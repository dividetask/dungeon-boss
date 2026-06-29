package com.dungeonboss.game.phases

import com.dungeonboss.game.Game
import com.dungeonboss.game.Party
import com.dungeonboss.game.PartyNamer
import com.dungeonboss.model.Bait

/**
 * Recruitment phase (after the crawl). Heroes/parties that did not enter a
 * dungeon this turn (unenticed or too afraid) band together for next time:
 *   - each waiting multi-hero party recruits one waiting lone hero, and
 *   - the remaining waiting lone heroes pair off,
 * both in board (town) order, preferring a partner with a different preferred
 * bait. An odd lone hero is left out and waits alone. Merged parties persist.
 */
object RecruitmentPhase {
    fun run(game: Game, waiting: List<Party>): Game {
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
        return game
    }

    /** Prefer a lone hero whose bait is not already represented; else the first. */
    private fun pickPartner(lones: List<Party>, takenBaits: List<Bait>): Party? =
        lones.firstOrNull { !takenBaits.contains(it.heroes.first().preferredBait) }
            ?: lones.firstOrNull()
}
