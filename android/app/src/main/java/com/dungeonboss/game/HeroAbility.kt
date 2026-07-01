package com.dungeonboss.game

import com.dungeonboss.model.Encounter
import com.dungeonboss.model.Hero
import kotlin.math.ceil

/**
 * Hero damage modifiers, read from each hero's **data fields** (no per-id code):
 *
 *   party aura — `partyDamageReduction` (levelled), a flat reduction applied to
 *                whichever member is hit, while the granting hero is alive in the
 *                party, gated by the bait/room-type filters. Auras stack across
 *                members. (Cleric, Mage, Rogue.)
 *   self mult  — `selfDamageMultiplier`, applied (rounded up) only to the damage
 *                the hero personally takes. (Barbarian: 0.5 — a "halving".)
 *
 * A room's [Resist] mode gates how much of this applies to a given hit:
 *   NORMAL    — party auras, then the self multiplier (full reduction).
 *   NO_HALVE  — party auras only; the self multiplier (Barbarian halving) is
 *               skipped ("damage cannot be halved").
 *   NO_REDUCE — no reduction at all ("damage cannot be reduced"); also used for
 *               poison ticks and any unreducible per-crawl modifier.
 */
object HeroAbility {

    /** How a hit may be reduced by hero abilities. */
    enum class Resist { NORMAL, NO_HALVE, NO_REDUCE }

    /** Does [member]'s party aura apply to this encounter (both filters must hold)? */
    private fun filterMatches(member: Hero, encounter: Encounter): Boolean {
        val baitFilter = member.damageBaitFilter
        if (baitFilter != null && encounter.bait.count(baitFilter) == 0) return false
        val typeFilter = member.damageRoomTypeFilter?.lowercase()
        if (typeFilter != null) {
            val matchesType = when (typeFilter) {
                "trap" -> encounter.trap()
                "creature", "monster" -> encounter.creature()
                else -> encounter.type?.lowercase()?.contains(typeFilter) ?: false
            }
            if (!matchesType) return false
        }
        return true
    }

    /** The levelled flat reduction [member] grants against this encounter (0 if filtered out). */
    private fun partyReduction(member: Hero, encounter: Encounter): Int =
        if (filterMatches(member, encounter)) member.partyReduction else 0

    /**
     * Damage a target hero actually takes from an encounter, given the heroes
     * still alive in its party and the room's [resist] mode. Party auras apply
     * first; the target's own self multiplier (rounded up) applies last. Never
     * below zero.
     */
    fun damageTaken(
        target: Hero,
        encounter: Encounter,
        aliveMembers: List<Hero>,
        base: Int = encounter.damage,
        resist: Resist = Resist.NORMAL
    ): Int {
        if (resist == Resist.NO_REDUCE) return maxOf(base, 0)
        var damage = base
        for (member in aliveMembers) {
            damage = maxOf(damage - partyReduction(member, encounter), 0)
        }
        if (resist == Resist.NORMAL) damage = ceil(damage * target.selfDamageMultiplier).toInt()
        return maxOf(damage, 0)
    }
}
