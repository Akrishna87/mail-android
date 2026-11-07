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
            MailTheme(vm.appTheme) { MailApp(vm) }
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
