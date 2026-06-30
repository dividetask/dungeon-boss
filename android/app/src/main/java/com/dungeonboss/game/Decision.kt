package com.dungeonboss.game

import com.dungeonboss.model.Card

/** The kinds of choice the game can wait on a player to make. */
enum class DecisionKind { CHOOSE_BOSS, PLACE_FIRST_ROOM, DISCARD_ROOMS, BUILD_ROOM }

/**
 * A choice the game is waiting for a player to make. Holds no logic — it only
 * describes what is being decided, by whom, and the available options.
 *
 *   CHOOSE_BOSS       pick one of two drawn boss cards (the other is discarded)
 *   PLACE_FIRST_ROOM  pick a room from hand to place into any of the 5 slots (Setup)
 *   DISCARD_ROOMS     pick 0–2 room cards from hand to throw away (Discard phase)
 *   BUILD_ROOM        pick a card from hand to play (into a slot, or to upgrade a
 *                     room), or skip (Build)
 */
class Decision(
    val kind: DecisionKind,
    val player: Player,
    options: List<Card>,
    val allowSkip: Boolean = false
) {
    val options: List<Card> = options.toList()

    fun prompt(): String = when (kind) {
        DecisionKind.CHOOSE_BOSS ->
            "${player.name}: choose your boss (the other is discarded)"
        DecisionKind.PLACE_FIRST_ROOM ->
            "${player.name}: choose a room and a slot to place it in"
        DecisionKind.DISCARD_ROOMS ->
            "${player.name}: discard 0–2 room cards (optional)"
        DecisionKind.BUILD_ROOM ->
            "${player.name}: place or upgrade a room, or build nothing"
    }
}
