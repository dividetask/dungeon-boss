package com.dungeonboss.game

import com.dungeonboss.model.Boss
import com.dungeonboss.model.Encounter

/**
 * Interprets a boss's declarative `effect` spec (see docs/cards.md). Every boss
 * deals +`self_damage_per_point` damage per point its owner has scored
 * (default 1). `room_bonuses` add flat and/or per-point damage to each matching
 * room. Mirrors `webapp/lib/boss_effect.rb`.
 */
object BossEffect {
    class Spec(raw: Map<String, Any?>) {
        private val selfPerPoint =
            if (raw.containsKey("self_damage_per_point")) Effects.intOf(raw["self_damage_per_point"], 1) else 1
        private val roomBonuses = Effects.listOfMaps(raw["room_bonuses"]).map { Effects.Aura(it) }

        /** The boss's own bonus damage for `points` owner points. */
        fun selfBonus(points: Int): Int = selfPerPoint * points

        /** Total bonus damage this boss grants to one room, for `points` owner points. */
        fun roomBonus(room: Encounter, points: Int): Int = roomBonuses.sumOf { it.bonus(room, points) }
    }

    /** The parsed effect for a boss (defaults to "+1 per point, no room bonuses"). */
    fun forBoss(boss: Boss): Spec = Spec(boss.effect)
}
