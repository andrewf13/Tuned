package com.andrewf13.tuned.player

import kotlin.math.abs

/** A display mode reduced to what matters for choosing one (keeps this unit-testable). */
data class ModeSpec(val id: Int, val width: Int, val height: Int, val hz: Float)

object FrameRates {
    private val standard = floatArrayOf(
        24000f / 1001f, 24f, 25f, 30000f / 1001f, 30f, 50f, 60000f / 1001f, 60f,
    )

    /** Snap a measured rate (e.g. 24.97) to the broadcast rate it really is (25). */
    fun snap(fps: Float): Float {
        if (fps <= 0f) return 0f
        val best = standard.minBy { abs(it - fps) / it }
        return if (abs(best - fps) / best < 0.03f) best else fps
    }

    /** Median frame interval → fps. Robust against the odd dropped or doubled frame. */
    fun fromTimestamps(ptsUs: List<Long>): Float {
        val deltas = ptsUs.zipWithNext { a, b -> b - a }.filter { it in 1_000..200_000 }.sorted()
        if (deltas.size < 8) return 0f
        return snap(1_000_000f / deltas[deltas.size / 2])
    }

    /**
     * Pick the display mode for [fps] at the current resolution.
     * Film (23.976/24) prefers a 1x mode; 25/29.97/30 prefer 2x (50/59.94/60 Hz),
     * which also suits interlaced broadcast. 50/60 use 1x. Returns null if nothing fits.
     */
    fun choose(modes: List<ModeSpec>, current: ModeSpec, fps: Float): ModeSpec? {
        if (fps <= 0f) return null
        val candidates = modes
            .filter { it.width == current.width && it.height == current.height }
            .sortedByDescending { it.id == current.id }
        val film = abs(fps - 24f) < 0.5f
        val multipliers = if (film || fps > 40f) listOf(1, 2) else listOf(2, 1)
        // First an exact match (separates 23.976 from 24), then a near one (24 Hz for 23.976).
        for (tolerance in floatArrayOf(0.0008f, 0.003f)) {
            for (k in multipliers) {
                val target = fps * k
                candidates.firstOrNull { abs(it.hz - target) / target < tolerance }?.let { return it }
            }
        }
        return null
    }
}
