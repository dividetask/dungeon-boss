package com.dungeonboss.game

/**
 * Forecasts how a set of parties would fare crawling a dungeon, without touching
 * the real game. Used by [LogicAgent] to score a candidate placement: how many
 * heroes it would kill (points), how many parties would survive (wounds), and how
 * much damage it would deal. Mirrors `webapp/lib/dungeon_forecast.rb`.
 *
 * Each crawl runs as a `dryRun`, so grow-on-death rooms are not permanently grown
 * and the same dungeon can be forecast repeatedly with no lasting effect.
 */
object DungeonForecast {
    /**
     * kills:       total heroes that die across all parties (= points scored)
     * wounds:      parties with at least one survivor (= wounds taken, one each)
     * totalDamage: post-reduction damage dealt to every hero, summed
     * heroCount:   heroes who entered (denominator for the average)
     */
    data class Outcome(val kills: Int, val wounds: Int, val totalDamage: Int, val heroCount: Int) {
        val avgDamage: Double get() = if (heroCount == 0) 0.0 else totalDamage.toDouble() / heroCount
    }

    fun run(dungeon: Dungeon, parties: List<Party>, bossBonus: Int = 0): Outcome {
        var kills = 0
        var wounds = 0
        var totalDamage = 0
        var heroCount = 0

        for (party in parties) {
            val result = PartyCrawlResolver.resolve(party, dungeon, bossBonus, dryRun = true)
            kills += result.deaths
            if (result.survivors.isNotEmpty()) wounds += 1
            totalDamage += result.log.sumOf { it.damage }
            heroCount += party.size()
        }
        return Outcome(kills, wounds, totalDamage, heroCount)
    }

    /**
     * A copy of the dungeon with the same boss and rooms (in order), preserving
     * each slot's upgrade and permanent grow bonus. Used to apply a candidate
     * build move without disturbing the real dungeon.
     */
    fun clone(dungeon: Dungeon): Dungeon {
        val copy = Dungeon(dungeon.boss)
        dungeon.rooms.asReversed().forEach { copy.addRoomToLeft(it.baseRoom) }
        dungeon.rooms.forEachIndexed { index, placed ->
            copy.rooms[index].upgrade = placed.upgrade
            copy.rooms[index].grow = placed.grow
        }
        return copy
    }
}
