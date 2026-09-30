package com.swipegallery.util

import android.content.Context
import android.text.format.Formatter
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

object Format {
    // Formatters are immutable and costly to build; cache them per locale.
    private var locale: Locale? = null
    private lateinit var medium: DateTimeFormatter
    private lateinit var long: DateTimeFormatter
    private lateinit var shortTime: DateTimeFormatter

    private fun ensure() {
        val current = Locale.getDefault()
        if (current != locale) {
            medium = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(current)
            long = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(current)
            shortTime = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(current)
            locale = current
        }
    }

    fun bytes(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    fun date(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        ensure()
        return medium.format(Instant.ofEpochMilli(millis).atZone(zone))
    }

    fun longDate(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        ensure()
        return long.format(Instant.ofEpochMilli(millis).atZone(zone))
    }

    fun time(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        ensure()
        return shortTime.format(Instant.ofEpochMilli(millis).atZone(zone))
    }

    fun month(yearMonth: YearMonth): String =
        DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()).format(yearMonth)

    fun shortMonth(yearMonth: YearMonth): String =
        DateTimeFormatter.ofPattern("LLL yyyy", Locale.getDefault()).format(yearMonth)

    fun count(n: Int): String = String.format(Locale.getDefault(), "%,d", n)
}
