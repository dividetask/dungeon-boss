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
 *                the hero personally takes, unconditionally. (Barbarian: 0.5.)
 *
 * [damageTaken] applies the party auras first, then the target's self multiplier
 * last; never below zero. Mirrors docs/pseudocode.md (HeroAbility).
 */
object HeroAbility {

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
     * still alive in its party. Every alive member's party aura applies first;
     * the target's own self multiplier (rounded up) applies last. Never below zero.
     */
    fun damageTaken(target: Hero, encounter: Encounter, aliveMembers: List<Hero>, base: Int = encounter.damage): Int {
        var damage = base
        for (member in aliveMembers) {
            damage = maxOf(damage - partyReduction(member, encounter), 0)
        }
        damage = ceil(damage * target.selfDamageMultiplier).toInt()
        return maxOf(damage, 0)
    }
}
