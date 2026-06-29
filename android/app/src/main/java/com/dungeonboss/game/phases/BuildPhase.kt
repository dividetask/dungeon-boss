package com.dungeonboss.game.phases

import com.dungeonboss.game.Game
import com.dungeonboss.game.Player
import com.dungeonboss.model.BuildCard
import com.dungeonboss.model.Room
import com.dungeonboss.model.Upgrade

/**
 * Build phase. For each living player: draw two room cards, CHOOSE one to
 * discard, then CHOOSE one to play (or build nothing). A basic room may be added
 * as a new entrance (to the left) or placed on top of an existing room to
 * replace it; an upgrade attaches to a room; an advanced room replaces a room
 * that shares at least one of its bait icons. This phase exposes the steps the
 * decision loop drives. Orchestration only.
 */
object BuildPhase {
    const val DRAW_COUNT = 2

    /**
     * Draw two rooms into each living player's hand, up to the cap; any room that
     * does not fit is discarded back to the deck.
     */
    fun drawForEach(game: Game) {
        for (player in game.livingPlayers()) {
            game.roomDeck.drawMany(DRAW_COUNT).forEach { room ->
                if (!player.addRoomToHand(room)) game.roomDeck.discard(room)
            }
        }
    }

    /**
     * Discard a chosen card from the player's hand (mandatory each Build phase).
     * Returns the discarded card so the caller can offer an undo.
     */
    fun discard(game: Game, player: Player, cardId: String): BuildCard {
        val card = player.takeRoomFromHand(cardId)
            ?: throw IllegalArgumentException("room not in hand: $cardId")
        game.roomDeck.discard(card)
        return card
    }

    /**
     * Apply a player's build choice. Returns the cards this placement pushed to
     * the room deck (a replaced room and any displaced upgrade), so the caller can
     * reclaim them when undoing the placement.
     *   cardId: the card from hand to play, or null to build nothing
     *   target: basic room -> "new" (or null) to add an entrance, or a room index
     *           to replace; upgrade or advanced room -> the room index it targets.
     */
    fun place(game: Game, player: Player, cardId: String?, target: Any?): List<BuildCard> {
        if (cardId == null) return emptyList()

        val card = player.takeRoomFromHand(cardId)
            ?: throw IllegalArgumentException("card not in hand: $cardId")
        val dungeon = player.dungeon!!
        val discarded = mutableListOf<BuildCard>()

        when {
            card is Upgrade -> {
                val replaced = dungeon.applyUpgrade(targetIndex(target), card)
                if (replaced != null) { game.roomDeck.discard(replaced); discarded.add(replaced) }
            }
            card is Room && card.advanced -> placeAdvanced(game, player, card, targetIndex(target), discarded)
            target == null || target.toString() == "new" -> {
                dungeon.addRoomToLeft(card as Room)
            }
            else -> {
                val old = dungeon.replaceRoom(targetIndex(target), card as Room)
                game.roomDeck.discard(old.baseRoom); discarded.add(old.baseRoom) // replaced room returns to deck
                old.upgrade?.let { game.roomDeck.discard(it); discarded.add(it) } // its upgrade is lost
            }
        }
        return discarded
    }

    /**
     * An advanced room may replace any room that shares at least one of its bait
     * icons.
     */
    private fun placeAdvanced(game: Game, player: Player, card: Room, index: Int, discarded: MutableList<BuildCard>) {
        val placed = player.dungeon!!.rooms[index]
        require(placed.bait.shares(card.bait)) {
            "advanced room needs a room sharing at least one bait icon"
        }
        val old = player.dungeon!!.replaceRoom(index, card)
        game.roomDeck.discard(old.baseRoom); discarded.add(old.baseRoom)
        old.upgrade?.let { game.roomDeck.discard(it); discarded.add(it) }
    }

    private fun targetIndex(target: Any?): Int = when (target) {
        is Int -> target
        else -> target.toString().toInt()
    }
}
