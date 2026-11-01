package com.akrishna87.mail

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import javax.mail.AuthenticationFailedException
import kotlin.coroutines.cancellation.CancellationException

/** Periodic background sync of the inbox (see [MailSync.apply] for how often it runs). */
class NewMailWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = AccountStore(applicationContext)
        val account = store.load() ?: return Result.success()
        val mail = MailService(applicationContext, store)
        return try {
            MailSync.syncInbox(applicationContext, store, mail, account)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthenticationFailedException) {
            store.lastCheckTime = System.currentTimeMillis()
            store.lastCheckError = "sign-in failed"
            Result.success() // Retrying won't help until the user signs in again.
        } catch (e: Exception) {
            store.lastCheckTime = System.currentTimeMillis()
            store.lastCheckError = e.message ?: e.javaClass.simpleName
            Result.retry()
        } finally {
            mail.close()
        }
    }
}
