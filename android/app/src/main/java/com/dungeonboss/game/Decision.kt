package com.dungeonboss.game

import com.dungeonboss.model.Card

/** The kinds of choice the game can wait on a player to make. */
enum class DecisionKind { CHOOSE_BOSS, PLACE_FIRST_ROOM, DISCARD_ROOM, BUILD_ROOM }

/**
 * A choice the game is waiting for a player to make. Holds no logic — it only
 * describes what is being decided, by whom, and the available options.
 *
 *   CHOOSE_BOSS       pick one of two drawn boss cards (the other is discarded)
 *   PLACE_FIRST_ROOM  pick a room from hand to place beside the boss (Setup)
 *   DISCARD_ROOM      pick a room from hand to throw away (Build)
 *   BUILD_ROOM        pick a card from hand to play, or skip (Build)
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
            "${player.name}: choose a room to place beside your boss"
        DecisionKind.DISCARD_ROOM ->
            "${player.name}: discard a room card"
        DecisionKind.BUILD_ROOM ->
            "${player.name}: choose a room to add to your dungeon, or build nothing"
    }
}
