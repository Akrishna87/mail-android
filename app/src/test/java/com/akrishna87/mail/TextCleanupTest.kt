package com.akrishna87.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextCleanupTest {
    @Test
    fun htmlInThePlainTextPartBecomesText() {
        // Seen on a phone: the nse_alerts preview showed "<html> <head></head> <body> <p>D…".
        val raw = "<html> <head><style>p{color:red}</style></head> <body> <p>Dear Investor,</p><p>Funds &amp; securities balance</p></body></html>"
        assertEquals("Dear Investor, Funds & securities balance", cleanPreview(raw))
    }

    @Test
    fun imagePlaceholderIsDropped() {
        // Seen on a phone: BookMyShow and Substack previews started with an "OBJ" box.
        assertEquals("Your booking is confirmed!", cleanPreview("￼ Your booking is confirmed!"))
    }

    @Test
    fun leftoverCssAndSeparatorsAreDropped() {
        // Seen on a phone: Tim Ferriss's preview showed "a {text-decoration: none;} ***************".
        assertEquals(
            "Hi all, here is 5-Bullet Friday",
            cleanPreview("a {text-decoration: none;} *************** Hi all, here is 5-Bullet Friday"),
        )
        assertEquals("Monthly update", cleanPreview("body { margin: 0; } ======== Monthly update -----"))
    }

    @Test
    fun bracesWithoutCssAreKept() {
        assertEquals("Hello {name}, welcome", cleanPreview("Hello {name}, welcome"))
    }

    @Test
    fun plainTextIsLeftAlone() {
        val text = "Lunch tomorrow? Use a < b and x > y freely"
        assertFalse(looksLikeHtml(text))
        assertEquals(text, cleanPreview(text))
    }

    @Test
    fun detectsCommonMarkup() {
        assertTrue(looksLikeHtml("<div class=\"x\">hi</div>"))
        assertTrue(looksLikeHtml("Hello<br>world"))
    }
}
