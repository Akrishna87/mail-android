package com.akrishna87.mail

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.mail.AuthenticationFailedException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Push mail: keeps an IMAP IDLE connection on the inbox, so the server tells the app the moment
 * mail arrives. Android requires a visible notification while it runs.
 */
class PushService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private lateinit var store: AccountStore
    private lateinit var mail: MailService

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = AccountStore(this)
        mail = MailService(this, store)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The "special use" type exists from Android 14; earlier versions need no type.
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, statusNotification(), type)
        if (store.load() == null || store.syncMode != SyncMode.PUSH) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop?.isActive != true) loop = scope.launch { listen() }
        return START_STICKY
    }

    private suspend fun listen() {
        var backoff = MIN_BACKOFF_MS
        while (scope.isActive) {
            val account = store.load() ?: break
            try {
                MailSync.syncInbox(this, store, mail, account)
                backoff = MIN_BACKOFF_MS
                // Returns when mail arrives or after IDLE_MS, which keeps the connection from being dropped.
                mail.waitForInboxChange(account, IDLE_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                store.lastCheckTime = System.currentTimeMillis()
                store.lastCheckError = if (e is AuthenticationFailedException) "sign-in failed" else e.message ?: e.javaClass.simpleName
                mail.close()
                // No network or the server refused: wait a little longer each time, up to 5 minutes.
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        // Closing the connection ends a wait in progress; it blocks, so do it off the main thread.
        Thread { mail.close() }.start()
        super.onDestroy()
    }

    private fun statusNotification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Push connection", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while Mailroom keeps a connection open for instant mail. You can hide it here."
            },
        )
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Waiting for new mail")
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "push"
        private const val NOTIFICATION_ID = 2
        private const val IDLE_MS = 9 * 60 * 1000L
        private const val MIN_BACKOFF_MS = 15 * 1000L
        private const val MAX_BACKOFF_MS = 5 * 60 * 1000L

        fun start(context: Context) {
            // Android only lets the app start this while it is on screen, after boot, or after an
            // update; the periodic check covers the other cases.
            runCatching { ContextCompat.startForegroundService(context, Intent(context, PushService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PushService::class.java))
        }
    }
}
