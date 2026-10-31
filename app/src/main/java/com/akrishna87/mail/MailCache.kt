package com.akrishna87.mail

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Date

/**
 * Keeps folders, message lists and downloaded messages on the phone, so the app opens
 * instantly and messages that were downloaded ahead of time open without the network.
 */
class MailCache(context: Context) {
    private val root = File(context.cacheDir, "mail")

    private fun folderDir(folder: String): File =
        File(root, Base64.encodeToString(folder.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))

    private fun read(file: File): String? = runCatching { file.readText() }.getOrNull()

    private fun write(file: File, text: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(file)
        }
    }

    fun clear() {
        root.deleteRecursively()
    }

    // --- Folders ------------------------------------------------------------------------------

    fun loadFolders(): List<MailFolder> = runCatching {
        val a = JSONArray(read(File(root, "folders.json")) ?: return emptyList())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            MailFolder(o.getString("fullName"), o.getString("name"), o.optString("role").ifEmpty { null }, o.optInt("unread"))
        }
    }.getOrDefault(emptyList())

    fun saveFolders(folders: List<MailFolder>) {
        val a = JSONArray()
        folders.forEach {
            a.put(JSONObject().put("fullName", it.fullName).put("name", it.name).put("role", it.role ?: "").put("unread", it.unread))
        }
        write(File(root, "folders.json"), a.toString())
    }

    // --- Message lists ------------------------------------------------------------------------

    fun loadList(folder: String): List<MessageSummary> = runCatching {
        val a = JSONArray(read(File(folderDir(folder), "list.json")) ?: return emptyList())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            MessageSummary(
                uid = o.getLong("uid"),
                from = o.getString("from"),
                subject = o.getString("subject"),
                date = o.optLong("date").takeIf { it > 0 }?.let(::Date),
                seen = o.getBoolean("seen"),
                size = o.optInt("size"),
                preview = o.optString("preview").ifEmpty { null },
            )
        }
    }.getOrDefault(emptyList())

    fun saveList(folder: String, messages: List<MessageSummary>) {
        val a = JSONArray()
        messages.forEach {
            a.put(
                JSONObject()
                    .put("uid", it.uid).put("from", it.from).put("subject", it.subject)
                    .put("date", it.date?.time ?: 0).put("seen", it.seen).put("size", it.size)
                    .put("preview", it.preview ?: ""),
            )
        }
        write(File(folderDir(folder), "list.json"), a.toString())
    }

    // --- Messages -----------------------------------------------------------------------------

    private fun messageFile(folder: String, uid: Long) = File(folderDir(folder), "$uid.json")

    fun has(folder: String, uid: Long): Boolean = messageFile(folder, uid).exists()

    fun load(folder: String, uid: Long): MessageDetail? = runCatching {
        val o = JSONObject(read(messageFile(folder, uid)) ?: return null)
        val atts = o.getJSONArray("attachments")
        MessageDetail(
            uid = uid,
            from = o.getString("from"),
            fromAddress = o.getString("fromAddress"),
            to = strings(o.getJSONArray("to")),
            cc = strings(o.getJSONArray("cc")),
            subject = o.getString("subject"),
            date = o.optLong("date").takeIf { it > 0 }?.let(::Date),
            html = if (o.isNull("html")) null else o.getString("html"),
            text = o.getString("text"),
            messageId = o.optString("messageId").ifEmpty { null },
            references = o.optString("references").ifEmpty { null },
            attachments = (0 until atts.length()).map { i ->
                val a = atts.getJSONObject(i)
                ReceivedAttachment(a.getInt("index"), a.getString("name"), a.getInt("size"), a.getString("mimeType"))
            },
        )
    }.getOrNull()

    fun save(folder: String, m: MessageDetail) {
        val atts = JSONArray()
        m.attachments.forEach {
            atts.put(JSONObject().put("index", it.index).put("name", it.name).put("size", it.size).put("mimeType", it.mimeType))
        }
        val o = JSONObject()
            .put("from", m.from).put("fromAddress", m.fromAddress)
            .put("to", JSONArray(m.to)).put("cc", JSONArray(m.cc))
            .put("subject", m.subject).put("date", m.date?.time ?: 0)
            .put("html", m.html ?: JSONObject.NULL).put("text", m.text)
            .put("messageId", m.messageId ?: "").put("references", m.references ?: "")
            .put("attachments", atts)
        write(messageFile(folder, m.uid), o.toString())
        prune()
    }

    /** Keeps the newest downloaded messages and deletes the rest. */
    private fun prune() {
        val files = root.walkTopDown()
            .filter { it.isFile && it.name != "list.json" && it.name != "folders.json" && it.extension == "json" }
            .sortedByDescending { it.lastModified() }
            .toList()
        files.drop(MAX_MESSAGES).forEach { it.delete() }
    }

    private fun strings(a: JSONArray): List<String> = (0 until a.length()).map { a.getString(it) }

    private companion object {
        const val MAX_MESSAGES = 400
    }
}
