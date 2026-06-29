package com.dungeonboss.game

import com.dungeonboss.model.Encounter
import com.dungeonboss.model.Hero

/**
 * Runs a single hero through a single dungeon and reports the outcome. Applies
 * room/boss damage reduced by the hero's special ability. Stateless; returns a
 * result rather than mutating anything.
 */
object CrawlResolver {
    enum class Outcome { DIED, ESCAPED }

    /** One step: the [damage] is the amount actually taken (after the ability). */
    data class Step(val encounter: Encounter, val damage: Int, val healthAfter: Int)

    data class Result(val outcome: Outcome, val remainingHealth: Int, val log: List<Step>)

    fun resolve(hero: Hero, dungeon: Dungeon): Result {
        val ability = HeroAbility.lookup(hero)
        var health = hero.health
        val log = mutableListOf<Step>()

        for (encounter in dungeon.encounters()) {
            val damage = ability.reduced(encounter, encounter.damage)
            health -= damage
            log.add(Step(encounter, damage, health))
            if (health <= 0) break
        }

        val outcome = if (health <= 0) Outcome.DIED else Outcome.ESCAPED
        return Result(outcome, health, log)
    }
}
