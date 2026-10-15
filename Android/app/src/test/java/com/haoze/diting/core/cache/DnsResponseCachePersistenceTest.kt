package com.haoze.diting.core.cache

import androidx.sqlite.db.SupportSQLiteQuery
import com.haoze.diting.data.dao.DnsCacheDao
import com.haoze.diting.data.dao.DnsCacheHitDelta
import com.haoze.diting.data.entity.DnsCacheEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies the batched, counter-preserving DNS cache write path: hits are
 * coalesced into atomic deltas, and a metadata refresh must never clobber the
 * persisted hit count (the previous REPLACE-based write lost counts).
 */
class DnsResponseCachePersistenceTest {

    private class FakeDnsCacheDao : DnsCacheDao {
        val storage = LinkedHashMap<String, DnsCacheEntity>()
        var hitCount = 0

        override suspend fun get(key: String): DnsCacheEntity? = storage[key]

        override suspend fun getUnexpired(now: Long, limit: Int): List<DnsCacheEntity> =
            storage.values.filter { it.expiresAt > now }.take(limit)

        override suspend fun countUnexpired(now: Long): Int = storage.values.count { it.expiresAt > now }

        override suspend fun totalHitCount(): Int = hitCount

        override suspend fun insert(entity: DnsCacheEntity) {
            storage[entity.key] = entity
        }

        override suspend fun insertIfAbsent(entity: DnsCacheEntity) {
            storage.putIfAbsent(entity.key, entity)
        }

        override suspend fun updateMetadata(
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
        ): Int {
            val existing = storage[key] ?: return 0
            // Mirrors SQL: only payload columns change; hitCount/lastHitAt untouched.
            storage[key] = existing.copy(
                queryName = queryName,
                queryType = queryType,
                queryClass = queryClass,
                createdAt = createdAt,
                expiresAt = expiresAt,
                originalTtlSeconds = originalTtlSeconds,
                ttlOffsets = ttlOffsets,
                response = response,
                responseSize = responseSize
            )
            return 1
        }

        override suspend fun addHits(key: String, delta: Int, lastHitAt: Long): Int {
            val existing = storage[key] ?: return 0
            storage[key] = existing.copy(hitCount = existing.hitCount + delta, lastHitAt = lastHitAt)
            return 1
        }

        override suspend fun recordHit(key: String, now: Long) {
            hitCount++
            storage[key]?.let { storage[key] = it.copy(hitCount = it.hitCount + 1, lastHitAt = now) }
        }

        override suspend fun delete(key: String) {
            storage.remove(key)
        }

        override suspend fun deleteExpired(now: Long): Int {
            val expired = storage.values.filter { it.expiresAt <= now }.map { it.key }
            expired.forEach { storage.remove(it) }
            return expired.size
        }

        override suspend fun clearAll() {
            storage.clear()
        }

        override suspend fun queryList(query: SupportSQLiteQuery): List<DnsCacheEntity> = emptyList()

        override suspend fun count(query: SupportSQLiteQuery): Int = storage.size
    }

    private fun newCache(dao: FakeDnsCacheDao): DnsResponseCache =
        DnsResponseCache(dao, DnsCachePolicy(enabled = true), CoroutineScope(Job()))

    private val keyA = DnsCacheKey.create(name = "a.com", type = 1).storageKey

    @Test
    fun recordResolvedThenFlushCreatesRow() = runBlocking {
        val dao = FakeDnsCacheDao()
        val cache = newCache(dao)

        cache.recordResolved("a.com", 1, "1.2.3.4", upstreamTtl = 300)
        cache.flushPendingWrites()

        val row = dao.storage[keyA]
        assertNotNull(row)
        assertEquals(0, row!!.hitCount)
    }

    @Test
    fun hitsAreCoalescedIntoCount() = runBlocking {
        val dao = FakeDnsCacheDao()
        val cache = newCache(dao)

        cache.recordResolved("a.com", 1, "1.2.3.4", upstreamTtl = 300)
        repeat(5) { cache.recordCacheHit("a.com", 1) }
        cache.flushPendingWrites()

        assertEquals(5, dao.storage[keyA]!!.hitCount)
        // The old hot path wrote per hit; the new one must not touch the DAO until flush.
        assertEquals(0, dao.hitCount)
    }

    @Test
    fun metadataRefreshDoesNotClobberHitCount() = runBlocking {
        val dao = FakeDnsCacheDao()
        val cache = newCache(dao)

        cache.recordResolved("a.com", 1, "1.2.3.4", upstreamTtl = 300)
        repeat(3) { cache.recordCacheHit("a.com", 1) }
        cache.flushPendingWrites()
        assertEquals(3, dao.storage[keyA]!!.hitCount)

        // A later resolve refreshes the metadata payload for the same key.
        cache.recordResolved("a.com", 1, "5.6.7.8", upstreamTtl = 300)
        repeat(2) { cache.recordCacheHit("a.com", 1) }
        cache.flushPendingWrites()

        assertEquals(5, dao.storage[keyA]!!.hitCount)
    }

    @Test
    fun hitWithoutResolvedRowUpdatesNothing() = runBlocking {
        val dao = FakeDnsCacheDao()
        val cache = newCache(dao)

        cache.recordCacheHit("missing.com", 1)
        cache.flushPendingWrites()

        assertNull(dao.storage[DnsCacheKey.create(name = "missing.com", type = 1).storageKey])
    }
}
