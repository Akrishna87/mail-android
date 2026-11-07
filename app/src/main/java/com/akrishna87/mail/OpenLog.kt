package com.akrishna87.mail

import org.json.JSONArray
import org.json.JSONObject

/** Where opening an email spent its time on the mail connection, in milliseconds. */
class LoadTiming {
    var waitMs = 0L
    var connectMs = 0L
    var downloadMs = 0L
    var parseMs = 0L
}

/** One opened email, for Settings → Recent opens. Kept only on the phone. */
data class OpenRecord(
    val at: Long,
    val from: String,
    val subject: String,
    val sizeBytes: Int,
    val fromPhone: Boolean,
    val totalMs: Long,
    val waitMs: Long = 0,
    val connectMs: Long = 0,
    val downloadMs: Long = 0,
    val parseMs: Long = 0,
) {
    /** "Axis Bank Alerts · 1.8 s · downloaded" */
    fun headline(): String = "$from · ${seconds(totalMs)} · ${if (fromPhone) "saved on phone" else "downloaded"}"

    /** "waited 0.1 s · connected 1.2 s · downloaded 0.4 s · prepared 0.1 s · 120 KB", or null when it came from the phone. */
    fun breakdown(): String? {
        if (fromPhone) return null
        val steps = listOf("waited" to waitMs, "connected" to connectMs, "downloaded" to downloadMs, "prepared" to parseMs)
            .filter { it.second > 0 }
            .map { "${it.first} ${seconds(it.second)}" }
        val size = if (sizeBytes > 0) listOf(kilobytes(sizeBytes)) else emptyList()
        return (steps + size).joinToString(" · ")
    }

    fun toJson(): JSONObject = JSONObject()
        .put("at", at).put("from", from).put("subject", subject).put("size", sizeBytes).put("fromPhone", fromPhone)
        .put("total", totalMs).put("wait", waitMs).put("connect", connectMs).put("download", downloadMs).put("parse", parseMs)

    companion object {
        const val KEEP = 20

        fun listToJson(list: List<OpenRecord>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(json: String): List<OpenRecord> = runCatching {
            val a = JSONArray(json)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                OpenRecord(
                    o.getLong("at"), o.optString("from"), o.optString("subject"), o.optInt("size"), o.optBoolean("fromPhone"),
                    o.optLong("total"), o.optLong("wait"), o.optLong("connect"), o.optLong("download"), o.optLong("parse"),
                )
            }
        }.getOrDefault(emptyList())

        fun seconds(ms: Long): String = "%.1f s".format(ms / 1000.0)

        fun kilobytes(bytes: Int): String =
            if (bytes >= 1024 * 1024) "%.1f MB".format(bytes / (1024.0 * 1024.0)) else "${(bytes + 1023) / 1024} KB"
    }
}
