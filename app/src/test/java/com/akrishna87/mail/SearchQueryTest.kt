package com.akrishna87.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryTest {
    @Test
    fun toggleAddsAndRemovesAFilter() {
        assertEquals("axis is:unread", SearchQuery.toggle("axis", "is:unread"))
        assertEquals("axis", SearchQuery.toggle("axis is:unread", "is:unread"))
    }

    @Test
    fun dateFiltersReplaceEachOther() {
        assertEquals("axis newer_than:30d", SearchQuery.toggle("axis newer_than:7d", "newer_than:30d"))
    }

    @Test
    fun quotedPhrasesStayTogether() {
        assertEquals("from:\"Axis Bank\" has:attachment", SearchQuery.toggle("from:\"Axis Bank\"", "has:attachment"))
    }

    @Test
    fun hasMatchesWholeOperatorsOnly() {
        assertTrue(SearchQuery.has("loan is:unread", "is:unread"))
        assertFalse(SearchQuery.has("loan is:unreadish", "is:unread"))
    }
}
