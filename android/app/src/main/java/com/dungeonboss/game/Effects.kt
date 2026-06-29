package com.dungeonboss.game

import com.dungeonboss.model.Bait
import com.dungeonboss.model.Encounter
import com.dungeonboss.model.Hero

/**
 * Shared building blocks for the declarative effects defined in `data/cards/`.
 * Effects are plain data — what they target and how much they add live in the
 * YAML, so both clients read the same definition rather than reimplementing
 * behaviour in code. Mirrors `webapp/lib/effects.rb`.
 */
object Effects {

    /** Read an integer out of a raw YAML value (Number/String), defaulting to 0. */
    fun intOf(raw: Any?, default: Int = 0): Int = when (raw) {
        is Number -> raw.toInt()
        is String -> raw.trim().toIntOrNull() ?: default
        else -> default
    }

    @Suppress("UNCHECKED_CAST")
    fun mapOf(raw: Any?): Map<String, Any?> = (raw as? Map<String, Any?>) ?: emptyMap()

    fun listOfMaps(raw: Any?): List<Map<String, Any?>> =
        (raw as? List<*>)?.mapNotNull {
            @Suppress("UNCHECKED_CAST")
            it as? Map<String, Any?>
        } ?: emptyList()

    fun boolOf(raw: Any?): Boolean = when (raw) {
        is Boolean -> raw
        is String -> raw.equals("true", ignoreCase = true)
        else -> false
    }

    /**
     * A selector: a card (room/boss) or hero matches when it satisfies every
     * dimension that is present. Dimensions:
     *   tag            - the card carries this tag
     *   type           - "trap" or "creature"/"monster" (by the room's type)
     *   bait           - the card has at least one icon of this bait type
     *   preferred_bait - the hero's preferred bait (used to match heroes)
     */
    class Selector(raw: Map<String, Any?> = emptyMap()) {
        private val tag = (raw["tag"] as? String)?.trim()?.lowercase()
        private val type = (raw["type"] as? String)?.trim()?.lowercase()
        private val bait = raw["bait"]?.let { Bait.normalize(it.toString()) }
        private val preferredBait = raw["preferred_bait"]?.let { Bait.normalize(it.toString()) }

        fun matches(card: Any): Boolean {
            if (tag != null && !tagged(card)) return false
            if (type != null && !typeMatches(card)) return false
            if (bait != null && !hasBait(card)) return false
            if (preferredBait != null && !prefers(card)) return false
            return true
        }

        private fun tagged(card: Any): Boolean = when (card) {
            is Encounter -> card.tags.contains(tag)
            is Hero -> card.tags.contains(tag)
            else -> false
        }

        private fun hasBait(card: Any): Boolean =
            bait != null && card is Encounter && card.bait.count(bait) > 0

        private fun prefers(card: Any): Boolean =
            card is Hero && card.preferredBait == preferredBait

        private fun typeMatches(card: Any): Boolean = when (type) {
            "trap" -> card is Encounter && card.trap()
            "creature", "monster" -> card is Encounter && card.creature()
            else -> false
        }
    }

    /**
     * A damage aura: +`flat` and/or +`per_point × points` to every card that
     * matches the selector.
     */
    class Aura(raw: Map<String, Any?> = emptyMap()) {
        private val match = Effects.Selector(Effects.mapOf(raw["match"]))
        private val flat = Effects.intOf(raw["flat"])
        private val perPoint = Effects.intOf(raw["per_point"])

        fun bonus(target: Any, points: Int): Int =
            if (match.matches(target)) flat + perPoint * points else 0
    }
}
