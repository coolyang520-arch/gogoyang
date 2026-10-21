package com.gogoyang.plates

/**
 * Turns match scores (one per recorded scooter) into a verdict.
 * A photo may only ever point at one scooter: when two recorded scooters both look
 * like the photo, the app does not guess, it asks.
 */
object Decide {
    /** Score from which a photo is taken as the same scooter on its own. */
    const val STRONG = 40
    /** Score below which a recorded scooter is not considered at all. */
    const val WEAK = 20
    /** The best score must be at least this many times the runner-up to count as the only match. */
    const val MARGIN = 2.0
    /** Fewer features than this: too dark, blurred or far away to compare reliably. */
    const val MIN_FEATURES = 150

    // Plate box inside the guide frame, as shares of the frame. Matches there count double,
    // since the plate is what tells two scooters of the same model apart.
    const val PLATE_LEFT = 0.25f
    const val PLATE_RIGHT = 0.75f
    const val PLATE_TOP = 0.58f
    const val PLATE_BOTTOM = 0.84f

    fun inPlate(x: Float, y: Float): Boolean = x in PLATE_LEFT..PLATE_RIGHT && y in PLATE_TOP..PLATE_BOTTOM

    sealed class Verdict {
        /** Nothing recorded looks like the photo. */
        object None : Verdict()
        /** Exactly one recorded scooter matches, clearly. */
        data class Same(val id: String, val score: Int) : Verdict()
        /** One scooter is similar but not certain: the officer compares the photos. */
        data class Maybe(val id: String, val score: Int) : Verdict()
        /** Several recorded scooters look alike; the officer picks one (or none). Best first. */
        data class Several(val ids: List<String>) : Verdict()
    }

    fun decide(scores: Map<String, Int>): Verdict {
        val ranked = scores.entries.filter { it.value >= WEAK }.sortedByDescending { it.value }
        if (ranked.isEmpty()) return Verdict.None
        val best = ranked[0]
        val second = ranked.getOrNull(1)?.value ?: 0
        if (second * MARGIN <= best.value) {
            return if (best.value >= STRONG) Verdict.Same(best.key, best.value) else Verdict.Maybe(best.key, best.value)
        }
        val close = ranked.filter { it.value * MARGIN > best.value }.take(4).map { it.key }
        return Verdict.Several(close)
    }
}
