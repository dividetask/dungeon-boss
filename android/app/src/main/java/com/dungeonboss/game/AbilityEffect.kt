package com.dungeonboss.game

import com.dungeonboss.model.AbilityCard

/**
 * Interprets an ability card's declarative `effect` spec (see docs/cards.md).
 * `add_damage`, `unreducible`, `zero`, and `retreat` act on a chosen room, so
 * those cards need a room target; `draw_rooms` does not. Mirrors
 * `webapp/lib/ability_effect.rb`.
 */
object AbilityEffect {
    class Spec(raw: Map<String, Any?>) {
        /** +N to the targeted room this crawl, or null. */
        val addDamage: Int? = if (raw.containsKey("add_damage")) Effects.intOf(raw["add_damage"]) else null

        /** Number of room cards to draw, or null. */
        val drawRooms: Int? = if (raw.containsKey("draw_rooms")) Effects.intOf(raw["draw_rooms"]) else null

        val unreducible: Boolean = Effects.boolOf(raw["unreducible"])
        val zero: Boolean = Effects.boolOf(raw["zero"])
        val retreat: Boolean = Effects.boolOf(raw["retreat"])

        /** Whether this card must be played on a specific room. */
        fun targetsRoom(): Boolean = addDamage != null || unreducible || zero || retreat
    }

    fun forCard(card: AbilityCard): Spec = Spec(card.effect)
}
