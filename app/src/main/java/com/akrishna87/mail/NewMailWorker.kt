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
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import javax.mail.AuthenticationFailedException
import kotlin.coroutines.cancellation.CancellationException

/** Checks the inbox every 15 minutes and posts a notification for new unread mail. */
class NewMailWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = AccountStore(applicationContext)
        val account = store.load() ?: return Result.success()
        val mail = MailService(applicationContext, store)
        store.lastCheckTime = System.currentTimeMillis()
        return try {
            val (next, fresh) = mail.newMail(account, store.lastUidNext)
            store.lastUidNext = next
            store.lastCheckError = ""
            if (fresh.isNotEmpty()) notify(fresh)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthenticationFailedException) {
            store.lastCheckError = "sign-in failed"
            Result.success() // Retrying won't help until the user signs in again.
        } catch (e: Exception) {
            store.lastCheckError = e.message ?: e.javaClass.simpleName
            Result.retry()
        } finally {
            mail.close()
        }
    }

    private fun notify(messages: List<MessageSummary>) {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "New mail", NotificationManager.IMPORTANCE_DEFAULT),
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
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "new_mail"
        private const val NOTIFICATION_ID = 1
        private const val WORK_NAME = "new-mail-check"
        const val ACTION_OPEN_INBOX = "com.akrishna87.mail.OPEN_INBOX"
        const val EXTRA_UID = "uid"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<NewMailWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
