package com.dungeonboss.game.phases

import com.dungeonboss.game.Game
import com.dungeonboss.game.Party
import com.dungeonboss.game.PartyNamer
import com.dungeonboss.game.Player
import com.dungeonboss.model.Bait
import com.dungeonboss.model.Hero

/**
 * Recharge (after the crawl). Three things happen (was RecruitmentPhase, which is
 * now step 2):
 *   1. Ability cards — each living player whose dungeon was NOT attacked this
 *      round draws one ability card.
 *   2. Party consolidation — heroes/parties that did not enter a dungeon band
 *      together: each waiting multi-hero party recruits one waiting lone hero, and
 *      the remaining waiting lone heroes pair off (board order, preferring a
 *      different preferred bait; an odd lone hero waits alone). Merged parties
 *      persist.
 *   3. Levelling — each hero that survived a crawl this round gains +1 level.
 */
object RechargePhase {
    fun run(
        game: Game,
        waiting: List<Party>,
        attackedOwners: Set<Player>,
        crawlSurvivors: Set<Hero>
    ): Game {
        // 1. Ability cards for un-attacked players.
        game.livingPlayers()
            .filterNot { attackedOwners.contains(it) }
            .forEach { it.addAbilityToHand(game.abilityDeck.draw()) }

        // 2. Party consolidation.
        consolidate(game, waiting)

        // 3. Levelling — survivors of a crawl grow stronger.
        crawlSurvivors.forEach { it.level += 1 }

        return game
    }

    private fun consolidate(game: Game, waiting: List<Party>) {
        val lones = waiting.filter { it.lone() }.toMutableList()
        val parties = waiting.filterNot { it.lone() }

        // Existing parties each pull in one lone hero.
        for (party in parties) {
            val lone = pickPartner(lones, party.heroes.map { it.preferredBait }) ?: continue
            lones.remove(lone)
            party.add(lone.heroes.first())
            game.removePartyFromTown(lone)
        }

        // Remaining lone heroes pair up into newly-named parties.
        while (lones.size >= 2) {
            val first = lones.removeAt(0)
            val partner = pickPartner(lones, first.heroes.map { it.preferredBait }) ?: lones.first()
            lones.remove(partner)
            first.add(partner.heroes.first())
            first.name = PartyNamer.generate(game.rng)
            game.removePartyFromTown(partner)
        }
        // Any leftover lone hero is the odd one out and stays alone.
    }

    /** Prefer a lone hero whose bait is not already represented; else the first. */
    private fun pickPartner(lones: List<Party>, takenBaits: List<Bait>): Party? =
        lones.firstOrNull { !takenBaits.contains(it.heroes.first().preferredBait) }
            ?: lones.firstOrNull()
}
