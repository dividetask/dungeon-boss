package com.dungeonboss.game.phases

import com.dungeonboss.game.BaitCounter
import com.dungeonboss.game.Dungeon
import com.dungeonboss.game.Game
import com.dungeonboss.game.Party
import com.dungeonboss.game.Player

/**
 * Bait. Each party is drawn toward the single most enticing dungeon (enticement
 * = the combined count of every member's preferred bait across that dungeon — a
 * bait shared by two members counts twice). A tie for the top enticement leaves
 * the party unenticed.
 *
 * An enticed party only enters if its combined courage is at least the target
 * dungeon owner's points. Bait is combined with the Crawl phase: the game asks
 * [targetFor] for each party right before it would enter, so the courage check
 * uses the owner's CURRENT points (which rise as earlier parties die). A party
 * that does not enter is left for Recruitment. Orchestration only; math in
 * BaitCounter.
 */
object BaitPhase {
    /**
     * The player whose dungeon this party enters right now, or null if it stays
     * in town — unenticed (tie/zero), or too timid for the owner's current points.
     */
    fun targetFor(game: Game, party: Party): Player? {
        val player = mostEnticingPlayer(game, party) ?: return null
        return if (party.courage() >= player.points) player else null
    }

    /**
     * The player whose dungeon is strictly most enticing, or null on a tie.
     * Eliminated players' dungeons do not attract parties.
     */
    fun mostEnticingPlayer(game: Game, party: Party): Player? {
        val scored = game.livingPlayers().map { it to enticement(it.dungeon!!, party) }
        if (scored.isEmpty()) return null
        val top = scored.maxOf { it.second }
        val winners = scored.filter { it.second == top }
        return if (winners.size == 1) winners.first().first else null
    }

    /** Combined enticement: sum each member's preferred-bait count for the dungeon. */
    fun enticement(dungeon: Dungeon, party: Party): Int =
        party.heroes.sumOf { BaitCounter.enticement(dungeon, it.preferredBait) }
}
