package com.dungeonboss.game.phases

import com.dungeonboss.game.Game
import com.dungeonboss.game.Player

/**
 * Draw phase. A player draws 1 room card plus 1 additional card per card they
 * discarded in the Discard phase (so Discard + Draw always nets +1 card). Drawn
 * rooms beyond the hand cap are discarded back to the deck. Orchestration only.
 */
object DrawPhase {
    const val BASE_DRAW = 1

    fun drawFor(game: Game, player: Player, discardedCount: Int) {
        repeat(BASE_DRAW + discardedCount) {
            val card = game.roomDeck.draw() ?: return@repeat
            if (!player.addRoomToHand(card)) game.roomDeck.discard(card)
        }
    }
}
