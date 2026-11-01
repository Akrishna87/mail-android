package com.akrishna87.mail

import java.util.Calendar
import java.util.Date
import javax.mail.Flags
import javax.mail.Message
import javax.mail.search.AndTerm
import javax.mail.search.BodyTerm
import javax.mail.search.ComparisonTerm
import javax.mail.search.FlagTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.HeaderTerm
import javax.mail.search.OrTerm
import javax.mail.search.ReceivedDateTerm
import javax.mail.search.RecipientStringTerm
import javax.mail.search.SearchTerm
import javax.mail.search.SubjectTerm

/**
 * Gmail-style search text for servers without Gmail's search (Yahoo, Outlook): the common
 * operators from:, to:, subject:, is:unread/read/starred, has:attachment, newer_than:/older_than:
 * (7d, 2m, 1y) and after:/before: (2026/10/31), plus plain words matched in sender, subject or text.
 */
object SearchQuery {
    /** Filter chips: label to search operator. Date chips replace each other. */
    val FILTERS = listOf(
        "Unread" to "is:unread",
        "Starred" to "is:starred",
        "Has attachment" to "has:attachment",
        "Last 7 days" to "newer_than:7d",
        "Last 30 days" to "newer_than:30d",
        "Last year" to "newer_than:1y",
    )

    private val token = Regex("""(\w+):("[^"]*"|\S+)|"[^"]*"|\S+""")

    fun has(query: String, operator: String): Boolean =
        token.findAll(query).any { it.value.equals(operator, ignoreCase = true) }

    /** Adds [operator] to the query, or removes it if present; date filters replace each other. */
    fun toggle(query: String, operator: String): String {
        val parts = token.findAll(query).map { it.value }.toMutableList()
        val present = parts.any { it.equals(operator, ignoreCase = true) }
        parts.removeAll { it.equals(operator, ignoreCase = true) }
        if (!present) {
            if (operator.startsWith("newer_than:")) parts.removeAll { it.startsWith("newer_than:", ignoreCase = true) }
            parts.add(operator)
        }
        return parts.joinToString(" ")
    }

    fun toSearchTerm(query: String): SearchTerm {
        val terms = token.findAll(query).mapNotNull { m ->
            val key = m.groupValues[1].lowercase()
            val value = m.groupValues[2].trim('"')
            when (key) {
                "from" -> FromStringTerm(value)
                "to" -> RecipientStringTerm(Message.RecipientType.TO, value)
                "subject" -> SubjectTerm(value)
                "is" -> when (value.lowercase()) {
                    "unread" -> FlagTerm(Flags(Flags.Flag.SEEN), false)
                    "read" -> FlagTerm(Flags(Flags.Flag.SEEN), true)
                    "starred" -> FlagTerm(Flags(Flags.Flag.FLAGGED), true)
                    else -> null
                }
                // Messages with attachments are almost always multipart/mixed.
                "has" -> if (value.equals("attachment", true)) HeaderTerm("Content-Type", "multipart/mixed") else null
                "newer_than" -> ago(value)?.let { ReceivedDateTerm(ComparisonTerm.GE, it) }
                "older_than" -> ago(value)?.let { ReceivedDateTerm(ComparisonTerm.LT, it) }
                "after" -> date(value)?.let { ReceivedDateTerm(ComparisonTerm.GE, it) }
                "before" -> date(value)?.let { ReceivedDateTerm(ComparisonTerm.LT, it) }
                else -> {
                    val word = m.value.trim('"')
                    OrTerm(arrayOf(FromStringTerm(word), SubjectTerm(word), BodyTerm(word)))
                }
            }
        }.toList()
        return when (terms.size) {
            0 -> OrTerm(arrayOf(SubjectTerm(query), BodyTerm(query)))
            1 -> terms[0]
            else -> AndTerm(terms.toTypedArray())
        }
    }

    /** "7d", "2m", "1y" -> that long ago. */
    private fun ago(value: String): Date? {
        val n = value.dropLast(1).toIntOrNull() ?: return null
        val field = when (value.last().lowercaseChar()) {
            'd' -> Calendar.DAY_OF_YEAR
            'm' -> Calendar.MONTH
            'y' -> Calendar.YEAR
            else -> return null
        }
        return Calendar.getInstance().apply { add(field, -n) }.time
    }

    /** "2026/10/31" or "2026-10-31". */
    private fun date(value: String): Date? {
        val p = value.split('/', '-').mapNotNull { it.toIntOrNull() }
        if (p.size != 3) return null
        return Calendar.getInstance().apply { clear(); set(p[0], p[1] - 1, p[2]) }.time
    }
}
