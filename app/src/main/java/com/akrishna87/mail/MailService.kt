package com.akrishna87.mail

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.core.text.HtmlCompat
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPStore
import com.sun.mail.imap.protocol.BODY
import com.sun.mail.imap.protocol.FetchResponse
import com.sun.mail.imap.protocol.UID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Serializable
import java.util.Date
import java.util.Properties
import javax.activation.DataHandler
import javax.mail.Address
import javax.mail.AuthenticationFailedException
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.MessagingException
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.internet.MimeUtility
import javax.mail.search.BodyTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.OrTerm
import javax.mail.search.SubjectTerm
import javax.mail.util.ByteArrayDataSource

data class MailFolder(val fullName: String, val name: String, val role: String?, val unread: Int)

data class MessageSummary(
    val uid: Long,
    val from: String,
    val subject: String,
    val date: Date?,
    val seen: Boolean,
    val flagged: Boolean = false,
    val size: Int = 0,
    val preview: String? = null,
)

data class MessageDetail(
    val uid: Long,
    val from: String,
    val fromAddress: String,
    val to: List<String>,
    val cc: List<String>,
    val subject: String,
    val date: Date?,
    val html: String?,
    val text: String,
    val messageId: String?,
    val references: String?,
    val attachments: List<ReceivedAttachment> = emptyList(),
) {
    /** One-line preview for the message list. */
    val preview: String get() = text.replace(Regex("\\s+"), " ").trim().take(160)
}

/** An attachment on a received message; [index] is its position among the message's attachments. */
data class ReceivedAttachment(val index: Int, val name: String, val size: Int, val mimeType: String)

data class Draft(
    val to: String = "",
    val cc: String = "",
    val bcc: String = "",
    val subject: String = "",
    val body: String = "",
    val inReplyTo: String? = null,
    val references: String? = null,
    val attachments: List<Attachment> = emptyList(),
)

/** A file picked on the phone; [uri] is a content:// URI string. */
data class Attachment(val uri: String, val name: String, val size: Long, val mimeType: String) : Serializable

data class Page(val messages: List<MessageSummary>, val hasMore: Boolean)

/**
 * Talks to the provider's IMAP and SMTP servers directly from the phone.
 *
 * Mobile round trips are slow, so it keeps one connection and the current folder open,
 * and fetches each message body with a single command.
 */
class MailService(private val context: Context, private val store: AccountStore) {

    private suspend fun secret(account: Account): String =
        if (account.provider.usesOAuth) MicrosoftAuth.freshToken(context, store) else account.password

    private fun session(account: Account): Session {
        val p = Properties()
        p["mail.imaps.connectiontimeout"] = "15000"
        p["mail.imaps.timeout"] = "20000"
        p["mail.imaps.ssl.checkserveridentity"] = "true"
        // Fetch a body in one response instead of 16 KB pieces.
        p["mail.imaps.partialfetch"] = "false"
        p["mail.smtp.connectiontimeout"] = "15000"
        p["mail.smtp.timeout"] = "30000"
        p["mail.smtp.auth"] = "true"
        p["mail.smtp.ssl.checkserveridentity"] = "true"
        if (account.provider.smtpPort == 465) {
            p["mail.smtp.ssl.enable"] = "true"
        } else {
            p["mail.smtp.starttls.enable"] = "true"
            p["mail.smtp.starttls.required"] = "true"
        }
        if (account.provider.usesOAuth) {
            p["mail.imaps.auth.mechanisms"] = "XOAUTH2"
            p["mail.smtp.auth.mechanisms"] = "XOAUTH2"
        }
        return Session.getInstance(p)
    }

    // --- Connection ---------------------------------------------------------------------------

    private val lock = Mutex()
    private var connection: IMAPStore? = null
    private var connectionSession: Session? = null
    private var connectedAccount: Account? = null
    private var openFolder: IMAPFolder? = null
    private var lastUsed = 0L
    private var roles: Map<String, String> = emptyMap() // role -> folder full name

    /** Runs [block] on a background thread with the open IMAP connection, one operation at a time. */
    private suspend fun <T> imap(account: Account, block: (IMAPStore) -> T): T = lock.withLock {
        withContext(Dispatchers.IO) {
            val reused = connection != null && connectedAccount == account
            try {
                block(connected(account))
            } catch (e: MessagingException) {
                if (!reused || e is AuthenticationFailedException) throw e
                // The kept-open connection dropped (phone slept, network changed): reconnect once.
                discard()
                block(connected(account))
            } finally {
                lastUsed = System.currentTimeMillis()
            }
        }
    }

    private suspend fun connected(account: Account): IMAPStore {
        val current = connection
        if (current != null && connectedAccount == account) {
            val idle = System.currentTimeMillis() - lastUsed
            // Recently used: trust it (a failure retries). A while idle: check it. Long idle: it's
            // probably silently dead, and probing a dead connection hangs until the timeout.
            if (idle < TRUST_MS) return current
            if (idle < MAX_IDLE_MS && current.isConnected) return current
        }
        discard()
        val session = session(account)
        val imapStore = session.getStore("imaps") as IMAPStore
        imapStore.connect(account.provider.imapHost, 993, account.email, secret(account))
        connection = imapStore
        connectionSession = session
        connectedAccount = account
        return imapStore
    }

    /** Forgets the kept-open connection and logs it out in the background without waiting. */
    private fun discard() {
        connection?.let { old -> Thread { runCatching { old.close() } }.start() }
        connection = null
        openFolder = null
        connectedAccount = null
    }

    /** Closes the kept-open connection. Blocks, so call it off the main thread. */
    fun close() {
        runCatching { connection?.close() }
        connection = null
        openFolder = null
        connectedAccount = null
    }

    /** Returns [name] opened read-write, reusing it when it is already the open folder. */
    private fun selected(s: IMAPStore, name: String): IMAPFolder {
        openFolder?.let { if (it.fullName == name && it.isOpen) return it }
        openFolder?.let { old -> runCatching { if (old.isOpen) old.close(false) } }
        val f = s.getFolder(name) as IMAPFolder
        f.open(Folder.READ_WRITE)
        openFolder = f
        return f
    }

    // --- Reading ------------------------------------------------------------------------------

    suspend fun verify(account: Account) = imap(account) { }

    /** Lists folders; only the inbox gets an unread count, because counting every folder is slow. */
    suspend fun folders(account: Account): List<MailFolder> = imap(account) { s ->
        val all = s.defaultFolder.list("*")
            .map { it as IMAPFolder }
            .filter { f ->
                (f.type and Folder.HOLDS_MESSAGES) != 0 &&
                    f.attributes.none { it.equals("\\Noselect", true) || it.equals("\\NonExistent", true) }
            }
        roles = all.mapNotNull { f -> role(f)?.let { it to f.fullName } }.toMap()
        val inboxUnread = runCatching { s.getFolder("INBOX").unreadMessageCount }.getOrDefault(0)
        all.map { f -> MailFolder(f.fullName, f.name, role(f), if (role(f) == "inbox") inboxUnread else 0) }
            .sortedWith(compareBy({ ROLE_ORDER.indexOf(it.role).let { i -> if (i < 0) ROLE_ORDER.size else i } }, { it.fullName.lowercase() }))
    }

    suspend fun messages(account: Account, folderName: String, page: Int): Page = imap(account) { s ->
        val wasOpen = openFolder?.let { it.fullName == folderName && it.isOpen } == true
        val f = selected(s, folderName)
        // A folder that stayed open learns about new mail on the next command.
        if (wasOpen && page == 1) f.doCommand { it.noop(); null }
        val end = f.messageCount - (page - 1) * PAGE_SIZE
        if (end < 1) return@imap Page(emptyList(), false)
        val start = maxOf(1, end - PAGE_SIZE + 1)
        val msgs = f.getMessages(start, end)
        val profile = FetchProfile().apply {
            add(FetchProfile.Item.ENVELOPE)
            add(FetchProfile.Item.FLAGS)
            add(UIDFolder.FetchProfileItem.UID)
        }
        f.fetch(msgs, profile)
        Page(msgs.reversed().map { summarize(f, it) }, start > 1)
    }

    /** Server-side search of [folderName] by sender, subject or text; newest results first. */
    suspend fun search(account: Account, folderName: String, query: String): List<MessageSummary> = imap(account) { s ->
        val f = selected(s, folderName)
        val found = f.search(OrTerm(arrayOf(FromStringTerm(query), SubjectTerm(query), BodyTerm(query))))
        val msgs = found.takeLast(PAGE_SIZE).toTypedArray()
        val profile = FetchProfile().apply {
            add(FetchProfile.Item.ENVELOPE)
            add(FetchProfile.Item.FLAGS)
            add(UIDFolder.FetchProfileItem.UID)
        }
        f.fetch(msgs, profile)
        msgs.reversed().map { summarize(f, it) }
    }

    /** Downloads one message without marking it read. */
    suspend fun message(account: Account, folderName: String, uid: Long): MessageDetail =
        prefetch(account, folderName, listOf(uid))[uid] ?: throw MessagingException("This message no longer exists")

    /** Downloads several messages in one request, without marking them read. */
    suspend fun prefetch(account: Account, folderName: String, uids: List<Long>): Map<Long, MessageDetail> =
        imap(account) { s ->
            val raw = fetchRaw(selected(s, folderName), uids)
            val session = connectionSession!!
            raw.mapValues { (uid, bytes) -> parse(uid, MimeMessage(session, bytes.inputStream())) }
        }

    /** Sets or clears an IMAP system flag, such as Seen (read) or Flagged (starred), with one command. */
    suspend fun setFlag(account: Account, folderName: String, uid: Long, flag: String, on: Boolean) = imap(account) { s ->
        val f = selected(s, folderName)
        f.doCommand { p ->
            val r = p.command("UID STORE $uid ${if (on) "+" else "-"}FLAGS.SILENT ($flag)", null)
            p.notifyResponseHandlers(r.copyOfRange(0, r.size - 1))
            p.handleResult(r.last())
            null
        }
        Unit
    }

    /** Saves attachment [index] of a message into [dir] and returns the file. */
    suspend fun saveAttachment(account: Account, folderName: String, uid: Long, index: Int, dir: File): File =
        imap(account) { s ->
            val bytes = fetchRaw(selected(s, folderName), listOf(uid))[uid]
                ?: throw MessagingException("This message no longer exists")
            val part = attachmentParts(MimeMessage(connectionSession!!, bytes.inputStream())).getOrNull(index)
                ?: throw MessagingException("Attachment not found")
            dir.mkdirs()
            val name = (decodedFileName(part) ?: "attachment-${index + 1}").replace(Regex("[/\\\\:*?\"<>|]"), "_")
            File(dir, name).also { file -> part.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } } }
        }

    /** UID FETCH <uids> (BODY.PEEK[]) as one command; returns uid -> raw message bytes. */
    private fun fetchRaw(f: IMAPFolder, uids: List<Long>): Map<Long, ByteArray> {
        @Suppress("UNCHECKED_CAST")
        return f.doCommand { p ->
            val r = p.command("UID FETCH ${uids.joinToString(",")} (UID BODY.PEEK[])", null)
            p.notifyResponseHandlers(r.copyOfRange(0, r.size - 1))
            p.handleResult(r.last())
            r.filterIsInstance<FetchResponse>().mapNotNull { fr ->
                val uid = fr.getItem(UID::class.java)?.uid ?: return@mapNotNull null
                val body = fr.getItem(BODY::class.java) ?: return@mapNotNull null
                uid to body.byteArrayInputStream.readBytes()
            }.toMap()
        } as Map<Long, ByteArray>
    }

    // --- Writing ------------------------------------------------------------------------------

    suspend fun send(account: Account, draft: Draft) {
        val secret = secret(account)
        val msg = withContext(Dispatchers.IO) {
            val session = session(account)
            val msg = MimeMessage(session)
            msg.setFrom(InternetAddress(account.email, account.name.ifBlank { null }, "UTF-8"))
            msg.setRecipients(Message.RecipientType.TO, parseAddresses(draft.to))
            if (draft.cc.isNotBlank()) msg.setRecipients(Message.RecipientType.CC, parseAddresses(draft.cc))
            if (draft.bcc.isNotBlank()) msg.setRecipients(Message.RecipientType.BCC, parseAddresses(draft.bcc))
            msg.setSubject(draft.subject, "UTF-8")
            if (draft.attachments.isEmpty()) {
                msg.setText(draft.body, "UTF-8")
            } else {
                val parts = MimeMultipart()
                parts.addBodyPart(MimeBodyPart().apply { setText(draft.body, "UTF-8") })
                draft.attachments.forEach { parts.addBodyPart(attachmentPart(it)) }
                msg.setContent(parts)
            }
            msg.sentDate = Date()
            draft.inReplyTo?.let { msg.setHeader("In-Reply-To", it) }
            draft.references?.let { msg.setHeader("References", it) }
            msg.saveChanges()

            val transport = session.getTransport("smtp")
            transport.connect(account.provider.smtpHost, account.provider.smtpPort, account.email, secret)
            try {
                transport.sendMessage(msg, msg.allRecipients)
            } finally {
                transport.close()
            }
            msg
        }

        // Gmail and Outlook save sent mail automatically; Yahoo does not.
        if (account.provider == Provider.YAHOO) {
            runCatching {
                imap(account) { s ->
                    folderWithRole(s, "sent")?.let { sent ->
                        msg.setFlag(Flags.Flag.SEEN, true)
                        sent.appendMessages(arrayOf(msg))
                    }
                }
            }
        }
    }

    /** Moves the message to Trash, or deletes it permanently when already in Trash. */
    suspend fun delete(account: Account, folderName: String, uid: Long) = imap(account) { s ->
        val trash = folderWithRole(s, "trash")
        val f = selected(s, folderName)
        val m = f.getMessageByUID(uid) ?: return@imap
        if (trash != null && trash.fullName != f.fullName) {
            move(s, f, m, trash)
        } else {
            m.setFlag(Flags.Flag.DELETED, true)
            f.expunge()
        }
    }

    /** Moves the message out of the inbox: to Archive, or for Gmail to All Mail (removing the Inbox label). */
    suspend fun archive(account: Account, folderName: String, uid: Long) = imap(account) { s ->
        val target = folderWithRole(s, "archive") ?: folderWithRole(s, "all")
            ?: throw MessagingException("This account has no Archive folder")
        val f = selected(s, folderName)
        val m = f.getMessageByUID(uid) ?: return@imap
        move(s, f, m, target)
    }

    private fun move(s: IMAPStore, from: IMAPFolder, m: Message, to: Folder) {
        if (s.hasCapability("MOVE")) {
            from.moveMessages(arrayOf(m), to)
        } else {
            from.copyMessages(arrayOf(m), to)
            m.setFlag(Flags.Flag.DELETED, true)
            from.expunge()
        }
    }

    /** Returns the current INBOX UIDNEXT and any unread messages that arrived since [lastUidNext]. */
    suspend fun newMail(account: Account, lastUidNext: Long): Pair<Long, List<MessageSummary>> = imap(account) { s ->
        val f = selected(s, "INBOX")
        val next = f.uidNext
        if (lastUidNext <= 0 || next <= lastUidNext) return@imap next to emptyList()
        val fresh = f.getMessagesByUID(lastUidNext, next - 1)
            .filterNotNull()
            .filter { !it.isSet(Flags.Flag.SEEN) }
            .map { summarize(f, it) }
        next to fresh
    }

    // --- Parsing ------------------------------------------------------------------------------

    private fun parse(uid: Long, m: MimeMessage): MessageDetail {
        val body = Body()
        extract(m, body)
        val from = m.from?.firstOrNull() as? InternetAddress
        val html = body.html?.let { html ->
            // Show images embedded in the message (logos, signatures) by inlining them.
            body.inline.entries.fold(html) { acc, (cid, dataUri) -> acc.replace("cid:$cid", dataUri, ignoreCase = true) }
        }
        return MessageDetail(
            uid = uid,
            from = from?.toUnicodeString() ?: "(unknown)",
            fromAddress = from?.address.orEmpty(),
            to = addresses(m.getRecipients(Message.RecipientType.TO)),
            cc = addresses(m.getRecipients(Message.RecipientType.CC)),
            subject = m.subject ?: "(no subject)",
            date = m.sentDate,
            html = html,
            text = body.text ?: body.html?.let(::htmlToText) ?: "",
            messageId = m.getHeader("Message-ID")?.firstOrNull(),
            references = m.getHeader("References")?.firstOrNull(),
            attachments = attachmentParts(m).mapIndexed { i, p ->
                ReceivedAttachment(i, decodedFileName(p) ?: "attachment-${i + 1}", p.size, p.contentType.substringBefore(';'))
            },
        )
    }

    private class Body(var html: String? = null, var text: String? = null) {
        val inline = mutableMapOf<String, String>() // Content-ID -> data: URI
    }

    private fun extract(part: Part, body: Body) {
        when {
            isAttachment(part) -> Unit
            part.isMimeType("text/html") -> if (body.html == null) body.html = textOf(part)
            part.isMimeType("text/plain") -> if (body.text == null) body.text = textOf(part)
            part.isMimeType("multipart/*") -> {
                val mp = part.content as Multipart
                for (i in 0 until mp.count) extract(mp.getBodyPart(i), body)
            }
            part.isMimeType("image/*") && part is MimeBodyPart && part.contentID != null && part.size < MAX_INLINE_IMAGE -> {
                val bytes = part.inputStream.use { it.readBytes() }
                val type = part.contentType.substringBefore(';').trim()
                body.inline[part.contentID.trim('<', '>')] =
                    "data:$type;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
        }
    }

    /** Attachments in a stable order, so an index from [parse] finds the same part later. */
    private fun attachmentParts(part: Part): List<Part> = when {
        isAttachment(part) -> listOf(part)
        part.isMimeType("multipart/*") -> {
            val mp = part.content as Multipart
            (0 until mp.count).flatMap { attachmentParts(mp.getBodyPart(it)) }
        }
        else -> emptyList()
    }

    private fun isAttachment(part: Part): Boolean =
        Part.ATTACHMENT.equals(part.disposition, ignoreCase = true) ||
            (part.fileName != null && !part.isMimeType("text/*") && !(part is MimeBodyPart && part.contentID != null))

    private fun decodedFileName(part: Part): String? =
        part.fileName?.let { runCatching { MimeUtility.decodeText(it) }.getOrDefault(it) }

    private fun textOf(part: Part): String =
        (part.content as? String) ?: part.inputStream.bufferedReader().use { it.readText() }

    private fun htmlToText(html: String): String =
        HtmlCompat.fromHtml(
            html.replace(Regex("(?is)<(style|script|head)\\b.*?</\\1>"), ""),
            HtmlCompat.FROM_HTML_MODE_LEGACY,
        ).toString()

    private fun attachmentPart(a: Attachment): MimeBodyPart {
        val bytes = context.contentResolver.openInputStream(Uri.parse(a.uri))?.use { it.readBytes() }
            ?: throw MessagingException("Can't read ${a.name}")
        return MimeBodyPart().apply {
            dataHandler = DataHandler(ByteArrayDataSource(bytes, a.mimeType))
            fileName = MimeUtility.encodeText(a.name, "UTF-8", null)
            disposition = Part.ATTACHMENT
        }
    }

    private fun summarize(f: IMAPFolder, m: Message): MessageSummary {
        val from = m.from?.firstOrNull() as? InternetAddress
        return MessageSummary(
            uid = f.getUID(m),
            from = from?.personal ?: from?.address ?: "(unknown)",
            subject = m.subject ?: "(no subject)",
            date = m.sentDate ?: m.receivedDate,
            seen = m.isSet(Flags.Flag.SEEN),
            flagged = m.isSet(Flags.Flag.FLAGGED),
            size = m.size,
        )
    }

    private fun folderWithRole(s: IMAPStore, role: String): IMAPFolder? {
        roles[role]?.let { return s.getFolder(it) as IMAPFolder }
        return s.defaultFolder.list("*").map { it as IMAPFolder }.firstOrNull { role(it) == role }
    }

    private fun role(f: IMAPFolder): String? {
        if (f.fullName.equals("INBOX", ignoreCase = true)) return "inbox"
        val attrs = f.attributes.map { it.lowercase() }
        return SPECIAL_USE.entries.firstOrNull { it.key in attrs }?.value
    }

    private fun addresses(list: Array<Address>?): List<String> =
        list.orEmpty().map { (it as? InternetAddress)?.toUnicodeString() ?: it.toString() }

    private fun parseAddresses(value: String): Array<InternetAddress> =
        InternetAddress.parse(value.replace(';', ','), false)

    companion object {
        const val PAGE_SIZE = 50
        private const val TRUST_MS = 60 * 1000L
        private const val MAX_IDLE_MS = 8 * 60 * 1000L
        private const val MAX_INLINE_IMAGE = 2 * 1024 * 1024

        val ROLE_ORDER = listOf("inbox", "starred", "drafts", "sent", "archive", "all", "spam", "trash")

        private val SPECIAL_USE = mapOf(
            "\\sent" to "sent",
            "\\drafts" to "drafts",
            "\\trash" to "trash",
            "\\junk" to "spam",
            "\\archive" to "archive",
            "\\all" to "all",
            "\\flagged" to "starred",
        )
    }
}
