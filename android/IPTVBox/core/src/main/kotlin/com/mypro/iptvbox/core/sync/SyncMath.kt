package com.mypro.iptvbox.core.sync

import kotlin.math.abs

/**
 * Pure logic for keeping a follower's player aligned with the host's state.
 * Kept free of Android so it can be unit tested.
 */
object SyncMath {
    /** Drift above this: hard seek. */
    const val SEEK_THRESHOLD_MS = 2000L
    /** Drift above this (but below seek): nudge the playback speed. */
    const val NUDGE_THRESHOLD_MS = 250L
    const val NUDGE_RATE = 0.06f

    sealed class Correction {
        object None : Correction()
        data class Seek(val toMs: Long) : Correction()
        data class Rate(val rate: Float) : Correction()
    }

    /** Where the host's playhead should be *now*, given its last STATE message and our clock offset. */
    fun expectedPosition(state: SyncMessage, nowLocalMs: Long, clockOffsetMs: Long): Long {
        if (!state.playing) return state.posMs
        val hostNow = nowLocalMs + clockOffsetMs
        val elapsed = (hostNow - state.ts).coerceAtLeast(0)
        return state.posMs + (elapsed * state.rate).toLong()
    }

    /**
     * @param localPosMs follower's current position
     * @param targetPosMs host's expected position
     * @param hostRate host's playback speed
     */
    fun correction(localPosMs: Long, targetPosMs: Long, hostRate: Float = 1f, hostPlaying: Boolean = true): Correction {
        if (!hostPlaying) {
            return if (abs(localPosMs - targetPosMs) > NUDGE_THRESHOLD_MS) Correction.Seek(targetPosMs) else Correction.None
        }
        val drift = targetPosMs - localPosMs // positive => we are behind
        return when {
            abs(drift) > SEEK_THRESHOLD_MS -> Correction.Seek(targetPosMs)
            abs(drift) > NUDGE_THRESHOLD_MS -> Correction.Rate(if (drift > 0) hostRate + NUDGE_RATE else hostRate - NUDGE_RATE)
            else -> Correction.Rate(hostRate)
        }
    }

    /** NTP-style offset estimate: (t1 - t0) - rtt/2, where t2 is when PONG arrived locally. */
    fun clockOffset(t0: Long, t1: Long, t2: Long): Long {
        val rtt = (t2 - t0).coerceAtLeast(0)
        return (t1 - t0) - rtt / 2
    }
}

/** Keeps a smoothed estimate of host clock minus local clock. */
class ClockOffsetEstimator {
    private val samples = ArrayDeque<Pair<Long, Long>>() // (offset, rtt)
    @Volatile var offsetMs: Long = 0L
        private set
    @Volatile var rttMs: Long = 0L
        private set

    fun addSample(t0: Long, t1: Long, t2: Long) {
        val rtt = (t2 - t0).coerceAtLeast(0)
        samples.addLast(SyncMath.clockOffset(t0, t1, t2) to rtt)
        while (samples.size > 8) samples.removeFirst()
        // trust the samples with the lowest round-trip time most
        val best = samples.sortedBy { it.second }.take(3)
        offsetMs = best.map { it.first }.sorted()[best.size / 2]
        rttMs = best.first().second
    }

    val hasEstimate get() = samples.isNotEmpty()
}
