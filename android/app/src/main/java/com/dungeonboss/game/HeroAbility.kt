package com.dungeonboss.game

import com.dungeonboss.model.Bait
import com.dungeonboss.model.Encounter
import com.dungeonboss.model.Hero
import kotlin.math.ceil

/**
 * Hero special abilities: damage modifiers applied to each encounter during the
 * crawl. Each ability has a [Scope]:
 *   PARTY — protects whoever takes the hit, while this hero is alive in the party
 *           (Cleric, Mage, Rogue).
 *   SELF  — only reduces damage this hero personally takes (Barbarian).
 *
 * Looked up by hero id; heroes without an entry take full damage (the null
 * ability). [damageTaken] combines a party's abilities for a single hit.
 */
object HeroAbility {
    enum class Scope { PARTY, SELF }

    interface Ability {
        val scope: Scope
        fun reduced(encounter: Encounter, damage: Int): Int
    }

    /** No reduction. */
    object Null : Ability {
        override val scope = Scope.SELF
        override fun reduced(encounter: Encounter, damage: Int): Int = damage
    }

    /** Halve all damage, rounded up — self only (Barbarian). */
    object HalveRoundedUp : Ability {
        override val scope = Scope.SELF
        override fun reduced(encounter: Encounter, damage: Int): Int = ceil(damage / 2.0).toInt()
    }

    /**
     * Reduce damage from any encounter (room OR boss) carrying a given bait by a
     * fixed amount, for the whole party (Cleric: undead −4, Mage: power −4).
     */
    class ReduceBaitDamage(private val bait: Bait, private val amount: Int) : Ability {
        override val scope = Scope.PARTY
        override fun reduced(encounter: Encounter, damage: Int): Int =
            if (encounter.bait.count(bait) > 0) maxOf(damage - amount, 0) else damage
    }

    /** Reduce damage from trap-type rooms by a fixed amount, party-wide (Rogue: −2). */
    class ReduceTrapDamage(private val amount: Int) : Ability {
        override val scope = Scope.PARTY
        override fun reduced(encounter: Encounter, damage: Int): Int {
            val type = encounter.type
            return if (type != null && type.lowercase().contains("trap")) {
                maxOf(damage - amount, 0)
            } else {
                damage
            }
        }
    }

    private val REGISTRY: Map<String, Ability> = mapOf(
        "hero_barbarian" to HalveRoundedUp,
        "hero_cleric" to ReduceBaitDamage(Bait.UNDEAD, 4),
        "hero_mage" to ReduceBaitDamage(Bait.POWER, 4),
        "hero_rogue" to ReduceTrapDamage(2)
    )

    /** The ability for a hero, or the null ability if it has none. */
    fun lookup(hero: Hero): Ability = REGISTRY[hero.id] ?: Null

    /**
     * Damage a target hero actually takes from an encounter, given the heroes
     * still alive in its party. Every alive member's party aura applies; the
     * target's own self ability applies last. Never below zero.
     */
    fun damageTaken(target: Hero, encounter: Encounter, aliveMembers: List<Hero>, base: Int = encounter.damage): Int {
        var damage = base
        for (member in aliveMembers) {
            val ability = lookup(member)
            if (ability.scope == Scope.PARTY) damage = ability.reduced(encounter, damage)
        }
        val own = lookup(target)
        if (own.scope == Scope.SELF) damage = own.reduced(encounter, damage)
        return damage
    }
}
