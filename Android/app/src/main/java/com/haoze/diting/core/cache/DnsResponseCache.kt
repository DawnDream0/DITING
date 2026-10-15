package com.haoze.diting.core.cache

import com.haoze.diting.data.dao.DnsCacheDao
import com.haoze.diting.data.dao.DnsCacheHitDelta
import com.haoze.diting.data.entity.DnsCacheEntity
import com.haoze.diting.core.dns.DnsMessageUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.ceil
import kotlin.math.max

data class DnsCacheResult(
    val response: ByteArray,
    val cached: Boolean,
    val stale: Boolean = false
)

class DnsResponseCache(
    private val dao: DnsCacheDao,
    initialPolicy: DnsCachePolicy,
    private val scope: CoroutineScope,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    shardCount: Int = DEFAULT_SHARD_COUNT
) {
    @Volatile
    private var policy: DnsCachePolicy = initialPolicy

    private val shards = List(shardCount.coerceAtLeast(1)) {
        CacheShard(max(1, ceil(maxEntries.toDouble() / shardCount.coerceAtLeast(1)).toInt()))
    }
    private val inFlightMutex = Mutex()
    private val inFlight = HashMap<String, Deferred<ByteArray>>()
    private val pendingWriteMutex = Mutex()
    private val pendingWrites = LinkedHashMap<String, DnsCacheEntity>()
    private val pendingHits = LinkedHashMap<String, DnsCacheHitDelta>()
    private var pendingWriteFlushJob: Job? = null

    fun updatePolicy(policy: DnsCachePolicy) {
        this.policy = policy
    }

    suspend fun resolve(
        question: DnsMessageUtils.DnsQuestion,
        requestQuery: ByteArray,
        resolver: suspend () -> ByteArray
    ): DnsCacheResult {
        val currentPolicy = policy
        if (!currentPolicy.enabled) {
            return DnsCacheResult(resolver(), cached = false)
        }

        get(question, requestQuery, allowStale = false)?.let {
            return DnsCacheResult(it, cached = true)
        }

        val cacheKey = DnsCacheKey.fromQuestion(question)
        return coroutineScope {
            var leader = false
            val deferred = inFlightMutex.withLock {
                inFlight[cacheKey.storageKey] ?: async {
                    resolver()
                }.also {
                    inFlight[cacheKey.storageKey] = it
                    leader = true
                }
            }

            try {
                val resolved = deferred.await()
                if (leader && DnsMessageUtils.isSuccessResponse(resolved)) {
                    put(cacheKey, resolved)
                }
                DnsCacheResult(
                    response = DnsMessageUtils.withTransactionId(resolved, requestQuery),
                    cached = false
                )
            } catch (e: Exception) {
                val stale = if (currentPolicy.staleFallbackEnabled) {
                    get(question, requestQuery, allowStale = true)
                } else {
                    null
                }
                if (stale != null) {
                    DnsCacheResult(stale, cached = false, stale = true)
                } else {
                    throw e
                }
            } finally {
                if (leader) {
                    inFlightMutex.withLock {
                        if (inFlight[cacheKey.storageKey] === deferred) {
                            inFlight.remove(cacheKey.storageKey)
                        }
                    }
                }
            }
        }
    }

    suspend fun warmUp() {
        val now = System.currentTimeMillis()
        val rows = dao.getUnexpired(now, maxEntries)
        shards.forEach { it.clear() }
        rows.forEach { entity ->
            val entry = entity.toEntry(policy)
            shardFor(entity.key).put(entity.key, entry)
        }
        dao.deleteExpired(now)
    }

    suspend fun clear() {
        clearMemory()
        clearPendingWrites()
        dao.clearAll()
    }

    suspend fun recordCacheHit(
        queryName: String,
        queryType: Int,
        qclass: Int = 1,
        now: Long = System.currentTimeMillis()
    ) {
        val normalized = queryName.trim().trimEnd('.').lowercase()
        val key = DnsCacheKey.create(name = normalized, type = queryType, qclass = qclass).storageKey
        recordHit(key, now)
    }

    suspend fun recordResolved(
        queryName: String,
        queryType: Int,
        resolvedIPs: String,
        qclass: Int = 1,
        now: Long = System.currentTimeMillis(),
        upstreamTtl: Long = 0L
    ) {
        val currentPolicy = policy
        if (!currentPolicy.enabled || resolvedIPs.isBlank()) return
        val normalized = queryName.trim().trimEnd('.').lowercase()
        val key = DnsCacheKey.create(name = normalized, type = queryType, qclass = qclass).storageKey
        val ipList = resolvedIPs.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (ipList.isEmpty()) return

        val rawTtl = if (upstreamTtl > 0L) upstreamTtl else currentPolicy.maxTtlSeconds.coerceAtLeast(300L)
        val effectiveTtl = currentPolicy.effectiveTtlSeconds(rawTtl)
        if (effectiveTtl <= 0L) return


        val existingEntry = shardFor(key).get(key)
        val currentHitCount = existingEntry?.currentHitCount ?: 0
        val currentLastHitAt = existingEntry?.currentLastHitAt

        val estimatedSize = 32 + ipList.size * (if (queryType == 28) 16 else 4)
        val entity = DnsCacheEntity(
            key = key,
            queryName = normalized,
            queryType = queryType,
            queryClass = qclass,
            createdAt = now,
            expiresAt = now + effectiveTtl * 1000L,
            lastHitAt = currentLastHitAt,
            hitCount = currentHitCount,
            originalTtlSeconds = rawTtl,
            ttlOffsets = "",
            response = EMPTY_RESPONSE,
            responseSize = estimatedSize
        )
        val entry = DnsCacheEntry(
            entity = entity,
            ttlOffsets = EMPTY_INT_ARRAY,
            staleExpiresAt = staleExpiresAt(entity.expiresAt, currentPolicy)
        )
        shardFor(key).put(key, entry)
        enqueueWrite(entity)
    }

    suspend fun clearMemory() {
        shards.forEach { it.clear() }
    }

    private suspend fun get(
        question: DnsMessageUtils.DnsQuestion,
        requestQuery: ByteArray,
        allowStale: Boolean
    ): ByteArray? {
        val cacheKey = DnsCacheKey.fromQuestion(question)
        val now = System.currentTimeMillis()
        val shard = shardFor(cacheKey.storageKey)

        val memoryEntry = shard.get(cacheKey.storageKey)
        if (memoryEntry != null) {
            return materialize(memoryEntry, requestQuery, now, allowStale) ?: run {
                if (!allowStale && shouldDeleteUnavailable(memoryEntry, now)) {
                    removeExpired(cacheKey.storageKey, shard)
                }
                null
            }
        }

        val entity = dao.get(cacheKey.storageKey) ?: return null
        val entry = entity.toEntry(policy)
        val response = materialize(entry, requestQuery, now, allowStale)
        if (response == null) {
            if (!allowStale && shouldDeleteUnavailable(entry, now)) {
                removeExpired(cacheKey.storageKey, shard)
            }
            return null
        }
        shard.put(cacheKey.storageKey, entry)
        return response
    }

    private suspend fun put(cacheKey: DnsCacheKey, response: ByteArray): Boolean {
        val metadata = DnsMessageUtils.extractResponseTtlMetadata(response) ?: return false
        val upstreamTtl = metadata.minTtlSeconds
        val currentPolicy = policy
        val effectiveTtl = currentPolicy.effectiveTtlSeconds(upstreamTtl)
        if (effectiveTtl <= 0L) return false

        val existingEntry = shardFor(cacheKey.storageKey).get(cacheKey.storageKey)
        val currentHitCount = existingEntry?.currentHitCount ?: 0
        val currentLastHitAt = existingEntry?.currentLastHitAt

        val now = System.currentTimeMillis()
        val entity = DnsCacheEntity(
            key = cacheKey.storageKey,
            queryName = cacheKey.name,
            queryType = cacheKey.type,
            queryClass = cacheKey.qclass,
            createdAt = now,
            expiresAt = now + effectiveTtl * 1000L,
            lastHitAt = currentLastHitAt,
            hitCount = currentHitCount,
            originalTtlSeconds = upstreamTtl,
            ttlOffsets = metadata.ttlOffsets.joinToString(","),
            response = response.copyOf(),
            responseSize = response.size
        )
        val entry = DnsCacheEntry(
            entity = entity,
            ttlOffsets = metadata.ttlOffsets,
            staleExpiresAt = staleExpiresAt(entity.expiresAt, currentPolicy)
        )
        shardFor(cacheKey.storageKey).put(cacheKey.storageKey, entry)
        enqueueWrite(entity)
        return true
    }

    private suspend fun enqueueWrite(entity: DnsCacheEntity) {
        val flushNow = pendingWriteMutex.withLock {
            pendingWrites[entity.key] = entity
            markPendingLocked()
        }
        if (flushNow) flushPendingWrites()
    }

    /**
     * Coalesces a cache hit into [pendingHits] instead of writing the row on the
     * hot path. Deltas are applied atomically at flush time, so they can never be
     * lost to (or clobber) the metadata write for the same key.
     */
    private suspend fun enqueueHit(key: String, now: Long) {
        val flushNow = pendingWriteMutex.withLock {
            val existing = pendingHits[key]
            pendingHits[key] = if (existing == null) {
                DnsCacheHitDelta(key, 1, now)
            } else {
                DnsCacheHitDelta(key, existing.delta + 1, max(existing.lastHitAt, now))
            }
            markPendingLocked()
        }
        if (flushNow) flushPendingWrites()
    }

    /**
     * Schedules a trailing flush once enough items are pending, or cancels it and
     * reports that the caller should flush immediately. Must hold [pendingWriteMutex].
     */
    private fun markPendingLocked(): Boolean {
        if (pendingWrites.size + pendingHits.size >= WRITE_BATCH_SIZE) {
            pendingWriteFlushJob?.cancel()
            pendingWriteFlushJob = null
            return true
        }
        if (pendingWriteFlushJob?.isActive != true) {
            pendingWriteFlushJob = scope.launch {
                delay(WRITE_FLUSH_DELAY_MS)
                flushPendingWrites()
            }
        }
        return false
    }

    suspend fun flushPendingWrites() {
        val (writes, hits) = pendingWriteMutex.withLock {
            pendingWriteFlushJob = null
            val w = pendingWrites.values.toList().also { pendingWrites.clear() }
            val h = pendingHits.values.toList().also { pendingHits.clear() }
            w to h
        }
        if (writes.isEmpty() && hits.isEmpty()) return
        runCatching { dao.applyPendingWrites(writes, hits) }.onFailure {
            pendingWriteMutex.withLock {
                writes.forEach { entity -> pendingWrites.putIfAbsent(entity.key, entity) }
                hits.forEach { hit ->
                    val existing = pendingHits[hit.key]
                    pendingHits[hit.key] = if (existing == null) {
                        hit
                    } else {
                        DnsCacheHitDelta(hit.key, existing.delta + hit.delta, max(existing.lastHitAt, hit.lastHitAt))
                    }
                }
                if (pendingWriteFlushJob?.isActive != true) {
                    pendingWriteFlushJob = scope.launch {
                        delay(WRITE_FLUSH_DELAY_MS)
                        flushPendingWrites()
                    }
                }
            }
        }
    }

    private suspend fun clearPendingWrites() {
        pendingWriteMutex.withLock {
            pendingWriteFlushJob?.cancel()
            pendingWriteFlushJob = null
            pendingWrites.clear()
            pendingHits.clear()
        }
    }

    private suspend fun materialize(
        entry: DnsCacheEntry,
        requestQuery: ByteArray,
        now: Long,
        allowStale: Boolean
    ): ByteArray? {
        val entity = entry.entity
        val expiresAt = entity.expiresAt
        val usable = if (allowStale) {
            now <= staleExpiresAt(expiresAt, policy)
        } else {
            now < expiresAt
        }
        if (!usable) return null

        val remainingSeconds = if (now < expiresAt) {
            ((expiresAt - now + 999L) / 1000L).coerceAtLeast(1L)
        } else {
            1L
        }
        val ttlPatched = DnsMessageUtils.patchResponseTtl(
            response = entity.response,
            ttlOffsets = entry.ttlOffsets,
            remainingTtlSeconds = remainingSeconds
        ) ?: return null
        if (!allowStale) {
            recordHit(entity.key, now)
        }
        return DnsMessageUtils.withTransactionId(ttlPatched, requestQuery)
    }

    private suspend fun recordHit(key: String, now: Long) {
        // Keep the in-memory LRU ordering warm, then coalesce the DB increment
        // into the pending batch. No synchronous DB write on the hot path.
        shardFor(key).recordHit(key, now)
        enqueueHit(key, now)
    }


    private fun shouldDeleteUnavailable(entry: DnsCacheEntry, now: Long): Boolean {
        val currentPolicy = policy
        if (!currentPolicy.staleFallbackEnabled) return true
        return now > staleExpiresAt(entry.entity.expiresAt, currentPolicy)
    }

    private suspend fun removeExpired(key: String, shard: CacheShard) {
        shard.remove(key)
        // Drop any queued write/hit for this key too, or the next flush would
        // resurrect the row we are deleting now.
        pendingWriteMutex.withLock {
            pendingWrites.remove(key)
            pendingHits.remove(key)
        }
        dao.delete(key)
    }

    private fun shardFor(key: String): CacheShard {
        val index = (key.hashCode() and Int.MAX_VALUE) % shards.size
        return shards[index]
    }

    private fun DnsCacheEntity.toEntry(policy: DnsCachePolicy): DnsCacheEntry {
        return DnsCacheEntry(
            entity = this,
            ttlOffsets = ttlOffsets.split(',')
                .mapNotNull { it.toIntOrNull() }
                .toIntArray(),
            staleExpiresAt = staleExpiresAt(expiresAt, policy)
        )
    }

    private fun staleExpiresAt(expiresAt: Long, policy: DnsCachePolicy): Long {
        return if (policy.staleFallbackEnabled) {
            expiresAt + policy.staleFallbackSeconds.coerceAtLeast(0L) * 1000L
        } else {
            expiresAt
        }
    }

    private class CacheShard(private val maxSize: Int) {
        private val mutex = Mutex()
        private val memory = object : LinkedHashMap<String, DnsCacheEntry>(maxSize, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DnsCacheEntry>?): Boolean {
                return size > maxSize
            }
        }

        suspend fun get(key: String): DnsCacheEntry? = mutex.withLock { memory[key] }

        suspend fun put(key: String, entry: DnsCacheEntry) {
            mutex.withLock {
                val current = memory[key]
                if (current != null && (current.currentHitCount > 0 || current.currentLastHitAt != null)) {
                    entry.currentHitCount = current.currentHitCount
                    entry.currentLastHitAt = current.currentLastHitAt
                }
                memory[key] = entry
            }
        }

        suspend fun remove(key: String) {
            mutex.withLock { memory.remove(key) }
        }

        suspend fun clear() {
            mutex.withLock { memory.clear() }
        }

        suspend fun recordHit(key: String, now: Long) {
            mutex.withLock {
                val current = memory[key] ?: return@withLock
                current.currentHitCount++
                current.currentLastHitAt = now
            }
        }
    }

    companion object {
        private val EMPTY_RESPONSE = ByteArray(0)
        private val EMPTY_INT_ARRAY = IntArray(0)
        private const val DEFAULT_MAX_ENTRIES = 2048
        private const val DEFAULT_SHARD_COUNT = 16
        private const val WRITE_FLUSH_DELAY_MS = 30_000L
        private const val WRITE_BATCH_SIZE = 200
    }
}
