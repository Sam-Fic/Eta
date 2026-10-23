package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 日程工具的纯函数格式助手：解析、RRULE、本地/UTC 换算与转义。 */
class CalendarEventFormatsTest {

    @Test
    fun parsesDateTimeWithOptionalSecondsAndSpaceSeparator() {
        val timed = CalendarEventFormats.parseScheduleTime("2026-10-24T15:30")
        requireNotNull(timed)
        assertFalse(timed.dateOnly)
        assertEquals(2026, timed.year)
        assertEquals(10, timed.month)
        assertEquals(24, timed.day)
        assertEquals(15, timed.hour)
        assertEquals(30, timed.minute)

        val withSeconds = CalendarEventFormats.parseScheduleTime("2026-10-24 15:30:45")
        requireNotNull(withSeconds)
        assertEquals(45, withSeconds.second)

        val dateOnly = CalendarEventFormats.parseScheduleTime("2026-10-24")
        requireNotNull(dateOnly)
        assertTrue(dateOnly.dateOnly)
        assertNull(dateOnly.hour)
    }

    @Test
    fun rejectsMalformedOrImpossibleTimes() {
        assertNull(CalendarEventFormats.parseScheduleTime(""))
        assertNull(CalendarEventFormats.parseScheduleTime("明天下午三点"))
        assertNull(CalendarEventFormats.parseScheduleTime("2026-13-01T10:00"))
        assertNull(CalendarEventFormats.parseScheduleTime("2026-02-30"))
        assertNull(CalendarEventFormats.parseScheduleTime("2026-10-24T25:00"))
        assertNull(CalendarEventFormats.parseScheduleTime("2026-10-24T15:99"))
        // 2028 是闰年，2 月 29 日合法。
        requireNotNull(CalendarEventFormats.parseScheduleTime("2028-02-29"))
    }

    @Test
    fun buildsWeeklyRruleWithOrderedByDay() {
        assertEquals(
            "FREQ=WEEKLY;BYDAY=MO,WE,FR",
            CalendarEventFormats.buildRrule("weekly", listOf("fri", "wed", "mon", "wed")),
        )
        assertEquals("FREQ=WEEKLY", CalendarEventFormats.buildRrule("weekly", null))
        assertEquals("FREQ=DAILY", CalendarEventFormats.buildRrule("daily", listOf("mon")))
        assertEquals("FREQ=MONTHLY", CalendarEventFormats.buildRrule("monthly", null))
        assertEquals("FREQ=YEARLY", CalendarEventFormats.buildRrule("yearly", null))
        assertNull(CalendarEventFormats.buildRrule("none", null))
        assertNull(CalendarEventFormats.buildRrule(null, listOf("mon")))
        assertNull(CalendarEventFormats.buildRrule("", null))
    }

    @Test
    fun repeatLabelsCoverAllFrequencies() {
        assertEquals("不重复", CalendarEventFormats.repeatLabel(null))
        assertEquals("每天", CalendarEventFormats.repeatLabel("FREQ=DAILY"))
        assertEquals("每周", CalendarEventFormats.repeatLabel("FREQ=WEEKLY"))
        assertEquals("每周一周五", CalendarEventFormats.repeatLabel("FREQ=WEEKLY;BYDAY=MO,FR"))
        assertEquals("每月", CalendarEventFormats.repeatLabel("FREQ=MONTHLY"))
        assertEquals("每年", CalendarEventFormats.repeatLabel("FREQ=YEARLY"))
    }

    @Test
    fun localAndUtcMidnightRoundTrip() {
        val time = CalendarEventFormats.parseScheduleTime("2026-10-24T15:30")
        requireNotNull(time)
        val local = CalendarEventFormats.localMillis(time)
        // 本地 15:30 回读必须还是 15:30。
        assertEquals("2026-10-24T15:30", CalendarEventFormats.formatSchedule(local, allDay = false))

        val midnight = CalendarEventFormats.utcMidnightMillis(time)
        // 全天日程的毫秒按 UTC 零点回读为日期。
        assertEquals("2026-10-24", CalendarEventFormats.formatSchedule(midnight, allDay = true))

        // timed -> 全天：保留同一天的日期。
        val asAllDay = CalendarEventFormats.utcMidnightFromMillis(local, wasAllDay = false)
        assertEquals("2026-10-24", CalendarEventFormats.formatSchedule(asAllDay, allDay = true))
        // 全天 -> timed：回到当天本地零点。
        val asTimed = CalendarEventFormats.localMidnightFromMillis(asAllDay, wasAllDay = true)
        assertEquals("2026-10-24T00:00", CalendarEventFormats.formatSchedule(asTimed, allDay = false))
    }

    @Test
    fun escapesLikeAndSqlSpecials() {
        assertEquals("100\\%", CalendarEventFormats.escapeLike("100%"))
        assertEquals("a\\_b", CalendarEventFormats.escapeLike("a_b"))
        assertEquals("a\\\\b", CalendarEventFormats.escapeLike("a\\b"))
        assertEquals("it''s", CalendarEventFormats.escapeSqlString("it's"))
    }

    @Test
    fun summaryMentionsReminderAndRepeat() {
        val start = CalendarEventFormats.parseScheduleTime("2026-10-24T15:30")
        requireNotNull(start)
        val startMillis = CalendarEventFormats.localMillis(start)
        val summary = CalendarEventFormats.summary(
            prefix = "已创建",
            title = "团队周会",
            startMillis = startMillis,
            endMillis = startMillis + 60 * 60 * 1000L,
            allDay = false,
            reminderMinutes = 10,
            rrule = "FREQ=WEEKLY;BYDAY=MO",
        )
        assertTrue(summary.contains("团队周会"))
        assertTrue(summary.contains("2026-10-24T15:30"))
        assertTrue(summary.contains("提前 10 分钟提醒"))
        assertTrue(summary.contains("每周一"))
    }
}
