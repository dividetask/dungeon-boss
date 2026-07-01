package com.dungeonboss.game

import com.dungeonboss.model.Encounter

/**
 * Effective crawl damage for an encounter, broken into components for display:
 * the room's primary channel (lead, else all, else rear) at its current level
 * first, then each positive bonus (room auras + boss per-point bonus), then any
 * per-crawl ability/boost modifier. The sum is what the encounter's primary hit
 * actually deals this crawl. Matches the resolver's channel folding.
 */
object EncounterDamage {

    /** The room's primary (used) damage channel at its current level. */
    private fun primaryBase(encounter: Encounter): Int = when {
        encounter.leadDamage > 0 -> encounter.leadDamage
        encounter.damageAll > 0 -> encounter.damageAll
        encounter.damageRear > 0 -> encounter.damageRear
        else -> encounter.leadDamage
    }

    fun parts(
        dungeon: Dungeon,
        encounter: Encounter,
        points: Int,
        mods: CrawlModifiers? = null,
        index: Int = -1
    ): List<Int> {
        val modded = mods != null && index >= 0
        if (modded && mods!!.isZero(index)) return listOf(0)

        val bossEffect = BossEffect.forBoss(dungeon.boss)
        return if (encounter === dungeon.boss) {
            val intrinsic = if (modded && mods!!.isSet(index)) mods.setValue(index) else encounter.leadDamage
            val out = mutableListOf(intrinsic)
            val self = bossEffect.selfBonus(points)
            if (self > 0) out.add(self)
            if (modded) mods!!.bonus(index).let { if (it > 0) out.add(it) }
            out
        } else {
            val out = mutableListOf<Int>()
            if (modded && mods!!.isSet(index)) out.add(mods.setValue(index)) else out.add(primaryBase(encounter))
            val aura = bossEffect.roomBonus(encounter, points) +
                dungeon.rooms.sumOf { r ->
                    if (r === encounter) 0 else RoomEffect.auraBonus(r, encounter, points)
                }
            if (aura > 0) out.add(aura)
            if (modded) mods!!.bonus(index).let { if (it > 0) out.add(it) }
            out
        }
    }

    fun total(dungeon: Dungeon, encounter: Encounter, points: Int): Int =
        parts(dungeon, encounter, points).sum()

    /** The dungeon's effective total damage (rooms + boss) for the quick sheet. */
    fun dungeonTotal(dungeon: Dungeon, points: Int): Int =
        dungeon.encounters().sumOf { total(dungeon, it, points) }
}
