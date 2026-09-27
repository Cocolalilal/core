package com.maxrave.domain.data.player

/**
 * Transition styles for intelligent, automated DJ-like track transitions.
 * Inspired by and adapted from:
 * - DJtransGAN (ICASSP 2022): https://github.com/ChenPaulYu/DJtransGAN
 * - FlowFusion (UCLA DSU): https://github.com/the-data-science-union/DSU-W2025-FlowFusion-Automated-Song-Transitions
 */
enum class DjTransitionStyle(val key: String) {
    /**
     * AI Smart Automix: Analyzes BPM difference, Camelot harmonic key compatibility,
     * and song energy to automatically pick the most musically natural DJ transition.
     */
    SMART_AI("smart_ai"),

    /**
     * Club Bass Swap: The gold standard DJ technique.
     * Beat-aligned blend where the incoming track's bass is cut (<320Hz) so its melody
     * and vocals ride on top of the outgoing beat, followed by an energetic bass swap at the drop.
     */
    BASS_SWAP("bass_swap"),

    /**
     * Filter Sweep Washout: Sweeps a resonant high-pass filter over the outgoing track,
     * creating a spacey, airy build before dropping the incoming track on the downbeat.
     */
    FILTER_SWEEP("filter_sweep"),

    /**
     * Vinyl Brake / Cut: Classic turntable motor brake / tape-stop on the outgoing track,
     * dropping directly into the incoming track on beat 1. Ideal for large tempo shifts.
     */
    VINYL_BRAKE("vinyl_brake"),

    /**
     * Smooth Crossfade: Equal-power acoustic crossfade curve.
     */
    SMOOTH_CROSSFADE("smooth_crossfade");

    companion object {
        fun fromKey(key: String): DjTransitionStyle =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: SMART_AI
    }
}
