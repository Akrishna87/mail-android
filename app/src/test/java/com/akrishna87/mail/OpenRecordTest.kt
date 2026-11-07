package com.akrishna87.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenRecordTest {
    @Test
    fun downloadedEmailShowsEachStep() {
        val r = OpenRecord(0, "Axis Bank Alerts", "Statement", 120 * 1024, false, 1800, 100, 1200, 400, 100)
        assertEquals("Axis Bank Alerts · 1.8 s · downloaded", r.headline())
        assertEquals("waited 0.1 s · connected 1.2 s · downloaded 0.4 s · prepared 0.1 s · 120 KB", r.breakdown())
    }

    @Test
    fun stepsThatTookNoTimeAreLeftOut() {
        // A kept-open connection costs nothing to reuse.
        val r = OpenRecord(0, "Ravi", "Lunch", 3 * 1024 * 1024, false, 600, 0, 0, 500, 100)
        assertEquals("downloaded 0.5 s · prepared 0.1 s · 3.0 MB", r.breakdown())
    }

    @Test
    fun savedOnPhoneHasNoBreakdown() {
        val r = OpenRecord(0, "Swiggy", "Order", 2048, true, 50)
        assertEquals("Swiggy · 0.1 s · saved on phone", r.headline())
        assertNull(r.breakdown())
    }
}
