package com.dungeonboss.game

import com.dungeonboss.model.Bait
import com.dungeonboss.model.Encounter

/**
 * Totals the icons of a single bait type across a dungeon (boss + all rooms).
 * This is a dungeon's "enticement" for a hero whose preferred bait is that type.
 * Stateless.
 */
object BaitCounter {
    fun enticement(dungeon: Dungeon, bait: Bait): Int {
        val sources: List<Encounter> = dungeon.rooms + dungeon.boss
        return sources.sumOf { it.bait.count(bait) }
    }
}
