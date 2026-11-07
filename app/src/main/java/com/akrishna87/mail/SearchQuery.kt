package com.akrishna87.mail

/** Gmail search text helpers for the filter chips; the query itself goes to Gmail unchanged. */
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
}
