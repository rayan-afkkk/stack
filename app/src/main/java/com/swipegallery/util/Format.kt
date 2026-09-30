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
    fun bytes(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    fun date(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
            .format(Instant.ofEpochMilli(millis).atZone(zone))

    fun longDate(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Locale.getDefault())
            .format(Instant.ofEpochMilli(millis).atZone(zone))

    fun time(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
            .format(Instant.ofEpochMilli(millis).atZone(zone))

    fun month(yearMonth: YearMonth): String =
        DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()).format(yearMonth)

    fun shortMonth(yearMonth: YearMonth): String =
        DateTimeFormatter.ofPattern("LLL yyyy", Locale.getDefault()).format(yearMonth)

    fun count(n: Int): String = String.format(Locale.getDefault(), "%,d", n)
}
