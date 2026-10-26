package com.akrishna87.mail

import android.content.Intent
import android.text.format.DateUtils
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch

@Composable
fun MailApp(vm: MailViewModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.notice) {
        vm.notice?.let {
            snackbar.showSnackbar(it)
            vm.notice = null
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
            Column(Modifier.padding(pad)) {
                if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.fillMaxSize()) {
                    items(vm.messages, key = { it.uid }) { m ->
                        MessageRow(m) { vm.open(m.uid) }
                        HorizontalDivider()
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
    val weight = if (m.seen) FontWeight.Normal else FontWeight.Bold
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row {
            Text(
                m.from,
                Modifier.weight(1f),
                fontWeight = weight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                m.date?.let { DateUtils.getRelativeTimeSpanString(it.time).toString() }.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(m.subject, fontWeight = weight, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
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
    val m = vm.message
    val context = LocalContext.current

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
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (m == null) return@Column

            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(m.subject, style = MaterialTheme.typography.titleLarge)
                Text(m.from, fontWeight = FontWeight.Bold)
                Text("To: ${m.to.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                if (m.cc.isNotEmpty()) Text("Cc: ${m.cc.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                m.date?.let {
                    Text(
                        DateUtils.formatDateTime(
                            context, it.time,
                            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    OutlinedButton(onClick = { vm.reply(m, all = false) }) { Text("Reply") }
                    OutlinedButton(onClick = { vm.reply(m, all = true) }) { Text("Reply all") }
                    OutlinedButton(onClick = { vm.forward(m) }) { Text("Forward") }
                }
            }
            HorizontalDivider()

            if (m.html != null) {
                key(uid) { HtmlBody(m.html) }
            } else {
                Text(m.text, Modifier.verticalScroll(rememberScrollState()).padding(16.dp))
            }
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

/** Shows HTML mail with JavaScript off; links open in the phone's browser. */
@Composable
private fun HtmlBody(html: String) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                        return true
                    }
                }
                loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
    )
}

// --- Compose ------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposeScreen(vm: MailViewModel, draft: Draft, snackbar: SnackbarHostState) {
    var to by rememberSaveable(draft) { mutableStateOf(draft.to) }
    var cc by rememberSaveable(draft) { mutableStateOf(draft.cc) }
    var subject by rememberSaveable(draft) { mutableStateOf(draft.subject) }
    var body by rememberSaveable(draft) { mutableStateOf(draft.body) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New message") },
                navigationIcon = {
                    IconButton(onClick = vm::back) { Icon(Icons.Default.Close, contentDescription = "Discard") }
                },
                actions = {
                    IconButton(
                        onClick = { vm.send(draft.copy(to = to, cc = cc, subject = subject, body = body)) },
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
            OutlinedTextField(body, { body = it }, label = { Text("Message") }, minLines = 10, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
}
