package com.dungeonboss.game.phases

import com.dungeonboss.game.CrawlModifiers
import com.dungeonboss.game.Game
import com.dungeonboss.game.Party
import com.dungeonboss.game.PartyCrawlResolver
import com.dungeonboss.game.Player

/**
 * Gauntlet (the final step of the Crawl). A party is sent into its dungeon as a
 * unit. Each hero that dies earns the controlling player a point and is removed
 * from the party; if at least one member survives a FULL crawl, the player gains
 * exactly one wound (one per party). A Retreat makes the party turn back at a
 * chosen room: the rooms before it still resolve (so deaths there still score),
 * but the party escapes the rest and the owner takes no wound. A party only
 * leaves town when all of its members die. Orchestration only; the math is in
 * PartyCrawlResolver. (Was CrawlPhase.)
 */
object GauntletPhase {
    /**
     * One resolved party crawl, for display/inspection. [retreated] is true when
     * a Retreat turned the party back before finishing the dungeon. [bossBonus]
     * is the owner's point total AT THE START of this crawl — the per-point bonus
     * that actually applied to it — so the UI can replay the dungeon at the right
     * damage.
     */
    data class Outcome(
        val player: Player,
        val party: Party,
        val result: PartyCrawlResolver.Result,
        val retreated: Boolean = false,
        val bossBonus: Int = 0
    )

    fun resolveParty(
        game: Game,
        player: Player,
        party: Party,
        modifiers: CrawlModifiers = CrawlModifiers()
    ): Outcome {
        // The boss deals extra damage equal to its owner's points, snapshotted now
        // so kills during this crawl only count toward the next one.
        val bossBonus = player.points
        val result = PartyCrawlResolver.resolve(party, player.dungeon!!, bossBonus, modifiers)

        repeat(result.deaths) { player.gainPoint() }
        // A wound is only taken when a hero survives a FULL crawl — retreaters escape.
        if (!modifiers.retreating() && result.survivors.isNotEmpty()) player.gainWound()

        result.deadHeroes.forEach { party.remove(it) }
        if (party.isEmpty()) game.removePartyFromTown(party)

        return Outcome(player, party, result, modifiers.retreating(), bossBonus)
    }
}
