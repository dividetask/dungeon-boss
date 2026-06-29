package com.dungeonboss.game

import com.dungeonboss.model.Bait
import com.dungeonboss.model.Encounter

/**
 * A read-only summary of a dungeon: its total damage and its bait icons totalled
 * by type across the boss and all rooms. Used for the player overview panel.
 */
class DungeonSummary(private val dungeon: Dungeon) {
    fun bossName(): String = dungeon.boss.name

    /** Sum of every encounter's damage (rooms + boss). */
    fun totalDamage(): Int = sources().sumOf { it.damage }

    /** Bait type => count, in canonical bait order, omitting zeros. */
    fun baitTotals(): Map<Bait, Int> {
        val totals = LinkedHashMap<Bait, Int>()
        for (bait in Bait.entries) {
            val count = sources().sumOf { it.bait.count(bait) }
            if (count > 0) totals[bait] = count
        }
        return totals
    }

    /** Every bait type => count, in canonical order, including zeros. */
    fun allBaitTotals(): Map<Bait, Int> {
        val totals = LinkedHashMap<Bait, Int>()
        for (bait in Bait.entries) {
            totals[bait] = sources().sumOf { it.bait.count(bait) }
        }
        return totals
    }

    private fun sources(): List<Encounter> = dungeon.rooms + dungeon.boss
}
