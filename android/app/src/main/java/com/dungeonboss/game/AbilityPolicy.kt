package com.dungeonboss.game

/**
 * The AI's ability-card policy, parsed from the `ai_abilities` block of
 * ai_logic.yaml. Unlike the decision heuristics (tie-break chains), an ability is
 * judged on its own: the AI forecasts the pending crawl with and without the card
 * and spends it only when one of the objectives this policy lists for that card
 * clears its threshold. The policy is data; [AbilityChooser] is the interpreter.
 * See docs/ai.md.
 */
class AbilityPolicy(
    /** An opponent counts as "winning" (worth a wound-dealing card) at these points. */
    val winningOpponentPoints: Int,
    private val cards: Map<String, CardPolicy>
) {
    /**
     * The objectives that justify spending one ability card:
     *  - [preventSelfWound]      the crawl owner plays it to stop a hero surviving
     *                            its own dungeon (a survivor costs the owner a wound)
     *  - [woundWinningOpponent]  an opponent plays it to give a *winning* owner a
     *                            wound (make a hero survive that dungeon)
     *  - [denyOpponentPoints]    an opponent plays it when it denies the owner at
     *                            least this many points (that many fewer deaths)
     *  - [refillRoomHandBelow]   a no-target draw card (Blueprints), played when the
     *                            actor holds fewer than this many room cards
     */
    data class CardPolicy(
        val preventSelfWound: Boolean = false,
        val woundWinningOpponent: Boolean = false,
        val denyOpponentPoints: Int? = null,
        val refillRoomHandBelow: Int? = null
    )

    /** The policy for a card id, or null when the AI is not configured to play it. */
    fun forCard(cardId: String): CardPolicy? = cards[cardId]

    companion object {
        /** No abilities configured: the AI plays none. */
        val NONE = AbilityPolicy(Int.MAX_VALUE, emptyMap())

        /** Build a policy from the raw `ai_abilities` map (or NONE when absent). */
        fun from(raw: Map<String, Any?>?): AbilityPolicy {
            if (raw.isNullOrEmpty()) return NONE
            val winning = Effects.intOf(raw["winning_opponent_points"], Int.MAX_VALUE)
            val cards = Effects.mapOf(raw["cards"]).mapValues { (_, value) ->
                val entry = Effects.mapOf(value)
                CardPolicy(
                    preventSelfWound = Effects.boolOf(entry["prevent_self_wound"]),
                    woundWinningOpponent = Effects.boolOf(entry["wound_winning_opponent"]),
                    denyOpponentPoints = intOrNull(entry["deny_opponent_points"]),
                    refillRoomHandBelow = intOrNull(entry["refill_room_hand_below"])
                )
            }
            return AbilityPolicy(winning, cards)
        }

        private fun intOrNull(raw: Any?): Int? = when (raw) {
            null -> null
            else -> Effects.intOf(raw)
        }
    }
}
