package com.akrishna87.mail

import android.content.Context
import androidx.core.text.HtmlCompat
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Properties
import javax.mail.Address
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
import javax.mail.internet.MimeMessage

data class MailFolder(val fullName: String, val name: String, val role: String?, val unread: Int)

data class MessageSummary(
    val uid: Long,
    val from: String,
    val subject: String,
    val date: Date?,
    val seen: Boolean,
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
)

data class Draft(
    val to: String = "",
    val cc: String = "",
    val subject: String = "",
    val body: String = "",
    val inReplyTo: String? = null,
    val references: String? = null,
)

data class Page(val messages: List<MessageSummary>, val hasMore: Boolean)

/** Talks to the provider's IMAP and SMTP servers directly from the phone. */
class MailService(private val context: Context, private val store: AccountStore) {

    private suspend fun secret(account: Account): String =
        if (account.provider.usesOAuth) MicrosoftAuth.freshToken(context, store) else account.password

    private fun session(account: Account): Session {
        val p = Properties()
        p["mail.imaps.connectiontimeout"] = "20000"
        p["mail.imaps.timeout"] = "30000"
        p["mail.imaps.ssl.checkserveridentity"] = "true"
        p["mail.smtp.connectiontimeout"] = "20000"
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

    /** Opens an IMAP connection, runs [block] on a background thread, and always closes it. */
    private suspend fun <T> imap(account: Account, block: (IMAPStore) -> T): T {
        val secret = secret(account)
        return withContext(Dispatchers.IO) {
            val imapStore = session(account).getStore("imaps") as IMAPStore
            imapStore.connect(account.provider.imapHost, 993, account.email, secret)
            try {
                block(imapStore)
            } finally {
                runCatching { imapStore.close() }
            }
        }
    }

    suspend fun verify(account: Account) = imap(account) { }

    suspend fun folders(account: Account): List<MailFolder> = imap(account) { s ->
        s.defaultFolder.list("*")
            .map { it as IMAPFolder }
            .filter { f ->
                (f.type and Folder.HOLDS_MESSAGES) != 0 &&
                    f.attributes.none { it.equals("\\Noselect", true) || it.equals("\\NonExistent", true) }
            }
            .map { f -> MailFolder(f.fullName, f.name, role(f), runCatching { f.unreadMessageCount }.getOrDefault(0)) }
            .sortedWith(compareBy({ ROLE_ORDER.indexOf(it.role).let { i -> if (i < 0) ROLE_ORDER.size else i } }, { it.fullName.lowercase() }))
    }

    suspend fun messages(account: Account, folderName: String, page: Int): Page = imap(account) { s ->
        val f = s.getFolder(folderName) as IMAPFolder
        f.open(Folder.READ_ONLY)
        try {
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
        } finally {
            f.close(false)
        }
    }

    /** Fetches a full message; reading it marks it as read on the server. */
    suspend fun message(account: Account, folderName: String, uid: Long): MessageDetail = imap(account) { s ->
        val f = s.getFolder(folderName) as IMAPFolder
        f.open(Folder.READ_WRITE)
        try {
            val m = f.getMessageByUID(uid) ?: throw MessagingException("This message no longer exists")
            val body = Body()
            extract(m, body)
            m.setFlag(Flags.Flag.SEEN, true)
            val from = m.from?.firstOrNull() as? InternetAddress
            MessageDetail(
                uid = uid,
                from = from?.toUnicodeString() ?: "(unknown)",
                fromAddress = from?.address.orEmpty(),
                to = addresses(m.getRecipients(Message.RecipientType.TO)),
                cc = addresses(m.getRecipients(Message.RecipientType.CC)),
                subject = m.subject ?: "(no subject)",
                date = m.sentDate ?: m.receivedDate,
                html = body.html,
                text = body.text
                    ?: body.html?.let { HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_LEGACY).toString() }
                    ?: "",
                messageId = m.getHeader("Message-ID")?.firstOrNull(),
                references = m.getHeader("References")?.firstOrNull(),
            )
        } finally {
            f.close(false)
        }
    }

    suspend fun send(account: Account, draft: Draft) {
        val secret = secret(account)
        val msg = withContext(Dispatchers.IO) {
            val session = session(account)
            val msg = MimeMessage(session)
            msg.setFrom(InternetAddress(account.email, account.name.ifBlank { null }, "UTF-8"))
            msg.setRecipients(Message.RecipientType.TO, parseAddresses(draft.to))
            if (draft.cc.isNotBlank()) msg.setRecipients(Message.RecipientType.CC, parseAddresses(draft.cc))
            msg.setSubject(draft.subject, "UTF-8")
            msg.setText(draft.body, "UTF-8")
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
                    findRole(s, "sent")?.let { sent ->
                        msg.setFlag(Flags.Flag.SEEN, true)
                        sent.appendMessages(arrayOf(msg))
                    }
                }
            }
        }
    }

    /** Moves the message to Trash, or deletes it permanently when already in Trash. */
    suspend fun delete(account: Account, folderName: String, uid: Long) = imap(account) { s ->
        val trash = findRole(s, "trash")
        val f = s.getFolder(folderName) as IMAPFolder
        f.open(Folder.READ_WRITE)
        try {
            val m = f.getMessageByUID(uid) ?: return@imap
            if (trash != null && trash.fullName != f.fullName) {
                if (s.hasCapability("MOVE")) {
                    f.moveMessages(arrayOf(m), trash)
                } else {
                    f.copyMessages(arrayOf(m), trash)
                    m.setFlag(Flags.Flag.DELETED, true)
                    f.expunge()
                }
            } else {
                m.setFlag(Flags.Flag.DELETED, true)
                f.expunge()
            }
        } finally {
            f.close(false)
        }
    }

    /** Returns the current INBOX UIDNEXT and any unread messages that arrived since [lastUidNext]. */
    suspend fun newMail(account: Account, lastUidNext: Long): Pair<Long, List<MessageSummary>> = imap(account) { s ->
        val f = s.getFolder("INBOX") as IMAPFolder
        f.open(Folder.READ_ONLY)
        try {
            val next = f.uidNext
            if (lastUidNext <= 0 || next <= lastUidNext) return@imap next to emptyList()
            val fresh = f.getMessagesByUID(lastUidNext, next - 1)
                .filterNotNull()
                .filter { !it.isSet(Flags.Flag.SEEN) }
                .map { summarize(f, it) }
            next to fresh
        } finally {
            f.close(false)
        }
    }

    private class Body(var html: String? = null, var text: String? = null)

    private fun extract(part: Part, body: Body) {
        val isAttachment = Part.ATTACHMENT.equals(part.disposition, ignoreCase = true)
        when {
            isAttachment -> Unit
            part.isMimeType("text/html") -> if (body.html == null) body.html = textOf(part)
            part.isMimeType("text/plain") -> if (body.text == null) body.text = textOf(part)
            part.isMimeType("multipart/*") -> {
                val mp = part.content as Multipart
                for (i in 0 until mp.count) extract(mp.getBodyPart(i), body)
            }
        }
    }

    private fun textOf(part: Part): String =
        (part.content as? String) ?: part.inputStream.bufferedReader().use { it.readText() }

    private fun summarize(f: IMAPFolder, m: Message): MessageSummary {
        val from = m.from?.firstOrNull() as? InternetAddress
        return MessageSummary(
            uid = f.getUID(m),
            from = from?.personal ?: from?.address ?: "(unknown)",
            subject = m.subject ?: "(no subject)",
            date = m.sentDate ?: m.receivedDate,
            seen = m.isSet(Flags.Flag.SEEN),
        )
    }

    private fun findRole(s: IMAPStore, role: String): IMAPFolder? =
        s.defaultFolder.list("*").map { it as IMAPFolder }.firstOrNull { role(it) == role }

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
