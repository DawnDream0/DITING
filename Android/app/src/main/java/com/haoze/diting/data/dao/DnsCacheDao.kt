package com.haoze.diting.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import com.haoze.diting.data.entity.DnsCacheEntity

/** One coalesced hot-path hit batch item: how many hits and the latest hit time. */
data class DnsCacheHitDelta(
    val key: String,
    val delta: Int,
    val lastHitAt: Long
)

@Dao
interface DnsCacheDao {
    @Query("SELECT * FROM dns_cache WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): DnsCacheEntity?

    @Query("SELECT * FROM dns_cache WHERE expiresAt > :now ORDER BY lastHitAt DESC, createdAt DESC LIMIT :limit")
    suspend fun getUnexpired(now: Long, limit: Int): List<DnsCacheEntity>

    @Query("SELECT COUNT(*) FROM dns_cache WHERE expiresAt > :now")
    suspend fun countUnexpired(now: Long): Int

    @Query("SELECT COALESCE(SUM(hitCount), 0) FROM dns_cache")
    suspend fun totalHitCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DnsCacheEntity)

    /** Inserts only when the key is absent; used by the crate-on-first-write path. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: DnsCacheEntity)

    /**
     * Overwrites the response/TTL columns of an existing row and returns the
     * number of rows affected. It deliberately does NOT touch `hitCount` /
     * `lastHitAt`, so a batched response refresh can never clobber the hit
     * counters accumulated by [addHits]. On a brand-new key this affects 0 rows
     * and the caller falls back to [insertIfAbsent] (which seeds counters at 0).
     * (INSERT ... ON CONFLICT DO UPDATE would need SQLite 3.24 / API 30, but
     * minSdk is 24, so this portable form is used instead.)
     */
    @Query(
        "UPDATE dns_cache SET queryName = :queryName, queryType = :queryType, " +
            "queryClass = :queryClass, createdAt = :createdAt, expiresAt = :expiresAt, " +
            "originalTtlSeconds = :originalTtlSeconds, ttlOffsets = :ttlOffsets, " +
            "response = :response, responseSize = :responseSize " +
            "WHERE `key` = :key"
    )
    suspend fun updateMetadata(
        key: String,
        queryName: String,
        queryType: Int,
        queryClass: Int,
        createdAt: Long,
        expiresAt: Long,
        originalTtlSeconds: Long,
        ttlOffsets: String,
        response: ByteArray,
        responseSize: Int
    ): Int

    /** Atomically adds a coalesced hit count to an existing row. */
    @Query("UPDATE dns_cache SET hitCount = hitCount + :delta, lastHitAt = :lastHitAt WHERE `key` = :key")
    suspend fun addHits(key: String, delta: Int, lastHitAt: Long): Int

    @Query("UPDATE dns_cache SET hitCount = hitCount + 1, lastHitAt = :now WHERE `key` = :key")
    suspend fun recordHit(key: String, now: Long)

    /**
     * Applies one flush batch in a single transaction: metadata payloads first
     * (so brand-new keys exist before their hit deltas land), then the coalesced
     * hit deltas. All-or-nothing keeps the caller's re-queue-on-failure correct.
     */
    @Transaction
    suspend fun applyPendingWrites(entities: List<DnsCacheEntity>, hits: List<DnsCacheHitDelta>) {
        for (e in entities) {
            val updated = updateMetadata(
                key = e.key,
                queryName = e.queryName,
                queryType = e.queryType,
                queryClass = e.queryClass,
                createdAt = e.createdAt,
                expiresAt = e.expiresAt,
                originalTtlSeconds = e.originalTtlSeconds,
                ttlOffsets = e.ttlOffsets,
                response = e.response,
                responseSize = e.responseSize
            )
            if (updated == 0) {
                insertIfAbsent(e)
            }
        }
        for (h in hits) {
            addHits(h.key, h.delta, h.lastHitAt)
        }
    }

    @Query("DELETE FROM dns_cache WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM dns_cache WHERE expiresAt <= :now")
    suspend fun deleteExpired(now: Long): Int

    @Query("DELETE FROM dns_cache")
    suspend fun clearAll()

    @RawQuery
    suspend fun queryList(query: SupportSQLiteQuery): List<DnsCacheEntity>

    @RawQuery
    suspend fun count(query: SupportSQLiteQuery): Int
}
