package com.dungeonboss.game.phases

import com.dungeonboss.game.Game

/**
 * Arrival phase. Draw one hero card per living player and place them face up in
 * town. Town is NOT cleared: heroes stay across turns and only leave by dying in
 * a dungeon. Eliminated players generate no arrivals. Orchestration only.
 */
object ArrivalPhase {
    fun run(game: Game): Game {
        repeat(game.livingPlayers().size) {
            val hero = game.heroDeck.draw()
            if (hero != null) game.addToTown(hero)
        }
        return game
    }
}
