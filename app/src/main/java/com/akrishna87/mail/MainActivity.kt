package com.akrishna87.mail

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    private val vm: MailViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            MailTheme { MailApp(vm) }
        }
        if (savedInstanceState == null) handleNotificationTap(intent)
        // Makes sure push is running if chosen; Android only allows starting it while the app is open.
        MailSync.apply(this)
        // The first WebView in a process takes a moment to start the browser engine; do it while
        // the app is idle, so the first email opens without that delay.
        Looper.myQueue().addIdleHandler {
            WebView(applicationContext).destroy()
            false
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationTap(intent)
    }

    private fun handleNotificationTap(intent: Intent?) {
        if (intent?.action != MailSync.ACTION_OPEN_INBOX) return
        val uid = intent.getLongExtra(MailSync.EXTRA_UID, 0L)
        vm.openFromNotification(uid.takeIf { it > 0 })
        setIntent(Intent()) // Handle each tap once, not again after rotation.
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    override fun onStop() {
        super.onStop()
        vm.commitPendingChange() // An archive or delete waiting on Undo goes through when leaving the app.
    }
}

/** Gmail's colours (light blue compose button and selection, white list, grey-blue search bar). */
private val GmailLight = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC2E7FF),
    onPrimaryContainer = Color(0xFF001D35),
    secondaryContainer = Color(0xFFD3E3FD),
    onSecondaryContainer = Color(0xFF041E49),
    background = Color.White,
    surface = Color.White,
    onSurface = Color(0xFF1F1F1F),
    onSurfaceVariant = Color(0xFF444746),
    surfaceContainerLow = Color(0xFFF8FAFD),
    surfaceContainer = Color(0xFFF6F8FC),
    surfaceContainerHigh = Color(0xFFEAF1FB),
    outline = Color(0xFF747775),
    outlineVariant = Color(0xFFC4C7C5),
)

private val GmailDark = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF004A77),
    onPrimaryContainer = Color(0xFFC2E7FF),
    secondaryContainer = Color(0xFF0842A0),
    onSecondaryContainer = Color(0xFFD3E3FD),
    background = Color(0xFF131314),
    surface = Color(0xFF131314),
    onSurface = Color(0xFFE3E3E3),
    onSurfaceVariant = Color(0xFFC4C7C5),
    surfaceContainerLow = Color(0xFF1B1B1B),
    surfaceContainer = Color(0xFF1E1F20),
    surfaceContainerHigh = Color(0xFF282A2C),
    outline = Color(0xFF8E918F),
    outlineVariant = Color(0xFF444746),
)

@Composable
private fun MailTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) GmailDark else GmailLight, content = content)
}
