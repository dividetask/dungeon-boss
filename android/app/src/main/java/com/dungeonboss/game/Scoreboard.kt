package com.dungeonboss.game

/**
 * Decides whether the game is over and who won.
 *
 * - A player with 5+ wounds is eliminated (loses outright).
 * - The game ends when any player reaches 10 points, or all but one player has
 *   been eliminated.
 * - Final score for a surviving player is points − 2 × wounds; highest wins.
 * - A tie is broken in favour of the player who ended the game.
 */
object Scoreboard {
    const val LOSS_WOUNDS = 5
    const val WIN_POINTS = 10

    data class Standing(val player: Player, val score: Int, val eliminated: Boolean)

    fun eliminated(player: Player): Boolean = player.wounds >= LOSS_WOUNDS

    fun score(player: Player): Int = player.points - 2 * player.wounds

    fun survivors(players: List<Player>): List<Player> = players.filterNot { eliminated(it) }

    fun over(players: List<Player>): Boolean =
        players.any { it.points >= WIN_POINTS } || survivors(players).size <= 1

    /** Standings for display, best score first (eliminated players last). */
    fun standings(players: List<Player>): List<Standing> =
        players
            .map { Standing(it, score(it), eliminated(it)) }
            .sortedWith(compareBy({ if (it.eliminated) 1 else 0 }, { -it.score }))

    /**
     * The winning player. [ender] is the player who triggered the game's end and
     * wins any tie.
     */
    fun winner(players: List<Player>, ender: Player): Player {
        val contenders = survivors(players)
        if (contenders.isEmpty()) return ender

        val best = contenders.maxOf { score(it) }
        val top = contenders.filter { score(it) == best }
        if (top.size == 1) return top.first()
        return if (top.any { it === ender }) ender else top.first()
    }
}
