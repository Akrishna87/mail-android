package com.akrishna87.mail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.format.Formatter
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import java.util.Calendar

@Composable
fun MailApp(vm: MailViewModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.notice) {
        vm.notice?.let {
            snackbar.showSnackbar(it)
            vm.notice = null
        }
    }
    val context = LocalContext.current
    LaunchedEffect(vm.openFile) {
        vm.openFile?.let { file ->
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(file.uri, file.mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                vm.notice = "No app on this phone can open this file"
            }
            vm.openFile = null
        }
    }
    BackHandler(enabled = vm.canGoBack) { vm.back() }

    when (val s = vm.screen) {
        Screen.Setup -> SetupScreen(vm)
        is Screen.Login -> LoginScreen(vm, s.provider, snackbar)
        Screen.Mailbox -> MailboxScreen(vm, snackbar)
        is Screen.Reader -> ReaderScreen(vm, s.uid, snackbar)
        is Screen.Compose -> ComposeScreen(vm, s.draft, snackbar)
    }
}

// --- Setup & sign in ----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupScreen(vm: MailViewModel) {
    Scaffold(topBar = { TopAppBar(title = { Text("Add your email account") }) }) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Provider.entries.forEach { p ->
                OutlinedCard(onClick = { vm.chooseProvider(p) }, modifier = Modifier.fillMaxWidth()) {
                    Text(p.label, Modifier.padding(20.dp), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginScreen(vm: MailViewModel, provider: Provider, snackbar: SnackbarHostState) {
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val uri = LocalUriHandler.current
    val microsoftLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.completeMicrosoftSignIn(it.data)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(provider.label) },
                navigationIcon = { BackButton(vm::back) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedTextField(name, { name = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                email, { email = it },
                label = { Text("Email address") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )

            when (provider) {
                Provider.MICROSOFT -> {
                    if (MicrosoftAuth.isConfigured) {
                        Text("You'll sign in on Microsoft's own page.")
                        Button(
                            onClick = { microsoftLauncher.launch(vm.microsoftSignInIntent(name, email)) },
                            enabled = email.isNotBlank() && !vm.loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Sign in with Microsoft") }
                    } else {
                        Text("Microsoft sign-in isn't set up in this build yet. See the README for the one-time setup.")
                    }
                }
                else -> {
                    OutlinedTextField(
                        password, { password = it },
                        label = { Text("App password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val (help, url) = if (provider == Provider.GMAIL) {
                        "Gmail needs an app password, not your normal password. Turn on 2-Step Verification, " +
                            "then create one on Google's app passwords page." to "https://myaccount.google.com/apppasswords"
                    } else {
                        "Yahoo needs an app password, not your normal password. Create one under " +
                            "Account security → Generate app password." to "https://login.yahoo.com/account/security"
                    }
                    Text(help, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { uri.openUri(url) }) { Text("Open app password page") }
                    Button(
                        onClick = { vm.signInWithPassword(provider, name, email, password) },
                        enabled = email.isNotBlank() && password.isNotBlank() && !vm.loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Sign in") }
                }
            }
        }
    }
}

// --- Mailbox ------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MailboxScreen(vm: MailViewModel, snackbar: SnackbarHostState) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var confirmSignOut by remember { mutableStateOf(false) }
    val current = vm.folders.firstOrNull { it.fullName == vm.folder }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                    Text(vm.account?.email.orEmpty(), Modifier.padding(12.dp), style = MaterialTheme.typography.titleSmall)
                    val status = remember(drawer.isOpen) { vm.newMailStatus() }
                    Text(
                        status,
                        Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    HorizontalDivider()
                    vm.folders.forEach { f ->
                        NavigationDrawerItem(
                            label = { Text(folderLabel(f)) },
                            badge = { if (f.unread > 0) Text(f.unread.toString()) },
                            selected = f.fullName == vm.folder,
                            onClick = {
                                scope.launch { drawer.close() }
                                vm.selectFolder(f.fullName)
                            },
                        )
                    }
                    HorizontalDivider()
                    NavigationDrawerItem(
                        label = { Text("Sign out") },
                        selected = false,
                        onClick = { confirmSignOut = true },
                    )
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(current?.let { folderLabel(it) } ?: "Inbox") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Folders")
                        }
                    },
                    actions = {
                        IconButton(onClick = vm::refresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                    },
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { vm.compose() },
                    icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    text = { Text("Compose") },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { pad ->
            PullToRefreshBox(
                isRefreshing = vm.refreshing,
                onRefresh = vm::refresh,
                modifier = Modifier.padding(pad).fillMaxSize(),
            ) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(vm.messages, key = { it.uid }) { m ->
                        MessageRow(m) { vm.open(m.uid) }
                    }
                    if (vm.hasMore) {
                        item {
                            TextButton(onClick = vm::loadMore, modifier = Modifier.fillMaxWidth()) {
                                Text("Load older messages")
                            }
                        }
                    }
                    if (vm.messages.isEmpty() && !vm.loading) {
                        item { Text("No messages", Modifier.padding(24.dp)) }
                    }
                    item { Spacer(Modifier.height(88.dp)) } // Room above the Compose button.
                }
                if (vm.loading && !vm.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("This removes the account from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    vm.signOut()
                }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MessageRow(m: MessageSummary, onClick: () -> Unit) {
    val context = LocalContext.current
    val weight = if (m.seen) FontWeight.Normal else FontWeight.Bold
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Avatar(m.from)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.from, Modifier.weight(1f), fontWeight = weight, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    m.date?.let { shortDate(context, it.time) }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = weight,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(m.subject, fontWeight = weight, maxLines = 1, overflow = TextOverflow.Ellipsis)
            m.preview?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A coloured circle with the sender's first letter, like Gmail. */
@Composable
private fun Avatar(name: String) {
    val letter = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '?'
    val color = AVATAR_COLORS[(name.lowercase().hashCode() and Int.MAX_VALUE) % AVATAR_COLORS.size]
    Box(Modifier.size(40.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(letter.toString(), color = Color.White, fontWeight = FontWeight.Bold)
    }
}

private val AVATAR_COLORS = listOf(
    Color(0xFF1A73E8), Color(0xFFD93025), Color(0xFF188038), Color(0xFFE37400),
    Color(0xFF9334E6), Color(0xFF007B83), Color(0xFFC5221F), Color(0xFF5F6368),
)

/** "Name <address>" -> "Name"; a bare address stays as is. */
private fun displayName(address: String): String =
    address.substringBefore(" <").trim().trim('"').ifEmpty { address }

/** Time for today, "Oct 26" for this year, otherwise the full date. */
private fun shortDate(context: Context, millis: Long): String = when {
    DateUtils.isToday(millis) -> DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_TIME)
    Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.YEAR) ==
        Calendar.getInstance().get(Calendar.YEAR) ->
        DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR)
    else -> DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NUMERIC_DATE)
}

private fun folderLabel(f: MailFolder): String = when (f.role) {
    "inbox" -> "Inbox"
    "sent" -> "Sent"
    "drafts" -> "Drafts"
    "trash" -> "Trash"
    "spam" -> "Spam"
    "archive" -> "Archive"
    "all" -> "All Mail"
    "starred" -> "Starred"
    else -> f.name
}

// --- Reader -------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderScreen(vm: MailViewModel, uid: Long, snackbar: SnackbarHostState) {
    var confirmDelete by remember { mutableStateOf(false) }
    var scrolled by remember(uid) { mutableStateOf(false) }
    val m = vm.message

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { BackButton(vm::back) },
                actions = {
                    IconButton(onClick = { confirmDelete = true }, enabled = m != null) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                    }
                },
            )
        },
        bottomBar = {
            if (m != null) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { vm.reply(m, all = false) }, Modifier.weight(1f)) { Text("Reply") }
                    OutlinedButton(onClick = { vm.reply(m, all = true) }, Modifier.weight(1f)) { Text("Reply all") }
                    OutlinedButton(onClick = { vm.forward(m) }, Modifier.weight(1f)) { Text("Forward") }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (m == null) {
                if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                return@Column
            }
            Text(
                m.subject,
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.titleLarge,
                maxLines = if (scrolled) 1 else 4,
                overflow = TextOverflow.Ellipsis,
            )
            // The sender details fold away while reading, leaving more room for the message.
            AnimatedVisibility(visible = !scrolled) {
                MessageHeader(m, onAttachment = { vm.openAttachment(m, it) })
            }
            HorizontalDivider()
            key(uid) { EmailBody(m, onScrolled = { scrolled = it }) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this message?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete(uid)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MessageHeader(m: MessageDetail, onAttachment: (ReceivedAttachment) -> Unit) {
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(displayName(m.from))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        displayName(m.from),
                        Modifier.weight(1f, fill = false),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    m.date?.let {
                        Text(
                            "  " + shortDate(context, it.time),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    m.fromAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "to " + (m.to + m.cc).joinToString(", ") { displayName(it) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (m.attachments.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                m.attachments.forEach { a ->
                    AssistChip(
                        onClick = { onAttachment(a) },
                        label = {
                            Text(
                                "📎 ${a.name} · ${Formatter.formatShortFileSize(context, a.size.toLong())}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }
    }
}

/** Shows the message with JavaScript off; links open in the phone's browser. */
@Composable
private fun EmailBody(m: MessageDetail, onScrolled: (Boolean) -> Unit) {
    val dark = isSystemInDarkTheme()
    // Matches the page background in emailDocument, so there is no flash while it loads.
    val background = if (m.html == null && dark) Color(0xFF1B1B1F) else Color.White
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(background.toArgb())
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                // Phone-width layout; wider emails are zoomed out to fit, like Gmail. This needs the
                // viewport meta in emailDocument to leave out initial-scale, which would pin the zoom at 100%.
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                setOnScrollChangeListener { _, _, y, _, _ -> onScrolled(y > 0) }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                        return true
                    }
                }
                loadDataWithBaseURL(null, emailDocument(m, dark), "text/html", "utf-8", null)
            }
        },
    )
}

private fun emailDocument(m: MessageDetail, dark: Boolean): String {
    val content = m.html ?: plainTextToHtml(m.text)
    val colors = if (m.html == null && dark) {
        "body{background:#1b1b1f;color:#e4e2e6} a{color:#a8c7fa}"
    } else {
        "body{background:#ffffff;color:#1f1f1f} a{color:#0b57d0}"
    }
    return """<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width">
<style>
body{margin:0;padding:12px 16px 24px;font-family:Roboto,sans-serif;font-size:15px;line-height:1.5;overflow-wrap:break-word}
$colors
img{max-width:100%;height:auto}
.plain{white-space:pre-wrap}
</style></head><body>$content</body></html>"""
}

private val urlPattern = Regex("""https?://[^\s<>"]+""")

private fun plainTextToHtml(text: String): String {
    val escaped = TextUtils.htmlEncode(text)
    val linked = urlPattern.replace(escaped) { """<a href="${it.value}">${it.value}</a>""" }
    return """<div class="plain">$linked</div>"""
}

// --- Compose ------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposeScreen(vm: MailViewModel, draft: Draft, snackbar: SnackbarHostState) {
    var to by rememberSaveable(draft) { mutableStateOf(draft.to) }
    var cc by rememberSaveable(draft) { mutableStateOf(draft.cc) }
    var subject by rememberSaveable(draft) { mutableStateOf(draft.subject) }
    var body by rememberSaveable(draft) { mutableStateOf(draft.body) }
    var attachments by rememberSaveable(draft) { mutableStateOf(ArrayList(draft.attachments)) }
    val context = LocalContext.current
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        attachments = ArrayList(attachments + uris.map(vm::describeAttachment))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New message") },
                navigationIcon = {
                    IconButton(onClick = vm::back) { Icon(Icons.Default.Close, contentDescription = "Discard") }
                },
                actions = {
                    TextButton(onClick = { pickFiles.launch("*/*") }) { Text("Attach") }
                    IconButton(
                        onClick = {
                            vm.send(draft.copy(to = to, cc = cc, subject = subject, body = body, attachments = attachments))
                        },
                        enabled = !vm.loading,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()).imePadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            val emailKeyboard = KeyboardOptions(keyboardType = KeyboardType.Email)
            OutlinedTextField(to, { to = it }, label = { Text("To") }, keyboardOptions = emailKeyboard, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cc, { cc = it }, label = { Text("Cc") }, keyboardOptions = emailKeyboard, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(subject, { subject = it }, label = { Text("Subject") }, modifier = Modifier.fillMaxWidth())
            attachments.forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "📎 ${a.name} (${Formatter.formatShortFileSize(context, a.size)})",
                        Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = { attachments = ArrayList(attachments - a) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove ${a.name}")
                    }
                }
            }
            OutlinedTextField(body, { body = it }, label = { Text("Message") }, minLines = 10, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
}
