package com.akrishna87.mail

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class SnoozeTimesTest {
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Calendar =
        Calendar.getInstance().apply { clear(); set(year, month, day, hour, minute) }

    private fun Calendar.describe(millis: Long): String {
        val c = (clone() as Calendar).apply { timeInMillis = millis }
        val days = arrayOf("", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        return "${days[c.get(Calendar.DAY_OF_WEEK)]} ${c.get(Calendar.DAY_OF_MONTH)} %02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    private fun options(now: Calendar) = SnoozeTimes.options(now).map { it.label to now.describe(it.at) }

    @Test
    fun weekdayMorningOffersAllFour() {
        val wed = at(2026, Calendar.NOVEMBER, 4, 10) // Wednesday 10:00
        assertEquals(
            listOf(
                "Later today" to "Wed 4 18:00",
                "Tomorrow" to "Thu 5 08:00",
                "This weekend" to "Sat 7 08:00",
                "Next week" to "Mon 9 08:00",
            ),
            options(wed),
        )
    }

    @Test
    fun eveningDropsLaterToday() {
        val wed = at(2026, Calendar.NOVEMBER, 4, 17, 30)
        assertEquals(listOf("Tomorrow", "This weekend", "Next week"), SnoozeTimes.options(wed).map { it.label })
    }

    @Test
    fun weekendHasNoThisWeekendAndNextWeekIsMonday() {
        val sat = at(2026, Calendar.NOVEMBER, 7, 9)
        assertEquals(
            listOf("Later today" to "Sat 7 18:00", "Tomorrow" to "Sun 8 08:00", "Next week" to "Mon 9 08:00"),
            options(sat),
        )
    }

    @Test
    fun mondayNextWeekIsAWeekLater() {
        val mon = at(2026, Calendar.NOVEMBER, 9, 20)
        assertEquals("Mon 16 08:00", mon.describe(SnoozeTimes.options(mon).first { it.label == "Next week" }.at))
    }
}
