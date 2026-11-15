package com.mypro.familytalk.core.protocol

/**
 * Drops envelopes that are too old, from the future, or already seen. The relay
 * (or anyone who records traffic) can therefore not replay an old "accept" or
 * "hangup" into a later call.
 */
class ReplayGuard(
    private val windowMs: Long = 10 * 60 * 1000L,
    private val skewMs: Long = 2 * 60 * 1000L,
    /** Per sender: more than a 25 MB file (~760 chunks) plus a week of chat. */
    private val capacity: Int = 8192,
) {
    /**
     * One bounded cache per sender ("bucket"), so nobody can push someone else's message
     * ids out of the cache and then replay them. Strangers share one small bucket.
     */
    private val buckets = HashMap<String, LinkedHashMap<String, Long>>()
    private fun bucket(name: String) = buckets.getOrPut(name) {
        val cap = if (name == STRANGERS) 512 else capacity
        object : LinkedHashMap<String, Long>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > cap
        }
    }

    /** Offline chat can arrive late, so queued text gets a longer window than call signalling. */
    @Synchronized
    fun accept(
        from: String, id: String, ts: Long, now: Long = System.currentTimeMillis(), window: Long = windowMs, futureSkew: Long = skewMs,
        bucketName: String = from,
    ): Boolean {
        if (ts > now + futureSkew) return false
        if (ts < now - window) return false
        val key = "$from/$id"
        val b = bucket(bucketName)
        if (b.containsKey(key)) return false
        b[key] = ts
        return true
    }

    /** Flat "sender/id" -> time (strangers' entries are not kept across restarts). */
    @Synchronized fun snapshot(): Map<String, Long> = buckets.filterKeys { it != STRANGERS }.values.fold(LinkedHashMap()) { acc, m -> acc.putAll(m); acc }
    @Synchronized fun restore(entries: Map<String, Long>) {
        entries.entries.sortedBy { it.value }.forEach { (k, v) -> bucket(k.substringBefore('/'))[k] = v }
    }

    companion object { const val STRANGERS = "~strangers" }
}
