package com.akrishna87.mail

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateUtils
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sun.mail.util.MailConnectException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationService
import java.io.File
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
    data object Settings : Screen
    data object Search : Screen
}

/** A downloaded attachment for the UI to open with another app. */
data class OpenFile(val uri: Uri, val mimeType: String)

/** An archive or delete waiting out its Undo snackbar; [id] tells repeats of the same text apart. */
data class UndoOffer(val text: String, val id: Long)

private class PendingChange(val run: suspend () -> Unit, val restore: () -> Unit)

class MailViewModel(app: Application) : AndroidViewModel(app) {
    private val store = AccountStore(app)
    private val mail = MailService(app, store)
    private val cache = MailCache(app)
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
    var refreshing by mutableStateOf(false)
        private set

    /** One-off message for the snackbar; the UI clears it after showing. */
    var notice by mutableStateOf<String?>(null)

    /** One-off request to open a downloaded attachment; the UI clears it after opening. */
    var openFile by mutableStateOf<OpenFile?>(null)

    /** Set while an archive or delete can still be undone. */
    var undoOffer by mutableStateOf<UndoOffer?>(null)
        private set

    /** How often new mail is fetched in the background. */
    var syncMode by mutableStateOf(store.syncMode)
        private set

    /** Search shown in place of the folder's messages, or null. */
    var searchQuery by mutableStateOf<String?>(null)
        private set

    /** Recent searches, newest first, shown before typing like Gmail. */
    var recentSearches by mutableStateOf(store.recentSearches)
        private set

    /** Senders seen in loaded lists, for search suggestions. */
    var knownSenders by mutableStateOf(listOf<String>())
        private set

    // Search results can come from another folder (Gmail searches All Mail), and arrive in pages.
    private var searchFolder: String? = null
    private var searchUids: List<Long> = emptyList()

    /** The folder the shown messages belong to. */
    val activeFolder: String get() = searchFolder ?: folder

    private var page = 1
    private var running = 0
    private var lastRefresh = 0L
    private var prefetchJob: Job? = null
    private var pendingMicrosoft: Pair<String, String>? = null
    private var pendingChange: PendingChange? = null

    init {
        if (account != null) {
            lastRefresh = System.currentTimeMillis()
            viewModelScope.launch {
                // Show what was saved last time immediately, then update from the server.
                val (savedFolders, savedList) = withContext(Dispatchers.IO) { cache.loadFolders() to cache.loadList(folder) }
                if (messages.isEmpty()) messages = savedList
                if (folders.isEmpty()) folders = savedFolders
                refresh()
            }
        }
        // When background sync or push saves new mail while the app is open, show it.
        viewModelScope.launch {
            SyncEvents.inboxChanged.collect {
                val showingInbox = folder == "INBOX" && searchQuery == null && pendingChange == null
                if (account != null && showingInbox && System.currentTimeMillis() - lastRefresh > LIVE_REFRESH_MS) refresh()
            }
        }
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

    val canGoBack: Boolean get() = selection.isNotEmpty() || (screen !is Screen.Setup && screen !is Screen.Mailbox)

    /** Where a message opened from a list returns to. */
    private fun listScreen(): Screen = if (searchQuery != null) Screen.Search else Screen.Mailbox

    fun back() {
        if (selection.isNotEmpty()) return clearSelection()
        if (screen == Screen.Search) return clearSearch()
        screen = when (val s = screen) {
            is Screen.Login -> Screen.Setup
            is Screen.Reader -> listScreen()
            is Screen.Compose -> s.returnTo
            Screen.Settings -> Screen.Mailbox
            else -> s
        }
    }

    fun chooseProvider(provider: Provider) {
        screen = Screen.Login(provider)
    }

    /** A tapped new-mail notification: show the inbox with the new mail, and open [uid] if given. */
    fun openFromNotification(uid: Long?) {
        if (account == null) return
        if (screen is Screen.Compose) {
            refresh() // Don't throw away a draft being written; just bring in the new mail.
            return
        }
        if (folder != "INBOX" || searchQuery != null) selectFolder("INBOX")
        screen = Screen.Mailbox
        if (System.currentTimeMillis() - lastRefresh > NOTIFICATION_REFRESH_MS) refresh()
        if (uid != null) open(uid)
    }

    /** Called when the app comes back on screen: checks for new mail and warms up the connection. */
    fun onResume() {
        if (account != null && System.currentTimeMillis() - lastRefresh > RESUME_REFRESH_MS) refresh()
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
        syncMode = store.syncMode
        MailSync.apply(getApplication())
        folder = "INBOX"
        screen = Screen.Mailbox
        lastRefresh = System.currentTimeMillis()
        loadMessages(reset = true)
        startPrefetch()
        loadFolders()
    }

    fun signOut() {
        prefetchJob?.cancel()
        store.clear()
        MailSync.apply(getApplication()) // No account any more: stops push and background checks.
        Thread { cache.clear() }.start()
        account = null
        folders = emptyList()
        messages = emptyList()
        message = null
        folder = "INBOX"
        screen = Screen.Setup
    }

    // --- Mailbox ------------------------------------------------------------------------------

    fun refresh() {
        lastRefresh = System.currentTimeMillis()
        refreshing = true
        launchTask {
            try {
                val q = searchQuery
                if (q != null) runSearch(q) else loadMessages(reset = true)
            } finally {
                refreshing = false
            }
            startPrefetch()
            loadFolders()
        }
    }

    fun openSearch() {
        screen = Screen.Search
    }

    /** Runs a search (Gmail syntax) and shows the results on the search screen. */
    fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        selection = emptySet()
        searchQuery = q
        screen = Screen.Search
        recentSearches = (listOf(q) + recentSearches.filterNot { it.equals(q, ignoreCase = true) }).take(MAX_RECENT_SEARCHES)
        store.recentSearches = recentSearches
        prefetchJob?.cancel()
        messages = emptyList()
        hasMore = false
        launchTask {
            runSearch(q)
            startPrefetch()
        }
    }

    /** Turns a filter chip (e.g. is:unread) on or off for the current search. */
    fun toggleSearchFilter(operator: String) {
        search(SearchQuery.toggle(searchQuery.orEmpty(), operator))
    }

    fun forgetRecentSearches() {
        recentSearches = emptyList()
        store.recentSearches = emptyList()
    }

    private suspend fun runSearch(q: String) {
        val a = account ?: return
        val (inFolder, uids) = mail.search(a, folder, q)
        if (searchQuery != q) return
        searchFolder = inFolder
        searchUids = uids
        val first = mail.summaries(a, inFolder, uids.take(MailService.PAGE_SIZE))
        if (searchQuery != q) return
        messages = first
        hasMore = uids.size > first.size
    }

    private suspend fun loadMoreSearchResults() {
        val a = account ?: return
        val q = searchQuery ?: return
        val f = searchFolder ?: return
        val next = searchUids.drop(messages.size).take(MailService.PAGE_SIZE)
        val more = mail.summaries(a, f, next)
        if (searchQuery != q) return
        messages = (messages + more).distinctBy { it.uid }
        hasMore = searchUids.size > messages.size
    }

    /** Leaves search and shows the folder again. */
    fun clearSearch() {
        searchQuery = null
        searchFolder = null
        searchUids = emptyList()
        screen = Screen.Mailbox
        selectFolder(folder)
    }

    fun selectFolder(fullName: String) {
        selection = emptySet()
        searchQuery = null
        searchFolder = null
        folder = fullName
        prefetchJob?.cancel()
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { cache.loadList(fullName) }
            if (folder == fullName) messages = saved
        }
        launchTask {
            loadMessages(reset = true)
            startPrefetch()
        }
    }

    fun loadMore() = launchTask {
        if (searchQuery != null) return@launchTask loadMoreSearchResults()
        page += 1
        loadMessages(reset = false)
    }

    private suspend fun loadFolders() {
        val a = account ?: return
        folders = mail.folders(a)
        withContext(Dispatchers.IO) { cache.saveFolders(folders) }
    }

    private suspend fun loadMessages(reset: Boolean) {
        val a = account ?: return
        val f = folder
        if (reset) page = 1
        val result = mail.messages(a, f, page)
        if (f != folder) return
        // Keep previews of messages that were already downloaded.
        val previews = messages.associate { it.uid to it.preview }
        val fresh = result.messages.map { it.copy(preview = previews[it.uid]) }
        // New mail shifts the pages, so the next page can repeat a message.
        messages = if (reset) fresh else (messages + fresh).distinctBy { it.uid }
        hasMore = result.hasMore
        knownSenders = (knownSenders + messages.map { it.from }).distinct().take(MAX_KNOWN_SENDERS)
        if (reset) persistList(f)
    }

    /** Saves the shown list for [f], unless it is showing search results. */
    private suspend fun persistList(f: String) {
        if (searchQuery != null || f != folder) return
        val snapshot = messages
        withContext(Dispatchers.IO) { cache.saveList(f, snapshot) }
    }

    /** Downloads the newest messages in the background, so opening them needs no network. */
    private fun startPrefetch() {
        val a = account ?: return
        val f = activeFolder
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch {
            try {
                val wanted = messages.take(PREFETCH_COUNT).filter { it.size < PREFETCH_MAX_BYTES }
                val (missing, cachedPreviews) = withContext(Dispatchers.IO) {
                    val missing = wanted.filterNot { cache.has(f, it.uid) }
                    // Already-downloaded messages only need their preview filled in.
                    val previews = (wanted - missing.toSet()).filter { it.preview == null }
                        .mapNotNull { m -> cache.load(f, m.uid)?.let { m.uid to it.preview } }
                        .toMap()
                    missing to previews
                }
                applyPreviews(f, cachedPreviews)
                for (batch in missing.chunked(PREFETCH_BATCH)) {
                    val details = mail.prefetch(a, f, batch.map { it.uid })
                    withContext(Dispatchers.IO) { details.values.forEach { cache.save(f, it) } }
                    applyPreviews(f, details.mapValues { it.value.preview })
                }
                persistList(f)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Downloading ahead is best effort; opening a message fetches it if needed.
            }
        }
    }

    private fun applyPreviews(f: String, previews: Map<Long, String>) {
        if (previews.isEmpty() || f != activeFolder) return
        messages = messages.map { m -> previews[m.uid]?.let { m.copy(preview = it) } ?: m }
    }

    // --- Reading ------------------------------------------------------------------------------

    fun open(uid: Long) {
        val a = account ?: return
        val f = activeFolder
        message = null
        screen = Screen.Reader(uid)
        launchTask {
            val detail = withContext(Dispatchers.IO) { cache.load(f, uid) }
                ?: mail.message(a, f, uid).also { withContext(Dispatchers.IO) { cache.save(f, it) } }
            if (screen == Screen.Reader(uid)) message = detail
            markRead(a, f, uid)
        }
    }

    private fun markRead(a: Account, f: String, uid: Long) {
        MailSync.cancelNotification(getApplication(), uid)
        val known = messages.firstOrNull { it.uid == uid }
        if (known?.seen == true) return
        // A message opened from a notification may not be in the list yet; still mark it on the server.
        if (known != null) {
            messages = messages.map { if (it.uid == uid) it.copy(seen = true) else it }
            folders = folders.map { if (it.fullName == f) it.copy(unread = maxOf(0, it.unread - 1)) else it }
        }
        viewModelScope.launch {
            runCatching { mail.setFlag(a, f, listOf(uid), "\\Seen", true) }
            persistList(f)
        }
    }

    fun markUnread(uid: Long) {
        setRead(listOf(uid), read = false)
        screen = listScreen()
    }

    private fun setRead(uids: List<Long>, read: Boolean) {
        val a = account ?: return
        val f = activeFolder
        val changed = messages.count { it.uid in uids && it.seen != read }
        messages = messages.map { if (it.uid in uids) it.copy(seen = read) else it }
        val delta = if (read) -changed else changed
        folders = folders.map { if (it.fullName == f) it.copy(unread = maxOf(0, it.unread + delta)) else it }
        if (read) uids.forEach { MailSync.cancelNotification(getApplication(), it) }
        launchTask {
            mail.setFlag(a, f, uids, "\\Seen", read)
            persistList(f)
        }
    }

    fun toggleStar(uid: Long) = setStarred(listOf(uid), on = !isStarred(uid))

    private fun setStarred(uids: List<Long>, on: Boolean) {
        val a = account ?: return
        val f = activeFolder
        val before = messages
        messages = messages.map { if (it.uid in uids) it.copy(flagged = on) else it }
        launchTask(onError = { if (activeFolder == f) messages = before }) {
            mail.setFlag(a, f, uids, "\\Flagged", on)
            persistList(f)
        }
    }

    fun isStarred(uid: Long): Boolean = messages.firstOrNull { it.uid == uid }?.flagged == true

    // --- Selecting several messages (long press), like Gmail --------------------------------------

    /** Messages picked with a long press; while not empty the list is in selection mode. */
    var selection by mutableStateOf(setOf<Long>())
        private set

    fun toggleSelection(uid: Long) {
        selection = if (uid in selection) selection - uid else selection + uid
    }

    fun selectAll() {
        selection = messages.map { it.uid }.toSet()
    }

    fun clearSelection() {
        selection = emptySet()
    }

    fun archiveSelected() = archiveAll(selection.toList())

    fun deleteSelected() = deleteAll(selection.toList())

    fun markSelectedRead(read: Boolean) {
        setRead(selection.toList(), read)
        clearSelection()
    }

    fun starSelected(on: Boolean) {
        setStarred(selection.toList(), on)
        clearSelection()
    }

    fun openAttachment(m: MessageDetail, attachment: ReceivedAttachment) {
        val a = account ?: return
        val f = activeFolder
        notice = "Opening ${attachment.name}…"
        launchTask {
            val app = getApplication<Application>()
            val dir = File(app.cacheDir, "attachments/${m.uid}")
            val file = mail.saveAttachment(a, f, m.uid, attachment.index, dir)
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
            openFile = OpenFile(uri, attachment.mimeType)
        }
    }

    /** Archive is offered in the inbox when the account has an Archive (or Gmail All Mail) folder. */
    val canArchive: Boolean
        get() = folders.firstOrNull { it.fullName == activeFolder }?.role == "inbox" &&
            folders.any { it.role == "archive" || it.role == "all" }

    fun archive(uid: Long) = archiveAll(listOf(uid))

    fun delete(uid: Long) = deleteAll(listOf(uid))

    private fun archiveAll(uids: List<Long>) {
        val a = account ?: return
        val f = activeFolder
        stage(uids, "Archived") { mail.archive(a, f, uids) }
    }

    private fun deleteAll(uids: List<Long>) {
        val a = account ?: return
        val f = activeFolder
        stage(uids, "Deleted") { mail.delete(a, f, uids) }
    }

    /**
     * Removes the message from the list right away and offers Undo, like Gmail. The server change
     * runs when the snackbar times out, another change replaces it, or the app leaves the screen.
     */
    private fun stage(uids: List<Long>, text: String, change: suspend () -> Unit) {
        if (messages.none { it.uid in uids }) return
        commitPendingChange()
        val f = activeFolder
        val before = messages
        messages = messages.filterNot { it.uid in uids }
        selection = selection - uids.toSet()
        uids.forEach { MailSync.cancelNotification(getApplication(), it) }
        if (screen is Screen.Reader) screen = listScreen()
        pendingChange = PendingChange(
            run = {
                change()
                persistList(f)
            },
            restore = { if (activeFolder == f) messages = before },
        )
        undoOffer = UndoOffer(if (uids.size == 1) text else "${uids.size} ${text.lowercase()}", System.nanoTime())
    }

    fun undoPendingChange() {
        pendingChange?.restore?.invoke()
        pendingChange = null
        undoOffer = null
    }

    fun commitPendingChange() {
        val change = pendingChange ?: return
        pendingChange = null
        undoOffer = null
        launchTask(onError = change.restore) { change.run() }
    }

    fun openSettings() {
        screen = Screen.Settings
    }

    fun changeSyncMode(mode: SyncMode) {
        store.syncMode = mode
        syncMode = mode
        MailSync.apply(getApplication())
    }

    /** Status line for background sync, shown in Settings and the account dialog. */
    fun newMailStatus(): String {
        if (store.syncMode == SyncMode.MANUAL) return "Background checks are off"
        if (!NotificationManagerCompat.from(getApplication()).areNotificationsEnabled()) {
            return "Notifications are off for Mailroom. Turn them on in Android Settings → Apps → Mailroom."
        }
        val time = store.lastCheckTime
        if (time == 0L) return "Mail hasn't been checked in the background yet"
        val ago = DateUtils.getRelativeTimeSpanString(time).toString()
        val error = store.lastCheckError
        return if (error.isEmpty()) "Last checked in the background $ago" else "Background check failed $ago: $error"
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
        const val PREFETCH_COUNT = 50 // The whole first page of the list.
        const val PREFETCH_BATCH = 5
        const val PREFETCH_MAX_BYTES = 512 * 1024 // Big messages download when opened, not ahead.
        const val RESUME_REFRESH_MS = 60 * 1000L
        const val NOTIFICATION_REFRESH_MS = 5 * 1000L
        const val LIVE_REFRESH_MS = 5 * 1000L
        const val MAX_RECENT_SEARCHES = 10
        const val MAX_KNOWN_SENDERS = 500 // Skips only the refresh the launch itself just started.
    }
}
