package com.dungeonboss.game

import com.dungeonboss.model.Encounter
import com.dungeonboss.model.Hero

/**
 * Interprets a room's declarative `effect` spec (see docs/cards.md). Like boss
 * effects, the behaviour is data — nothing here hard-codes a particular room.
 * The resolver consults these hooks; an absent key means "no such behaviour".
 * Mirrors `webapp/lib/room_effect.rb`.
 */
object RoomEffect {

    /** One "hit each matching member for `amount` unreducible damage" rule. */
    class PartyHit(raw: Map<String, Any?>) {
        private val match = Effects.Selector(Effects.mapOf(raw["match"]))
        private val amount = Effects.intOf(raw["amount"])

        fun hits(alive: List<Hero>): List<Pair<Hero, Int>> =
            alive.filter { match.matches(it) }.map { it to amount }
    }

    class Spec(raw: Map<String, Any?>) {
        private val auras = Effects.listOfMaps(raw["room_auras"]).map { Effects.Aura(it) }
        private val partyHitRules = Effects.listOfMaps(raw["party_hits"]).map { PartyHit(it) }
        private val poisonsOnHit = Effects.boolOf(raw["poisons_on_hit"])
        private val growsOnDeath = Effects.boolOf(raw["grows_on_death"])
        private val unreducible = Effects.boolOf(raw["unreducible"])
        private val damagesAll = Effects.boolOf(raw["damages_all"])
        private val nextRoomDamageValue = Effects.intOf(raw["next_room_damage"])
        private val drawOnDeath = raw["draw_on_death"] as? String // "ability" | "room" | null
        private val discardBoostSpec =
            if (raw["discard_boost"] != null) Effects.mapOf(raw["discard_boost"]) else null

        /** Extra damage this room grants to another room, for `points` owner points. */
        fun auraBonus(target: Encounter, points: Int = 0): Int = auras.sumOf { it.bonus(target, points) }

        /** [[hero, amount], ...] unreducible hits this room deals to party members. */
        fun partyHits(alive: List<Hero>): List<Pair<Hero, Int>> = partyHitRules.flatMap { it.hits(alive) }

        fun poisonsOnHit(): Boolean = poisonsOnHit
        fun growsOnDeath(): Boolean = growsOnDeath

        /** This room's own single-target damage cannot be reduced by hero abilities. */
        fun unreducible(): Boolean = unreducible

        /**
         * This room's base hit lands on EVERY alive hero (still reducible as
         * usual), rather than a single target (Poison Gas).
         */
        fun damagesAll(): Boolean = damagesAll

        /** Damage the hero this room hits also takes in the NEXT room (one-shot), or 0. */
        fun nextRoomDamage(): Int = nextRoomDamageValue

        /** The deck ("ability"/"room") the owner draws from per death here, or null. */
        fun drawsOnDeath(): String? = drawOnDeath

        /** Whether the owner may discard a room card to boost this room. */
        fun boostable(): Boolean = discardBoostSpec != null

        /** The discard-to-boost spec (add_damage / set_damage / unreducible), or null. */
        fun discardBoost(): Map<String, Any?>? = discardBoostSpec
    }

    val NULL = Spec(emptyMap())

    /**
     * The parsed effect for an encounter. Rooms carry an `effect` map; a plain
     * room or the boss yields the do-nothing NULL spec.
     */
    fun forEncounter(encounter: Encounter): Spec {
        val effect = encounter.effect
        return if (effect.isEmpty()) NULL else Spec(effect)
    }
}
