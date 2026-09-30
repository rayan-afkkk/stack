package com.swipegallery.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ReviewDao {
    // ---- Daily ledger ----
    @Query("SELECT EXISTS(SELECT 1 FROM daily_charges WHERE day = :day AND mediaKey = :key)")
    suspend fun isCharged(day: String, key: String): Boolean

    @Query("SELECT COUNT(*) FROM daily_charges WHERE day = :day")
    suspend fun chargedCount(day: String): Int

    @Query("SELECT COUNT(*) FROM daily_charges WHERE day = :day")
    fun observeChargedCount(day: String): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCharge(charge: ChargeEntity)

    /** Ledger rows are only needed for the current day; older ones are pruned. */
    @Query("DELETE FROM daily_charges WHERE day < :oldestDayToKeep")
    suspend fun pruneChargesBefore(oldestDayToKeep: String)

    // ---- Decisions ----
    @Query("SELECT * FROM decisions WHERE mediaKey = :key")
    suspend fun decision(key: String): DecisionEntity?

    @Upsert
    suspend fun upsertDecision(decision: DecisionEntity)

    @Query("DELETE FROM decisions WHERE mediaKey = :key")
    suspend fun deleteDecision(key: String)

    @Query("SELECT mediaKey FROM decisions")
    fun observeDecidedKeys(): Flow<List<String>>

    @Query("SELECT mediaKey FROM decisions")
    suspend fun decidedKeys(): List<String>

    @Query("DELETE FROM decisions WHERE state != 'QUEUED'")
    suspend fun deleteSettledDecisions()

    // ---- Deletion queue ----
    @Query("SELECT * FROM deletion_queue WHERE mediaKey = :key")
    suspend fun queueItem(key: String): QueueEntity?

    @Upsert
    suspend fun upsertQueueItem(item: QueueEntity)

    @Query("DELETE FROM deletion_queue WHERE mediaKey = :key")
    suspend fun deleteQueueItem(key: String)

    @Query("SELECT * FROM deletion_queue ORDER BY addedAt DESC")
    fun observeQueue(): Flow<List<QueueEntity>>

    @Query("SELECT * FROM deletion_queue ORDER BY addedAt DESC")
    suspend fun queue(): List<QueueEntity>

    @Query("SELECT COUNT(*) FROM deletion_queue")
    fun observeQueueCount(): Flow<Int>

    // ---- Session actions ----
    @Query("SELECT * FROM session_actions WHERE sessionId = :sessionId AND mediaKey = :key")
    suspend fun sessionAction(sessionId: Long, key: String): SessionActionEntity?

    @Query("SELECT * FROM session_actions WHERE sessionId = :sessionId ORDER BY seq DESC LIMIT 1")
    suspend fun lastSessionAction(sessionId: Long): SessionActionEntity?

    @Query("SELECT COALESCE(MAX(seq), 0) FROM session_actions WHERE sessionId = :sessionId")
    suspend fun maxSeq(sessionId: Long): Long

    @Upsert
    suspend fun upsertSessionAction(action: SessionActionEntity)

    @Query("DELETE FROM session_actions WHERE sessionId = :sessionId AND mediaKey = :key")
    suspend fun deleteSessionAction(sessionId: Long, key: String)

    @Query("SELECT mediaKey FROM session_actions WHERE sessionId = :sessionId")
    suspend fun actedKeys(sessionId: Long): List<String>

    @Query("SELECT decision, COUNT(*) AS count FROM session_actions WHERE sessionId = :sessionId GROUP BY decision")
    suspend fun decisionCounts(sessionId: Long): List<DecisionCount>

    @Query("SELECT COUNT(*) FROM session_actions WHERE sessionId = :sessionId")
    fun observeActionCount(sessionId: Long): Flow<Int>

    @Query("DELETE FROM session_actions")
    suspend fun deleteAllSessionActions()

    // ---- Sessions ----
    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun session(id: Long): SessionEntity?

    @Query("UPDATE sessions SET updatedAt = :at WHERE id = :id")
    suspend fun touchSession(id: Long, at: Long)

    @Query("UPDATE sessions SET completedAt = :at WHERE id = :id AND completedAt IS NULL")
    suspend fun completeSession(id: Long, at: Long)

    @Query("UPDATE sessions SET completedAt = NULL WHERE id = :id")
    suspend fun reopenSession(id: Long)

    @Query(
        """
        SELECT * FROM sessions
        WHERE completedAt IS NULL
          AND scopeType = :scopeType
          AND ((:scopeArg IS NULL AND scopeArg IS NULL) OR scopeArg = :scopeArg)
          AND sortOrder = :sortOrder
          AND includeReviewed = :includeReviewed
        ORDER BY updatedAt DESC
        LIMIT 1
        """,
    )
    suspend fun findOpenSession(
        scopeType: String,
        scopeArg: String?,
        sortOrder: String,
        includeReviewed: Boolean,
    ): SessionEntity?

    /** Most recently used unfinished session that has at least one decision. */
    @Query(
        """
        SELECT s.* FROM sessions s
        WHERE s.completedAt IS NULL
          AND EXISTS (SELECT 1 FROM session_actions a WHERE a.sessionId = s.id)
        ORDER BY s.updatedAt DESC
        LIMIT 1
        """,
    )
    fun observeResumableSession(): Flow<SessionEntity?>

    @Query("DELETE FROM sessions")
    suspend fun deleteAllSessions()
}
