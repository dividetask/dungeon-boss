package com.dungeonboss.game

import com.dungeonboss.model.Encounter

/**
 * Small helpers over a room's flat field schema (see docs/cards.md). Nothing here
 * hard-codes a particular room: behaviour is read from the encounter's typed
 * fields. The resolver reads most fields directly; these helpers cover the bits
 * the Game / UI need (crawl-time discard boost and room auras).
 */
object RoomEffect {

    /** Whether the owner may discard a card to boost this room during the crawl. */
    fun boostable(encounter: Encounter): Boolean =
        encounter.discardLeadDamage > 0 || encounter.discardAllDamage > 0

    /** How much each discarded card adds to this room's damage (all takes precedence). */
    fun boostAmount(encounter: Encounter): Int =
        if (encounter.discardAllDamage > 0) encounter.discardAllDamage else encounter.discardLeadDamage

    /**
     * Extra damage [source]'s aura grants to [target] (0 if it doesn't match or
     * there is no aura). A room never buffs itself or the boss (the caller skips
     * the granting room).
     */
    fun auraBonus(source: Encounter, target: Encounter, points: Int = 0): Int {
        val aura = source.roomAura ?: return 0
        return Effects.Aura(aura).bonus(target, points)
    }
}
