package com.dungeonboss

import com.dungeonboss.game.BaitCounter
import com.dungeonboss.game.Dungeon
import com.dungeonboss.game.HeroAbility
import com.dungeonboss.game.Party
import com.dungeonboss.game.PartyCrawlResolver
import com.dungeonboss.game.Player
import com.dungeonboss.game.Scoreboard
import com.dungeonboss.game.phases.EnticePhase
import com.dungeonboss.model.Bait
import com.dungeonboss.model.BaitIcons
import com.dungeonboss.model.Boss
import com.dungeonboss.model.Hero
import com.dungeonboss.model.Room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engine fidelity checks: the Android engine must behave identically to the Ruby
 * reference (see webapp/test). These cover targeting, hero abilities, bait/
 * courage, and scoring.
 */
class EngineTest {

    private fun room(damage: Int, type: String = "Basic Trap", bait: Map<String, Int> = emptyMap()) =
        Room(id = "r", name = "R", type = type, damage = damage, bait = BaitIcons(bait))

    private fun boss(damage: Int, bait: Map<String, Int> = emptyMap()) =
        Boss(id = "b", name = "Boss", damage = damage, bait = BaitIcons(bait))

    private fun dungeon(bossDamage: Int, roomDamages: List<Int>): Dungeon {
        val d = Dungeon(boss(bossDamage))
        // place into slots 0.. so the listed order is left->right
        roomDamages.forEachIndexed { i, dmg -> d.placeRoom(i, room(dmg)) }
        return d
    }

    @Test
    fun partyContinuesAfterADeathUntilAllDie() {
        val a = Hero(id = "a", name = "A", preferredBait = Bait.GLORY, startingHp = 4)
        val b = Hero(id = "b", name = "B", preferredBait = Bait.GLORY, startingHp = 4)
        // entrance dmg 4 (kills A), boss dmg 4 (kills B)
        val res = PartyCrawlResolver.resolve(Party(listOf(a, b)), dungeon(4, listOf(4)))

        assertEquals(2, res.log.size)
        assertEquals(listOf(a, b), res.log.map { it.hero }) // B is hit after A dies
        assertEquals(2, res.deaths)
        assertTrue(res.survivors.isEmpty())
    }

    @Test
    fun twoHeroesWithTheSameIdAreIndependent() {
        // Distinct objects sharing an id (as the deck produces) must not collapse.
        val m1 = Hero(id = "hero_mage", name = "Mage", preferredBait = Bait.ARCANE, startingHp = 4)
        val m2 = Hero(id = "hero_mage", name = "Mage", preferredBait = Bait.ARCANE, startingHp = 4)
        val res = PartyCrawlResolver.resolve(Party(listOf(m1, m2)), dungeon(4, listOf(4)))

        assertEquals(2, res.deaths)
        assertEquals(listOf(m1, m2), res.log.map { it.hero })
    }

    @Test
    fun targetingHitsTheHighestCurrentHealthMember() {
        val low = Hero(id = "low", name = "Low", preferredBait = Bait.GLORY, startingHp = 3)
        val high = Hero(id = "high", name = "High", preferredBait = Bait.GLORY, startingHp = 8)
        val res = PartyCrawlResolver.resolve(Party(listOf(low, high)), dungeon(0, listOf(2)))
        // Only one encounter does damage; the higher-health hero is hit.
        assertSame(high, res.log.first().hero)
    }

    @Test
    fun barbarianHalvesOnlyHisOwnHits() {
        // Barbarian: self-damage multiplier 0.5, rounded up (data-driven).
        val barb = Hero(
            id = "hero_barbarian", name = "Barbarian", preferredBait = Bait.GLORY,
            startingHp = 8, selfDamageMultiplier = 0.5
        )
        // 5 damage, halved rounded up => 3.
        assertEquals(3, HeroAbility.damageTaken(barb, room(5), listOf(barb)))
    }

    @Test
    fun clericAuraProtectsWhoeverIsHitFromUndead() {
        // Cleric: party-wide undead-bait reduction of 4 (data-driven).
        val cleric = Hero(
            id = "hero_cleric", name = "Cleric", preferredBait = Bait.UNDEAD, startingHp = 5,
            partyDamageReduction = 4, damageBaitFilter = Bait.UNDEAD
        )
        val ally = Hero(id = "ally", name = "Ally", preferredBait = Bait.GLORY, startingHp = 5)
        val undeadRoom = room(5, type = "Basic Monster", bait = mapOf("undead" to 1))
        // Cleric alive => ally takes 5 - 4 = 1.
        assertEquals(1, HeroAbility.damageTaken(ally, undeadRoom, listOf(cleric, ally)))
        // Cleric not in the alive list => no protection.
        assertEquals(5, HeroAbility.damageTaken(ally, undeadRoom, listOf(ally)))
    }

    @Test
    fun enticementSumsPreferredBaitAcrossMembers() {
        val d = Dungeon(boss(4, bait = mapOf("glory" to 1)))
        d.placeRoom(0, room(2, bait = mapOf("glory" to 1)))
        assertEquals(2, BaitCounter.enticement(d, Bait.GLORY))

        val g1 = Hero(id = "g1", name = "G1", preferredBait = Bait.GLORY, startingHp = 5)
        val g2 = Hero(id = "g2", name = "G2", preferredBait = Bait.GLORY, startingHp = 5)
        // Two glory members => 2 + 2 = 4.
        assertEquals(4, EnticePhase.enticement(d, Party(listOf(g1, g2))))
    }

    @Test
    fun restoreSlotsReversesAPlacement() {
        // Undo-placement snapshots the 5 slots, then restores them to drop a build.
        val d = dungeon(0, listOf(2, 3)) // slots 0,1 occupied
        val snapshot = d.snapshotSlots()
        d.placeRoom(2, room(9)) // place a new room in slot 2
        assertEquals(listOf(2, 3, 9), d.rooms.map { it.damage })
        d.restoreSlots(snapshot)
        assertEquals(listOf(2, 3), d.rooms.map { it.damage }) // back to before the placement
    }

    @Test
    fun scoreboardScoresAndEliminates() {
        val p = Player("P")
        p.points = 6; p.wounds = 2
        assertEquals(2, Scoreboard.score(p)) // 6 - 2*2
        assertTrue(!Scoreboard.eliminated(p))
        p.wounds = 5
        assertTrue(Scoreboard.eliminated(p))
    }
}
