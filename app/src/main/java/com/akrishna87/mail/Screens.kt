package com.akrishna87.mail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
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
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
    // Archive and delete wait for the Undo snackbar before they reach the server, like Gmail.
    LaunchedEffect(vm.undoOffer) {
        val offer = vm.undoOffer ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(offer.text, actionLabel = "Undo", duration = SnackbarDuration.Short)
        if (vm.undoOffer == offer) {
            if (result == SnackbarResult.ActionPerformed) vm.undoPendingChange() else vm.commitPendingChange()
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
        Screen.Settings -> SettingsScreen(vm)
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
    val listState = rememberLazyListState()
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    var showAccount by remember { mutableStateOf(false) }
    val current = vm.folders.firstOrNull { it.fullName == vm.folder }

    BackHandler(enabled = vm.searchQuery != null) { vm.clearSearch() }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            FolderDrawer(
                vm,
                onSelect = { f ->
                    scope.launch { drawer.close() }
                    vm.selectFolder(f.fullName)
                },
                onSettings = {
                    scope.launch { drawer.close() }
                    vm.openSettings()
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                SearchBar(
                    vm = vm,
                    onMenu = { scope.launch { drawer.open() } },
                    onAccount = { showAccount = true },
                )
            },
            floatingActionButton = {
                // Like Gmail, the button shrinks to just the pencil once you scroll.
                ExtendedFloatingActionButton(
                    onClick = { vm.compose() },
                    expanded = atTop,
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
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    item {
                        Text(
                            vm.searchQuery?.let { "Results for \u201C$it\u201D" } ?: current?.let { folderLabel(it) } ?: "Inbox",
                            Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(vm.messages, key = { it.uid }) { m ->
                        SwipeableRow(vm, m)
                    }
                    if (vm.hasMore) {
                        item {
                            TextButton(onClick = vm::loadMore, modifier = Modifier.fillMaxWidth()) {
                                Text("Load older messages")
                            }
                        }
                    }
                    if (vm.messages.isEmpty() && !vm.loading) {
                        item {
                            Text(
                                if (vm.searchQuery != null) "No results" else "Nothing in ${current?.let { folderLabel(it) } ?: "this folder"}",
                                Modifier.fillMaxWidth().padding(48.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item { Spacer(Modifier.height(96.dp)) } // Room above the Compose button.
                }
                if (vm.loading && !vm.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }

    if (showAccount) AccountDialog(vm, onDismiss = { showAccount = false })
}

/** Gmail's rounded "Search in mail" bar, with the folder menu and account avatar inside it. */
@Composable
private fun SearchBar(vm: MailViewModel, onMenu: () -> Unit, onAccount: () -> Unit) {
    var query by rememberSaveable { mutableStateOf(vm.searchQuery.orEmpty()) }
    val focus = LocalFocusManager.current
    LaunchedEffect(vm.searchQuery) { if (vm.searchQuery == null) query = "" }

    Row(
        Modifier
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (vm.searchQuery != null) {
            IconButton(onClick = vm::clearSearch) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
            }
        } else {
            IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, contentDescription = "Folders") }
        }
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                vm.search(query)
                focus.clearFocus()
            }),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text("Search in mail", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    field()
                }
            },
        )
        if (query.isNotEmpty()) {
            IconButton(onClick = {
                query = ""
                vm.clearSearch()
            }) { Icon(Icons.Default.Close, contentDescription = "Clear search") }
        }
        Box(Modifier.padding(horizontal = 8.dp).clip(CircleShape).clickable(onClick = onAccount)) {
            Avatar(vm.account?.name?.ifBlank { null } ?: vm.account?.email.orEmpty(), size = 32.dp)
        }
    }
}

@Composable
private fun AccountDialog(vm: MailViewModel, onDismiss: () -> Unit) {
    var confirmSignOut by remember { mutableStateOf(false) }
    val status = remember { vm.newMailStatus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(vm.account?.name?.ifBlank { null } ?: "Account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(vm.account?.email.orEmpty())
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = { confirmSignOut = true }) { Text("Sign out") } },
    )
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("This removes the account and its saved mail from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    onDismiss()
                    vm.signOut()
                }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FolderDrawer(vm: MailViewModel, onSelect: (MailFolder) -> Unit, onSettings: () -> Unit) {
    ModalDrawerSheet {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                "Mailroom",
                Modifier.padding(start = 28.dp, top = 24.dp, bottom = 16.dp),
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFFD93025),
            )
            vm.folders.forEach { f ->
                NavigationDrawerItem(
                    icon = { Icon(folderIcon(f), contentDescription = null) },
                    label = { Text(folderLabel(f)) },
                    badge = { if (f.unread > 0) Text(f.unread.toString()) },
                    selected = f.fullName == vm.folder && vm.searchQuery == null,
                    onClick = { onSelect(f) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 28.dp))
            NavigationDrawerItem(
                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                label = { Text("Settings") },
                selected = false,
                onClick = onSettings,
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun folderIcon(f: MailFolder): Painter = when (f.role) {
    "inbox" -> painterResource(R.drawable.ic_inbox)
    "starred" -> painterResource(R.drawable.ic_star_border)
    "sent" -> rememberVectorPainter(Icons.AutoMirrored.Filled.Send)
    "drafts" -> painterResource(R.drawable.ic_drafts)
    "all" -> painterResource(R.drawable.ic_mark_unread)
    "archive" -> painterResource(R.drawable.ic_archive)
    "spam" -> painterResource(R.drawable.ic_report)
    "trash" -> rememberVectorPainter(Icons.Default.Delete)
    else -> painterResource(R.drawable.ic_label)
}

private fun folderLabel(f: MailFolder): String = when (f.role) {
    "inbox" -> "Inbox"
    "sent" -> "Sent"
    "drafts" -> "Drafts"
    "trash" -> "Trash"
    "spam" -> "Spam"
    "archive" -> "Archive"
    "all" -> "All mail"
    "starred" -> "Starred"
    else -> f.name
}

/** Swipe either way to archive (in the inbox) or delete, with Undo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableRow(vm: MailViewModel, m: MessageSummary) {
    val archive = vm.canArchive
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.Settled) return@rememberSwipeToDismissBoxState false
            if (archive) vm.archive(m.uid) else vm.delete(m.uid)
            true
        },
    )
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val toEnd = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Box(
                Modifier.fillMaxSize().background(if (archive) Color(0xFF188038) else Color(0xFFD93025)).padding(horizontal = 24.dp),
                contentAlignment = if (toEnd) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Icon(
                    if (archive) painterResource(R.drawable.ic_archive) else rememberVectorPainter(Icons.Default.Delete),
                    contentDescription = null,
                    tint = Color.White,
                )
            }
        },
    ) {
        MessageRow(m, onClick = { vm.open(m.uid) }, onStar = { vm.toggleStar(m.uid) })
    }
}

@Composable
private fun MessageRow(m: MessageSummary, onClick: () -> Unit, onStar: () -> Unit) {
    val context = LocalContext.current
    val unread = !m.seen
    val weight = if (unread) FontWeight.Bold else FontWeight.Normal
    val strong = MaterialTheme.colorScheme.onSurface
    val soft = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Avatar(m.from)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    m.from,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = weight,
                    color = if (unread) strong else soft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    m.date?.let { shortDate(context, it.time) }.orEmpty(),
                    Modifier.padding(start = 8.dp, end = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = weight,
                    color = if (unread) strong else soft,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        m.subject,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = weight,
                        color = if (unread) strong else soft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        m.preview.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = soft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StarButton(m.flagged, onStar)
            }
        }
    }
}

@Composable
private fun StarButton(starred: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (starred) {
            Icon(Icons.Default.Star, contentDescription = "Starred", tint = Color(0xFFF4B400))
        } else {
            Icon(painterResource(R.drawable.ic_star_border), contentDescription = "Not starred", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// --- Settings -----------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(vm: MailViewModel) {
    val context = LocalContext.current
    val power = remember { context.getSystemService(PowerManager::class.java) }
    var unrestricted by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    val askBattery = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        unrestricted = power.isIgnoringBatteryOptimizations(context.packageName)
    }
    val status = remember(vm.syncMode) { vm.newMailStatus() }
    val soft = MaterialTheme.colorScheme.onSurfaceVariant

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { BackButton(vm::back) }) },
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {
            Text(
                "Check for new mail",
                Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            SyncMode.entries.forEach { mode ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = vm.syncMode == mode, onClick = { vm.changeSyncMode(mode) }, role = Role.RadioButton)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = vm.syncMode == mode, onClick = null)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                        if (mode.detail.isNotEmpty()) Text(mode.detail, style = MaterialTheme.typography.bodySmall, color = soft)
                    }
                }
            }
            Text(status, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = soft)

            if (vm.syncMode != SyncMode.MANUAL && !unrestricted) {
                HorizontalDivider()
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Battery", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "Android may delay or stop background checks to save battery. For reliable new mail, " +
                            "let Mailroom run in the background.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = {
                        askBattery.launch(
                            Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.parse("package:${context.packageName}")),
                        )
                    }) { Text("Allow background use") }
                }
            }
            if (vm.syncMode == SyncMode.PUSH) {
                Text(
                    "Android requires a notification while push is on. To hide it, long-press it and turn off " +
                        "\u201CPush connection\u201D notifications; push keeps working.",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = soft,
                )
            }
        }
    }
}

// --- Reader -------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderScreen(vm: MailViewModel, uid: Long, snackbar: SnackbarHostState) {
    var scrolled by remember(uid) { mutableStateOf(false) }
    val m = vm.message

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { BackButton(vm::back) },
                actions = {
                    if (vm.canArchive) {
                        IconButton(onClick = { vm.archive(uid) }) {
                            Icon(painterResource(R.drawable.ic_archive), contentDescription = "Archive")
                        }
                    }
                    IconButton(onClick = { vm.delete(uid) }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                    IconButton(onClick = { vm.markUnread(uid) }) {
                        Icon(painterResource(R.drawable.ic_mark_unread), contentDescription = "Mark unread")
                    }
                },
            )
        },
        bottomBar = {
            if (m != null) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PillButton(R.drawable.ic_reply, "Reply", Modifier.weight(1f)) { vm.reply(m, all = false) }
                    PillButton(R.drawable.ic_reply_all, "Reply all", Modifier.weight(1.2f)) { vm.reply(m, all = true) }
                    PillButton(R.drawable.ic_forward, "Forward", Modifier.weight(1.1f)) { vm.forward(m) }
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
            Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(top = 6.dp)) {
                    Text(
                        m.subject,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = if (scrolled) 1 else 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    vm.folders.firstOrNull { it.fullName == vm.folder }?.let { FolderTag(folderLabel(it)) }
                }
                StarButton(vm.isStarred(uid)) { vm.toggleStar(uid) }
            }
            // The sender details fold away while reading, leaving more room for the message.
            AnimatedVisibility(visible = !scrolled) {
                SenderHeader(
                    m,
                    me = vm.account?.email.orEmpty(),
                    onReply = { vm.reply(m, all = false) },
                    onAttachment = { vm.openAttachment(m, it) },
                )
            }
            key(uid) { EmailBody(m, onScrolled = { scrolled = it }) }
        }
    }
}

@Composable
private fun FolderTag(label: String) {
    Text(
        label,
        Modifier
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PillButton(icon: Int, label: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Icon(painterResource(icon), contentDescription = null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1)
    }
}

@Composable
private fun SenderHeader(
    m: MessageDetail,
    me: String,
    onReply: () -> Unit,
    onAttachment: (ReceivedAttachment) -> Unit,
) {
    val context = LocalContext.current
    var expanded by remember(m.uid) { mutableStateOf(false) }
    val soft = MaterialTheme.colorScheme.onSurfaceVariant
    val recipients = (m.to + m.cc).joinToString(", ") {
        if (me.isNotEmpty() && it.contains(me, ignoreCase = true)) "me" else displayName(it)
    }
    Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(displayName(m.from))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        displayName(m.from),
                        Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    m.date?.let {
                        Text("  " + shortDate(context, it.time), style = MaterialTheme.typography.labelMedium, color = soft)
                    }
                }
                Row(
                    Modifier.clip(RoundedCornerShape(4.dp)).clickable { expanded = !expanded },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "to $recipients",
                        Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodySmall,
                        color = soft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "Hide details" else "Show details",
                        tint = soft,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            IconButton(onClick = onReply) { Icon(painterResource(R.drawable.ic_reply), contentDescription = "Reply") }
        }
        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(end = 12.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DetailLine("From", m.from)
                DetailLine("To", m.to.joinToString(", "))
                if (m.cc.isNotEmpty()) DetailLine("Cc", m.cc.joinToString(", "))
                m.date?.let {
                    DetailLine(
                        "Date",
                        DateUtils.formatDateTime(context, it.time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR),
                    )
                }
            }
        }
        if (m.attachments.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                m.attachments.forEach { a ->
                    AssistChip(
                        onClick = { onAttachment(a) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_attach), contentDescription = null, Modifier.size(18.dp)) },
                        label = {
                            Text(
                                "${a.name} · ${Formatter.formatShortFileSize(context, a.size.toLong())}",
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

@Composable
private fun DetailLine(label: String, value: String) {
    Row {
        Text(label, Modifier.width(48.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
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

/**
 * Wraps the message in a page sized for the phone. The table/div rules narrow fixed-width emails
 * (often 600-700px) to the screen so text stays readable; zooming out is only the fallback.
 */
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
img{max-width:100%;height:auto !important}
.plain{white-space:pre-wrap}
table,tbody,tr,td,th,div,p,img,center,section{max-width:100% !important}
table{width:auto !important;height:auto !important}
td,th{min-width:0 !important;height:auto !important}
</style></head><body>$content</body></html>"""
}

private val urlPattern = Regex("""https?://[^\s<>"]+""")

private fun plainTextToHtml(text: String): String {
    val escaped = TextUtils.htmlEncode(text)
    val linked = urlPattern.replace(escaped) { """<a href="${it.value}">${it.value}</a>""" }
    return """<div class="plain">$linked</div>"""
}

/** A coloured circle with the sender's first letter, like Gmail. */
@Composable
private fun Avatar(name: String, size: Dp = 40.dp) {
    val letter = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '?'
    val color = AVATAR_COLORS[(name.lowercase().hashCode() and Int.MAX_VALUE) % AVATAR_COLORS.size]
    Box(Modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
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

// --- Compose ------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposeScreen(vm: MailViewModel, draft: Draft, snackbar: SnackbarHostState) {
    var to by rememberSaveable(draft) { mutableStateOf(draft.to) }
    var cc by rememberSaveable(draft) { mutableStateOf(draft.cc) }
    var bcc by rememberSaveable(draft) { mutableStateOf(draft.bcc) }
    var subject by rememberSaveable(draft) { mutableStateOf(draft.subject) }
    var body by rememberSaveable(draft) { mutableStateOf(draft.body) }
    var showCc by rememberSaveable(draft) { mutableStateOf(draft.cc.isNotEmpty() || draft.bcc.isNotEmpty()) }
    var attachments by rememberSaveable(draft) { mutableStateOf(ArrayList(draft.attachments)) }
    val context = LocalContext.current
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        attachments = ArrayList(attachments + uris.map(vm::describeAttachment))
    }
    val send = { vm.send(draft.copy(to = to, cc = cc, bcc = bcc, subject = subject, body = body, attachments = attachments)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { BackButton(vm::back) },
                actions = {
                    IconButton(onClick = { pickFiles.launch("*/*") }) {
                        Icon(painterResource(R.drawable.ic_attach), contentDescription = "Attach file")
                    }
                    IconButton(onClick = send, enabled = !vm.loading) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).imePadding()) {
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            FieldRow("From") {
                Text(vm.account?.email.orEmpty(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 4.dp))
            }
            FieldRow("To") {
                PlainField(to, { to = it }, "", Modifier.weight(1f), KeyboardType.Email)
                IconButton(onClick = { showCc = !showCc }) {
                    Icon(
                        if (showCc) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (showCc) "Hide Cc and Bcc" else "Add Cc and Bcc",
                    )
                }
            }
            if (showCc) {
                FieldRow("Cc") { PlainField(cc, { cc = it }, "", Modifier.weight(1f), KeyboardType.Email) }
                FieldRow("Bcc") { PlainField(bcc, { bcc = it }, "", Modifier.weight(1f), KeyboardType.Email) }
            }
            Box(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                PlainField(subject, { subject = it }, "Subject", Modifier.fillMaxWidth())
            }
            HorizontalDivider()
            attachments.forEach { a ->
                Row(
                    Modifier
                        .padding(start = 16.dp, end = 8.dp, top = 8.dp)
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_attach), contentDescription = null, Modifier.size(18.dp))
                    Text(
                        "  ${a.name} (${Formatter.formatShortFileSize(context, a.size)})",
                        Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = { attachments = ArrayList(attachments - a) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove ${a.name}")
                    }
                }
            }
            Box(Modifier.padding(16.dp)) {
                PlainField(body, { body = it }, "Compose email", Modifier.fillMaxWidth(), singleLine = false, minLines = 12)
            }
        }
    }
}

/** A label on the left and the field on the right, with a divider below, like Gmail's compose. */
@Composable
private fun FieldRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.width(56.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
    HorizontalDivider()
}

/** A text field without a box around it. */
@Composable
private fun PlainField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        singleLine = singleLine,
        minLines = minLines,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        decorationBox = { field ->
            Box {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                field()
            }
        },
    )
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
}
