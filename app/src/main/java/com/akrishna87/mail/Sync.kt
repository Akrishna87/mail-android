package com.akrishna87.mail

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** How often new mail is fetched in the background, like Thunderbird's sync setting. */
enum class SyncMode(val label: String, val detail: String, val minutes: Long) {
    PUSH("Instantly (push)", "Keeps a connection open, so mail arrives in seconds. Uses more battery and shows a small permanent notification.", 15),
    EVERY_15("Every 15 minutes", "Android may wait longer when the phone is saving battery.", 15),
    EVERY_30("Every 30 minutes", "", 30),
    EVERY_60("Every hour", "", 60),
    MANUAL("Only when I open the app", "No background checks and no new-mail notifications.", 0),
}

/** Lets an open app know the background sync saved new mail. */
object SyncEvents {
    val inboxChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
}

object MailSync {
    const val ACTION_OPEN_INBOX = "com.akrishna87.mail.OPEN_INBOX"
    const val EXTRA_UID = "uid"
    private const val CHANNEL_NEW_MAIL = "new_mail"
    private const val NEW_MAIL_NOTIFICATION = 1
    private const val WORK_NAME = "new-mail-check"
    private const val PREFETCH_NEW = 10
    private const val PREFETCH_MAX_BYTES = 512 * 1024

    /** Starts or stops the background work that the chosen [SyncMode] needs. */
    fun apply(context: Context) {
        val store = AccountStore(context)
        val mode = if (store.load() == null) SyncMode.MANUAL else store.syncMode
        val work = WorkManager.getInstance(context)
        if (mode == SyncMode.MANUAL) {
            work.cancelUniqueWork(WORK_NAME)
        } else {
            // With push this periodic check is a safety net in case the push connection stops.
            val request = PeriodicWorkRequestBuilder<NewMailWorker>(mode.minutes, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
        if (mode == SyncMode.PUSH) PushService.start(context) else PushService.stop(context)
    }

    /**
     * Brings the saved inbox up to date: the newest messages, their downloaded bodies, and a
     * notification for unread mail that arrived since the last sync.
     */
    suspend fun syncInbox(context: Context, store: AccountStore, mail: MailService, account: Account) {
        val cache = MailCache(context)
        val page = mail.messages(account, "INBOX", 1)
        val saved = withContext(Dispatchers.IO) { cache.loadList("INBOX").associateBy { it.uid } }
        var list = page.messages.map { it.copy(preview = saved[it.uid]?.preview) }

        // Download new mail now, so it opens instantly from the notification or the list.
        val missing = withContext(Dispatchers.IO) {
            list.take(PREFETCH_NEW).filter { it.size < PREFETCH_MAX_BYTES && !cache.has("INBOX", it.uid) }
        }
        if (missing.isNotEmpty()) {
            val details = runCatching { mail.prefetch(account, "INBOX", missing.map { it.uid }) }.getOrDefault(emptyMap())
            withContext(Dispatchers.IO) { details.values.forEach { cache.save("INBOX", it) } }
            list = list.map { m -> details[m.uid]?.let { m.copy(preview = it.preview) } ?: m }
        }
        list = withContext(Dispatchers.IO) {
            // Fill previews from messages downloaded earlier, then save the list for the app.
            list.map { m -> if (m.preview != null) m else cache.load("INBOX", m.uid)?.let { m.copy(preview = it.preview) } ?: m }
                .also { cache.saveList("INBOX", it) }
        }

        val last = store.lastUidNext
        val next = maxOf(last, (list.maxOfOrNull { it.uid } ?: 0L) + 1)
        if (last > 0) {
            val fresh = list.filter { it.uid >= last && !it.seen }
            if (fresh.isNotEmpty()) notifyNewMail(context, fresh)
        }
        store.lastUidNext = next
        store.lastCheckTime = System.currentTimeMillis()
        store.lastCheckError = ""
        SyncEvents.inboxChanged.tryEmit(Unit)
    }

    private fun notifyNewMail(context: Context, messages: List<MessageSummary>) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_NEW_MAIL, "New mail", NotificationManager.IMPORTANCE_DEFAULT),
        )
        // One new email opens that email; several open the inbox. Either way the inbox refreshes.
        val tap = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_INBOX)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (messages.size == 1) tap.putExtra(EXTRA_UID, messages[0].uid)
        val open = PendingIntent.getActivity(
            context,
            0,
            tap,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val (title, text) = if (messages.size == 1) {
            messages[0].from to messages[0].subject
        } else {
            "${messages.size} new emails" to messages.joinToString(", ") { it.from }
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_NEW_MAIL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NEW_MAIL_NOTIFICATION, notification)
    }
}
