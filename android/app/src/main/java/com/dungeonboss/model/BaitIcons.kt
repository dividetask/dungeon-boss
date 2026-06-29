package com.dungeonboss.model

/**
 * An immutable count of icons per bait type. Answers "how many of bait X?".
 * A bait type that is absent counts as 0. Accepts either a raw map from YAML
 * (string keys) or a [Bait]-keyed map; zero counts are dropped.
 */
class BaitIcons(counts: Map<*, *>? = null) {
    private val counts: Map<Bait, Int>

    init {
        val result = LinkedHashMap<Bait, Int>()
        counts?.forEach { (bait, count) ->
            val n = when (count) {
                is Number -> count.toInt()
                null -> 0
                else -> count.toString().toIntOrNull() ?: 0
            }
            if (n != 0) result[Bait.normalize(bait)] = n
        }
        this.counts = result
    }

    /** Number of icons of the given bait type. */
    fun count(bait: Bait): Int = counts[bait] ?: 0

    /** Total icons across all bait types. */
    fun total(): Int = counts.values.sum()

    /** True when, for every bait in [other], this holds at least as many. */
    fun contains(other: BaitIcons): Boolean =
        other.counts.all { (bait, n) -> count(bait) >= n }

    /** True when any bait type appears (>0) in both. */
    fun shares(other: BaitIcons): Boolean =
        counts.keys.any { other.count(it) > 0 }

    fun toMap(): Map<Bait, Int> = LinkedHashMap(counts)
}
