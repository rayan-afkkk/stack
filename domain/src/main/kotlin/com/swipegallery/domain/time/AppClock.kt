package com.swipegallery.domain.time

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Injectable source of "now". The daily allowance is keyed by the user's *local*
 * calendar day, so both the instant and the zone are abstracted for tests.
 *
 * This is an offline app: the device clock is trusted. Changing the clock or clearing
 * app data can change the allowance, and the app does not claim otherwise.
 */
interface AppClock {
    fun nowMillis(): Long
    fun zone(): ZoneId
}

object SystemAppClock : AppClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

fun AppClock.today(): LocalDate = Instant.ofEpochMilli(nowMillis()).atZone(zone()).toLocalDate()

/** Start of the next local calendar day, in epoch millis (DST-safe). */
fun AppClock.nextResetMillis(): Long =
    today().plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli()

/**
 * Emits the current local day and re-emits whenever it changes, including when the
 * user changes the time zone or clock. Polls at most every [pollMillis].
 */
fun AppClock.dayFlow(pollMillis: Long = 30_000L): Flow<LocalDate> = flow {
    while (true) {
        emit(today())
        val untilMidnight = (nextResetMillis() - nowMillis()).coerceAtLeast(1_000L)
        delay(minOf(untilMidnight, pollMillis))
    }
}.distinctUntilChanged()

/** Stable string form used as the ledger's day column (ISO-8601, e.g. 2026-09-30). */
fun LocalDate.ledgerKey(): String = toString()
