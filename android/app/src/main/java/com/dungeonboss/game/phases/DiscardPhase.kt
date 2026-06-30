package com.dungeonboss.game.phases

import com.dungeonboss.game.Game
import com.dungeonboss.game.Player
import com.dungeonboss.model.BuildCard

/**
 * Discard phase. Each player MAY discard 0, 1, or 2 room cards from hand
 * (entirely optional). The number discarded feeds the following Draw phase
 * (draw = 1 + discards). Returns the discarded cards so the caller can offer an
 * undo. Orchestration only. Mirrors `webapp/lib` discard handling.
 */
object DiscardPhase {
    const val MAX_DISCARDS = 2

    fun discard(game: Game, player: Player, cardIds: List<String>): List<BuildCard> {
        require(cardIds.size <= MAX_DISCARDS) { "may discard at most $MAX_DISCARDS room cards" }
        val discarded = mutableListOf<BuildCard>()
        for (id in cardIds) {
            val card = player.takeRoomFromHand(id)
                ?: throw IllegalArgumentException("room not in hand: $id")
            game.roomDeck.discard(card)
            discarded.add(card)
        }
        return discarded
    }
}
