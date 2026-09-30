package com.swipegallery.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Durable decision for one photo version (see domain MediaKeys). */
@Entity(tableName = "decisions", indices = [Index("state")])
data class DecisionEntity(
    @PrimaryKey val mediaKey: String,
    val mediaId: Long,
    val volume: String,
    val sizeBytes: Long?,
    val dateMillis: Long?,
    val width: Int?,
    val height: Int?,
    /** ReviewState name. */
    val state: String,
    val decidedAt: Long,
)

/** Per-day ledger: one row per photo version charged against that local day's allowance. */
@Entity(tableName = "daily_charges", primaryKeys = ["day", "mediaKey"])
data class ChargeEntity(
    /** ISO local date, e.g. 2026-09-30. */
    val day: String,
    val mediaKey: String,
    val chargedAt: Long,
)

/** Pending deletion queue. Nothing here has been deleted. */
@Entity(tableName = "deletion_queue", indices = [Index("addedAt")])
data class QueueEntity(
    @PrimaryKey val mediaKey: String,
    val mediaId: Long,
    val volume: String,
    val sizeBytes: Long?,
    val dateMillis: Long?,
    val width: Int?,
    val height: Int?,
    val addedAt: Long,
)

@Entity(tableName = "sessions", indices = [Index("completedAt"), Index("updatedAt")])
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scopeType: String,
    val scopeArg: String?,
    /** Display label (album name); informational only. */
    val scopeLabel: String?,
    /** SessionOrder name. */
    val sortOrder: String,
    val includeReviewed: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
)

/** A session's undo stack and progress ("which photos this session has decided"). */
@Entity(
    tableName = "session_actions",
    primaryKeys = ["sessionId", "mediaKey"],
    indices = [Index(value = ["sessionId", "seq"])],
)
data class SessionActionEntity(
    val sessionId: Long,
    val mediaKey: String,
    val seq: Long,
    val mediaId: Long,
    val volume: String,
    val sizeBytes: Long?,
    val dateMillis: Long?,
    val width: Int?,
    val height: Int?,
    /** Decision name. */
    val decision: String,
    val previousState: String?,
    val previousDecidedAt: Long?,
    val previousQueuedAt: Long?,
    val at: Long,
)

data class DecisionCount(val decision: String, val count: Int)
