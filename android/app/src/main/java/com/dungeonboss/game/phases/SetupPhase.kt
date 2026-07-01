package com.dungeonboss.game.phases

import com.dungeonboss.game.Dungeon
import com.dungeonboss.game.Game
import com.dungeonboss.game.Player
import com.dungeonboss.model.Room

/**
 * Setup phase (runs once at game start). The player makes the choices: dealt a
 * starting hand (mulliganed until it holds a room), dealt two boss candidates
 * and CHOOSEs one, then CHOOSEs one room to place beside their boss. This phase
 * exposes the discrete steps the decision loop drives; it does not pick.
 */
object SetupPhase {
    const val STARTING_ROOMS = 4
    const val STARTING_ABILITIES = 2
    const val BOSS_CANDIDATES = 2

    /** Deal opening hands and draw each player's boss candidates. */
    fun deal(game: Game) {
        for (player in game.players) {
            dealStartingHand(game, player)
            game.abilityDeck.drawMany(STARTING_ABILITIES).forEach { player.addAbilityToHand(it) }
            game.setBossCandidates(player, game.bossDeck.drawMany(BOSS_CANDIDATES))
        }
    }

    /**
     * Deal a starting hand, mulliganing (redrawing) until it contains at least
     * one basic room — only a basic room can be placed as the first room (not an
     * upgrade or an advanced room).
     */
    fun dealStartingHand(game: Game, player: Player) {
        while (true) {
            val drawn = game.roomDeck.drawMany(STARTING_ROOMS)
            if (drawn.any { it is Room && !it.advanced }) {
                drawn.forEach { player.addRoomToHand(it) }
                return
            }
            drawn.forEach { game.roomDeck.discard(it) } // mulligan
        }
    }

    /** Apply a player's boss choice: keep the chosen card, discard the rest. */
    fun chooseBoss(game: Game, player: Player, bossId: String) {
        val candidates = game.bossCandidatesFor(player)
        val chosen = candidates.firstOrNull { it.id == bossId }
            ?: throw IllegalArgumentException("boss not among candidates: $bossId")

        candidates.filter { it !== chosen }.forEach { game.bossDeck.discard(it) }
        player.dungeon = Dungeon(chosen)
        game.clearBossCandidates(player)
    }

    /**
     * Apply a player's first-room choice (mandatory; must be an actual room),
     * placing it into the chosen slot (any of the 5).
     */
    fun placeFirstRoom(player: Player, roomId: String, slot: Int = 0) {
        val card = player.takeRoomFromHand(roomId)
            ?: throw IllegalArgumentException("room not in hand: $roomId")
        val room = card as? Room
            ?: throw IllegalArgumentException("first placement must be a room")
        player.dungeon!!.placeRoom(slot, room)
    }
}
