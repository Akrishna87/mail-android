package com.akrishna87.mail

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateUtils
import androidx.core.app.NotificationManagerCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sun.mail.util.MailConnectException
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationService
import java.net.UnknownHostException
import java.text.DateFormat
import javax.mail.AuthenticationFailedException
import kotlin.coroutines.cancellation.CancellationException

sealed interface Screen {
    data object Setup : Screen
    data class Login(val provider: Provider) : Screen
    data object Mailbox : Screen
    data class Reader(val uid: Long) : Screen
    data class Compose(val draft: Draft, val returnTo: Screen) : Screen
}

class MailViewModel(app: Application) : AndroidViewModel(app) {
    private val store = AccountStore(app)
    private val mail = MailService(app, store)
    private val authService = AuthorizationService(app)

    var account by mutableStateOf(store.load())
        private set
    var screen by mutableStateOf<Screen>(if (account == null) Screen.Setup else Screen.Mailbox)
        private set
    var folders by mutableStateOf(listOf<MailFolder>())
        private set
    var folder by mutableStateOf("INBOX")
        private set
    var messages by mutableStateOf(listOf<MessageSummary>())
        private set
    var hasMore by mutableStateOf(false)
        private set
    var message by mutableStateOf<MessageDetail?>(null)
        private set
    var loading by mutableStateOf(false)
        private set

    /** One-off message for the snackbar; the UI clears it after showing. */
    var notice by mutableStateOf<String?>(null)

    private var page = 1
    private var running = 0
    private var pendingMicrosoft: Pair<String, String>? = null

    init {
        if (account != null) refresh()
    }

    override fun onCleared() {
        authService.dispose()
        Thread { mail.close() }.start()
    }

    /** Runs [block] in the background with a progress bar; on failure shows the error and runs [onError]. */
    private fun launchTask(onError: () -> Unit = {}, block: suspend () -> Unit) {
        viewModelScope.launch {
            running += 1
            loading = true
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = friendly(e)
                onError()
            } finally {
                running -= 1
                loading = running > 0
            }
        }
    }

    private fun friendly(e: Exception): String = when (e) {
        is AuthenticationFailedException ->
            "Sign-in failed. Check your email address and password."
        is MailConnectException, is UnknownHostException ->
            "Can't reach the mail server. Check your internet connection."
        else -> e.message ?: e.javaClass.simpleName
    }

    // --- Navigation ---------------------------------------------------------------------------

    val canGoBack: Boolean get() = screen !is Screen.Setup && screen !is Screen.Mailbox

    fun back() {
        screen = when (val s = screen) {
            is Screen.Login -> Screen.Setup
            is Screen.Reader -> Screen.Mailbox
            is Screen.Compose -> s.returnTo
            else -> s
        }
    }

    fun chooseProvider(provider: Provider) {
        screen = Screen.Login(provider)
    }

    // --- Sign in / out ------------------------------------------------------------------------

    fun signInWithPassword(provider: Provider, name: String, email: String, password: String) = launchTask {
        // Google shows app passwords with spaces; IMAP expects them without.
        val account = Account(provider, email.trim(), name.trim(), password.replace(" ", ""))
        mail.verify(account)
        finishSignIn(account)
    }

    fun microsoftSignInIntent(name: String, email: String): Intent {
        pendingMicrosoft = name.trim() to email.trim()
        return MicrosoftAuth.signInIntent(authService, email.trim())
    }

    fun completeMicrosoftSignIn(data: Intent?) = launchTask {
        val (name, email) = pendingMicrosoft ?: return@launchTask
        if (data == null) throw Exception("Microsoft sign-in was cancelled")
        store.authState = MicrosoftAuth.completeSignIn(authService, data)
        val account = Account(Provider.MICROSOFT, email, name)
        try {
            mail.verify(account)
        } catch (e: Exception) {
            store.authState = ""
            throw e
        }
        finishSignIn(account)
    }

    private suspend fun finishSignIn(account: Account) {
        store.save(account)
        store.lastUidNext = 0
        this.account = account
        NewMailWorker.schedule(getApplication())
        folder = "INBOX"
        screen = Screen.Mailbox
        loadMessages(reset = true)
        loadFolders()
    }

    fun signOut() {
        NewMailWorker.cancel(getApplication())
        store.clear()
        account = null
        folders = emptyList()
        messages = emptyList()
        message = null
        folder = "INBOX"
        screen = Screen.Setup
    }

    // --- Mailbox ------------------------------------------------------------------------------

    fun refresh() = launchTask {
        loadMessages(reset = true) // Messages first, so the list shows before the folder counts.
        loadFolders()
    }

    fun selectFolder(fullName: String) {
        folder = fullName
        messages = emptyList()
        launchTask { loadMessages(reset = true) }
    }

    fun loadMore() = launchTask {
        page += 1
        loadMessages(reset = false)
    }

    private suspend fun loadFolders() {
        val a = account ?: return
        folders = mail.folders(a)
    }

    private suspend fun loadMessages(reset: Boolean) {
        val a = account ?: return
        if (reset) page = 1
        val result = mail.messages(a, folder, page)
        // New mail shifts the pages, so the next page can repeat a message.
        messages = if (reset) result.messages else (messages + result.messages).distinctBy { it.uid }
        hasMore = result.hasMore
    }

    // --- Reading ------------------------------------------------------------------------------

    fun open(uid: Long) {
        val a = account ?: return
        val f = folder
        message = null
        screen = Screen.Reader(uid)
        launchTask {
            message = mail.message(a, f, uid)
            val wasUnread = messages.any { it.uid == uid && !it.seen }
            messages = messages.map { if (it.uid == uid) it.copy(seen = true) else it }
            if (wasUnread) {
                folders = folders.map {
                    if (it.fullName == f) it.copy(unread = maxOf(0, it.unread - 1)) else it
                }
            }
        }
    }

    /** Removes the message from the list right away; puts it back if the server refuses. */
    fun delete(uid: Long) {
        val a = account ?: return
        val f = folder
        val before = messages
        messages = messages.filterNot { it.uid == uid }
        screen = Screen.Mailbox
        notice = "Message deleted"
        launchTask(onError = { if (folder == f) messages = before }) {
            mail.delete(a, f, uid)
        }
    }

    /** Status line for the new-mail check, shown in the folder menu. */
    fun newMailStatus(): String {
        if (!NotificationManagerCompat.from(getApplication()).areNotificationsEnabled()) {
            return "Notifications are off for Mailroom. Turn them on in Android Settings → Apps → Mailroom."
        }
        val time = store.lastCheckTime
        if (time == 0L) return "New-mail check hasn't run yet"
        val ago = DateUtils.getRelativeTimeSpanString(time).toString()
        val error = store.lastCheckError
        return if (error.isEmpty()) "New mail last checked $ago" else "New-mail check failed $ago: $error"
    }

    // --- Writing ------------------------------------------------------------------------------

    fun compose(draft: Draft = Draft()) {
        screen = Screen.Compose(draft, returnTo = screen)
    }

    fun reply(m: MessageDetail, all: Boolean) {
        val me = account?.email.orEmpty()
        val cc = if (all) {
            (m.to + m.cc).filterNot { addr ->
                listOf(me, m.fromAddress).any { it.isNotEmpty() && addr.contains(it, ignoreCase = true) }
            }
        } else {
            emptyList()
        }
        compose(
            Draft(
                to = m.fromAddress,
                cc = cc.joinToString(", "),
                subject = if (m.subject.startsWith("Re:", ignoreCase = true)) m.subject else "Re: ${m.subject}",
                body = "\n\nOn ${formatDate(m)}, ${m.from} wrote:\n" + m.text.lines().joinToString("\n") { "> $it" },
                inReplyTo = m.messageId,
                references = listOfNotNull(m.references, m.messageId).joinToString(" ").ifBlank { null },
            ),
        )
    }

    fun forward(m: MessageDetail) {
        compose(
            Draft(
                subject = if (m.subject.startsWith("Fwd:", ignoreCase = true)) m.subject else "Fwd: ${m.subject}",
                body = "\n\n---------- Forwarded message ----------\n" +
                    "From: ${m.from}\nDate: ${formatDate(m)}\nSubject: ${m.subject}\n" +
                    "To: ${m.to.joinToString(", ")}\n\n${m.text}",
            ),
        )
    }

    fun describeAttachment(uri: Uri): Attachment {
        val resolver = getApplication<Application>().contentResolver
        var name = uri.lastPathSegment ?: "attachment"
        var size = 0L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                if (!it.isNull(0)) name = it.getString(0)
                if (!it.isNull(1)) size = it.getLong(1)
            }
        }
        return Attachment(uri.toString(), name, size, resolver.getType(uri) ?: "application/octet-stream")
    }

    /** Closes the compose screen right away; reopens it with the draft if sending fails. */
    fun send(draft: Draft) {
        val a = account ?: return
        val s = screen as? Screen.Compose ?: return
        if (draft.to.isBlank()) {
            notice = "Add at least one recipient"
            return
        }
        if (draft.attachments.sumOf { it.size } > MAX_ATTACHMENTS_BYTES) {
            notice = "Attachments are too big. The limit is 25 MB in total."
            return
        }
        screen = s.returnTo
        notice = "Sending…"
        launchTask(onError = { screen = s.copy(draft = draft) }) {
            mail.send(a, draft)
            notice = "Message sent"
        }
    }

    private fun formatDate(m: MessageDetail): String =
        m.date?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(it) }.orEmpty()

    private companion object {
        const val MAX_ATTACHMENTS_BYTES = 25L * 1024 * 1024 // Gmail, Yahoo and Outlook all allow about 25 MB.
    }
}
