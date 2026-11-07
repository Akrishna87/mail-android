package com.akrishna87.mail

/** Some senders put HTML markup in the plain-text part of an email. */
fun looksLikeHtml(text: String): Boolean = HTML_TAG.containsMatchIn(text)

/**
 * One readable line for the message list: markup removed when the text is really HTML, the
 * placeholder Android puts where an image was (U+FFFC, shown as "OBJ") dropped, whitespace collapsed.
 */
fun cleanPreview(text: String): String {
    var s = text.replace(OBJECT_REPLACEMENT, "")
    if (looksLikeHtml(s)) {
        s = s.replace(Regex("(?is)<(style|script|head)\\b.*?</\\1>"), " ")
            .replace(Regex("<[^>]*>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
    }
    // Newsletters' plain-text parts sometimes start with leftover CSS ("a {text-decoration: none;}")
    // and lines of asterisks or dashes; neither belongs in a preview.
    s = s.replace(CSS_RULE, " ").replace(SEPARATOR, " ")
    return s.replace(Regex("\\s+"), " ").trim().take(160)
}

private const val OBJECT_REPLACEMENT = "\uFFFC"
private val CSS_RULE = Regex("""[\w\s.#:,>*\[\]="'-]{0,80}\{[^{}]*:[^{}]*\}""")
private val SEPARATOR = Regex("""([*=_~#-])\1{3,}""")
private val HTML_TAG = Regex("(?i)<(html|head|body|div|p|br|table|span|a)[\\s>/]")
