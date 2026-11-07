package com.akrishna87.mail

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** A snoozed message, found again by its Message-ID when it is due. */
data class Snooze(val messageId: String, val until: Long, val from: String, val subject: String) {
    fun toJson(): JSONObject =
        JSONObject().put("messageId", messageId).put("until", until).put("from", from).put("subject", subject)

    companion object {
        fun listToJson(list: List<Snooze>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(json: String): List<Snooze> = runCatching {
            val a = JSONArray(json)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Snooze(o.getString("messageId"), o.getLong("until"), o.optString("from"), o.optString("subject"))
            }
        }.getOrDefault(emptyList())
    }
}

/** Gmail's snooze choices: later today, tomorrow, this weekend, next week (8 AM, or 6 PM today). */
object SnoozeTimes {
    data class Option(val label: String, val at: Long)

    fun options(now: Calendar = Calendar.getInstance()): List<Option> {
        val options = mutableListOf<Option>()
        if (now.get(Calendar.HOUR_OF_DAY) < LATER_TODAY_HOUR - 1) {
            options += Option("Later today", at(now, 0, LATER_TODAY_HOUR))
        }
        options += Option("Tomorrow", at(now, 1, MORNING_HOUR))
        val day = now.get(Calendar.DAY_OF_WEEK)
        if (day != Calendar.SATURDAY && day != Calendar.SUNDAY) {
            options += Option("This weekend", at(now, daysUntil(day, Calendar.SATURDAY), MORNING_HOUR))
        }
        options += Option("Next week", at(now, daysUntil(day, Calendar.MONDAY), MORNING_HOUR))
        return options
    }

    /** Days from [from] to the next [to] weekday, 1..7 (never today). */
    private fun daysUntil(from: Int, to: Int): Int = ((to - from + 7) % 7).let { if (it == 0) 7 else it }

    private fun at(now: Calendar, plusDays: Int, hour: Int): Long = (now.clone() as Calendar).apply {
        add(Calendar.DAY_OF_YEAR, plusDays)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private const val MORNING_HOUR = 8
    private const val LATER_TODAY_HOUR = 18
}

/** Brings snoozed messages back to the inbox when they are due. */
object Snoozes {
    private const val WORK_NAME = "snooze-return"

    /** Moves due messages from Snoozed back to the inbox, unread, and notifies about them. */
    suspend fun returnDue(context: Context, store: AccountStore, mail: MailService, account: Account) {
        val now = System.currentTimeMillis()
        val due = store.snoozes.filter { it.until <= now }
        if (due.isEmpty()) return
        mail.unsnooze(account, due.map { it.messageId })
        store.snoozes = store.snoozes.filterNot { s -> due.any { it.messageId == s.messageId } }
        MailSync.notifyReturned(context, due)
        SyncEvents.inboxChanged.tryEmit(Unit)
    }

    /** Wakes [SnoozeWorker] when the next snooze is due. */
    fun schedule(context: Context) {
        val next = AccountStore(context).snoozes.minOfOrNull { it.until }
        val work = WorkManager.getInstance(context)
        if (next == null) {
            work.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = OneTimeWorkRequestBuilder<SnoozeWorker>()
            .setInitialDelay((next - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}

class SnoozeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = AccountStore(applicationContext)
        val account = store.load() ?: return Result.success()
        val mail = MailService(applicationContext)
        return try {
            Snoozes.returnDue(applicationContext, store, mail, account)
            Snoozes.schedule(applicationContext)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        } finally {
            mail.close()
        }
    }
}
