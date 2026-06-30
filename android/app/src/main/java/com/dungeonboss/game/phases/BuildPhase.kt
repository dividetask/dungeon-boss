package com.dungeonboss.game.phases

import com.dungeonboss.game.Game
import com.dungeonboss.game.Player
import com.dungeonboss.model.BuildCard
import com.dungeonboss.model.Room
import com.dungeonboss.model.Upgrade

/**
 * Build phase. A player MAY take one build action: place a room into any of the
 * 5 slots (an empty slot is filled, an occupied slot is replaced), spend a basic
 * or advanced room card to upgrade a placed room (granting its bait icons and a
 * room level), or attach a dedicated upgrade card. This phase exposes the step
 * the decision loop drives. Orchestration only. Mirrors `webapp/lib/build_phase`.
 *
 * [place] target encoding:
 *   - placing a room          -> the slot index (Int 0..4)
 *   - attaching an Upgrade     -> the (occupied) slot index (Int)
 *   - upgrading with a room    -> the string "upgrade:<slot>" (the Room is spent)
 */
object BuildPhase {

    /**
     * Apply a player's build choice. Returns the cards this action pushed to the
     * room deck (a replaced room, a displaced upgrade, or the spent upgrade card),
     * so the caller can reclaim them when undoing the placement.
     */
    fun place(game: Game, player: Player, cardId: String?, target: Any?): List<BuildCard> {
        if (cardId == null) return emptyList()

        val card = player.takeRoomFromHand(cardId)
            ?: throw IllegalArgumentException("card not in hand: $cardId")
        val dungeon = player.dungeon!!
        val discarded = mutableListOf<BuildCard>()

        when {
            card is Upgrade -> {
                val replaced = dungeon.applyUpgrade(slotOf(target), card)
                if (replaced != null) { game.roomDeck.discard(replaced); discarded.add(replaced) }
            }
            card is Room && isUpgradeWith(target) -> {
                // Spend a basic/advanced room card to upgrade a placed room: grants
                // its bait icons and a room level. The spent card is discarded.
                dungeon.upgradeRoomWith(slotOf(target), card)
                game.roomDeck.discard(card)
                discarded.add(card)
            }
            card is Room && card.advanced -> placeAdvanced(game, player, card, slotOf(target), discarded)
            card is Room -> placeBasic(game, dungeon, card, slotOf(target), discarded)
        }
        return discarded
    }

    private fun placeBasic(
        game: Game,
        dungeon: com.dungeonboss.game.Dungeon,
        card: Room,
        slot: Int,
        discarded: MutableList<BuildCard>
    ) {
        val old = dungeon.placeRoom(slot, card)
        if (old != null) {
            game.roomDeck.discard(old.baseRoom); discarded.add(old.baseRoom) // replaced room returns to deck
            old.upgrade?.let { game.roomDeck.discard(it); discarded.add(it) } // its upgrade is lost
        }
    }

    /**
     * An advanced room may fill an empty slot freely; replacing an occupied slot
     * requires that room to share at least one of the advanced room's bait icons.
     */
    private fun placeAdvanced(game: Game, player: Player, card: Room, slot: Int, discarded: MutableList<BuildCard>) {
        val dungeon = player.dungeon!!
        val existing = dungeon.slots[slot]
        if (existing != null) {
            require(existing.bait.shares(card.bait)) {
                "advanced room needs a room sharing at least one bait icon"
            }
        }
        placeBasic(game, dungeon, card, slot, discarded)
    }

    private fun isUpgradeWith(target: Any?): Boolean =
        target is String && target.startsWith("upgrade:")

    private fun slotOf(target: Any?): Int = when (target) {
        is Int -> target
        else -> target.toString().substringAfter(':', target.toString()).trim().toInt()
    }
}
