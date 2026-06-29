package com.dungeonboss.game

import com.dungeonboss.model.Encounter
import com.dungeonboss.model.PlacedRoom

/**
 * Effective crawl damage for an encounter, broken into components for display:
 * base first, then each positive bonus (upgrade, grow, room auras + boss
 * per-point bonus). The sum is what the encounter actually deals (before any
 * per-crawl ability/boost modifiers). Mirrors the web app's `damage_parts`.
 */
object EncounterDamage {

    /**
     * [base, +bonus, …] — only positive bonuses are included. When [mods] and a
     * non-negative encounter [index] are supplied (the dungeon currently in the
     * pre-crawl window), the per-crawl ability/boost modifiers are folded in so a
     * room shows the damage it will actually deal this crawl: an override or zero
     * replaces the printed base, and any added damage (Reinforcements / discard
     * boost) appears as a final bonus. Matches the resolver's `effectiveBase`.
     */
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
            val intrinsic = if (modded && mods!!.isSet(index)) mods.setValue(index) else encounter.damage
            val out = mutableListOf(intrinsic)
            val self = bossEffect.selfBonus(points)
            if (self > 0) out.add(self)
            if (modded) mods!!.bonus(index).let { if (it > 0) out.add(it) }
            out
        } else {
            val placed = encounter as PlacedRoom
            val out = mutableListOf<Int>()
            if (modded && mods!!.isSet(index)) {
                // A discard-boost override replaces base + upgrade + grow entirely.
                out.add(mods.setValue(index))
            } else {
                out.add(placed.baseRoom.damage)
                placed.upgrade?.bonusDamage?.let { if (it > 0) out.add(it) }
                if (placed.grow > 0) out.add(placed.grow)
            }
            val aura = bossEffect.roomBonus(encounter, points) +
                dungeon.rooms.sumOf { r ->
                    if (r === encounter) 0 else RoomEffect.forEncounter(r).auraBonus(encounter, points)
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
