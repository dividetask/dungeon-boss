package com.dungeonboss.game

/**
 * Decides whether the game is over and who won.
 *
 * - A player with 5+ wounds is eliminated (loses outright).
 * - The game ends when any player reaches 10 points, or all but one player has
 *   been eliminated.
 * - The player who ends the game gains a 5-point [END_GAME_BONUS].
 * - Final score for a surviving player is points − 2 × wounds (+ the end-game
 *   bonus for the ender); highest wins.
 * - A tie is broken in favour of the player who ended the game.
 */
object Scoreboard {
    const val LOSS_WOUNDS = 5
    const val WIN_POINTS = 10

    /** Points awarded to the player who ends the game (see [score]). */
    const val END_GAME_BONUS = 5

    data class Standing(val player: Player, val score: Int, val eliminated: Boolean)

    fun eliminated(player: Player): Boolean = player.wounds >= LOSS_WOUNDS

    /**
     * Final score: points − 2 × wounds, plus the [END_GAME_BONUS] when [player]
     * is the [ender] (the player who ended the game). Pass `ender = null` (the
     * default) to score mid-game, before any bonus applies.
     */
    fun score(player: Player, ender: Player? = null): Int =
        player.points - 2 * player.wounds + if (player === ender) END_GAME_BONUS else 0

    fun survivors(players: List<Player>): List<Player> = players.filterNot { eliminated(it) }

    fun over(players: List<Player>): Boolean =
        players.any { it.points >= WIN_POINTS } || survivors(players).size <= 1

    /**
     * Standings for display, best score first (eliminated players last). [ender]
     * is the player who ended the game and gets the bonus; pass null while the
     * game is still in progress.
     */
    fun standings(players: List<Player>, ender: Player? = null): List<Standing> =
        players
            .map { Standing(it, score(it, ender), eliminated(it)) }
            .sortedWith(compareBy({ if (it.eliminated) 1 else 0 }, { -it.score }))

    /**
     * The winning player. [ender] is the player who triggered the game's end;
     * they gain the end-game bonus and win any remaining tie.
     */
    fun winner(players: List<Player>, ender: Player): Player {
        val contenders = survivors(players)
        if (contenders.isEmpty()) return ender

        val best = contenders.maxOf { score(it, ender) }
        val top = contenders.filter { score(it, ender) == best }
        if (top.size == 1) return top.first()
        return if (top.any { it === ender }) ender else top.first()
    }
}
