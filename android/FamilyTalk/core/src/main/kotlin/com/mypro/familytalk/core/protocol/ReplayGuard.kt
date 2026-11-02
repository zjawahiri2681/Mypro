package com.mypro.familytalk.core.protocol

/**
 * Drops envelopes that are too old, from the future, or already seen. The relay
 * (or anyone who records traffic) can therefore not replay an old "accept" or
 * "hangup" into a later call.
 */
class ReplayGuard(
    private val windowMs: Long = 10 * 60 * 1000L,
    private val skewMs: Long = 2 * 60 * 1000L,
    private val capacity: Int = 4096,
) {
    private val seen = object : LinkedHashMap<String, Long>(capacity, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > capacity
    }

    /** Offline chat can arrive late, so queued text gets a longer window than call signalling. */
    @Synchronized
    fun accept(from: String, id: String, ts: Long, now: Long = System.currentTimeMillis(), window: Long = windowMs, futureSkew: Long = skewMs): Boolean {
        if (ts > now + futureSkew) return false
        if (ts < now - window) return false
        val key = "$from/$id"
        if (seen.containsKey(key)) return false
        seen[key] = ts
        return true
    }

    @Synchronized fun snapshot(): Map<String, Long> = LinkedHashMap(seen)
    @Synchronized fun restore(entries: Map<String, Long>) { seen.putAll(entries) }
}
