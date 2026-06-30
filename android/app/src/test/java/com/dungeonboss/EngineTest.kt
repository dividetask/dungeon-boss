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
 * Engine fidelity checks for the flat field schema: targeting (lead/rear),
 * hero abilities (party reduction + self multiplier, with the resist modes),
 * bait/courage, and scoring.
 */
class EngineTest {

    /** A lead-damage room (the common case). */
    private fun room(damage: Int, type: String = "trap", bait: Map<String, Int> = emptyMap()) =
        Room(id = "r", name = "R", type = type, bait = BaitIcons(bait), leadBase = damage)

    private fun boss(damage: Int, bait: Map<String, Int> = emptyMap()) =
        Boss(id = "b", name = "Boss", damage = damage, bait = BaitIcons(bait))

    private fun dungeon(bossDamage: Int, roomDamages: List<Int>): Dungeon {
        val d = Dungeon(boss(bossDamage))
        roomDamages.forEachIndexed { i, dmg -> d.placeRoom(i, room(dmg)) }
        return d
    }

    @Test
    fun partyContinuesAfterADeathUntilAllDie() {
        val a = Hero(id = "a", name = "A", preferredBait = Bait.GLORY, startingHp = 4)
        val b = Hero(id = "b", name = "B", preferredBait = Bait.GLORY, startingHp = 4)
        // entrance lead 4 (kills A exactly), boss lead 4 (kills B)
        val res = PartyCrawlResolver.resolve(Party(listOf(a, b)), dungeon(4, listOf(4)))

        assertEquals(2, res.log.size)
        assertEquals(listOf(a, b), res.log.map { it.hero }) // B is hit after A dies
        assertEquals(2, res.deaths)
        assertTrue(res.survivors.isEmpty())
    }

    @Test
    fun twoHeroesWithTheSameIdAreIndependent() {
        val m1 = Hero(id = "hero_mage", name = "Mage", preferredBait = Bait.ARCANE, startingHp = 4)
        val m2 = Hero(id = "hero_mage", name = "Mage", preferredBait = Bait.ARCANE, startingHp = 4)
        val res = PartyCrawlResolver.resolve(Party(listOf(m1, m2)), dungeon(4, listOf(4)))

        assertEquals(2, res.deaths)
        assertEquals(listOf(m1, m2), res.log.map { it.hero })
    }

    @Test
    fun leadHitsTheHighestCurrentHealthMember() {
        val low = Hero(id = "low", name = "Low", preferredBait = Bait.GLORY, startingHp = 3)
        val high = Hero(id = "high", name = "High", preferredBait = Bait.GLORY, startingHp = 8)
        val res = PartyCrawlResolver.resolve(Party(listOf(low, high)), dungeon(0, listOf(2)))
        assertSame(high, res.log.first().hero)
    }

    @Test
    fun rearHitsTheMostInjuredMember() {
        // Damage Rear targets the lowest-HP hero (cascading upward).
        val hurt = Hero(id = "hurt", name = "Hurt", preferredBait = Bait.GLORY, startingHp = 4)
        val fresh = Hero(id = "fresh", name = "Fresh", preferredBait = Bait.GLORY, startingHp = 10)
        val d = Dungeon(boss(0))
        d.placeRoom(0, Room(id = "bt", name = "BT", type = "trap", bait = BaitIcons(), rearBase = 5))
        val res = PartyCrawlResolver.resolve(Party(listOf(fresh, hurt)), d)
        // The most-injured (Hurt, 4 HP) is struck first; overkill (1) spills to Fresh.
        assertSame(hurt, res.log.first().hero)
        assertTrue(res.deadHeroes.contains(hurt))
        assertTrue(res.survivors.contains(fresh))
    }

    @Test
    fun barbarianHalvesOnlyHisOwnHits() {
        val barb = Hero(
            id = "hero_barbarian", name = "Barbarian", preferredBait = Bait.GLORY,
            startingHp = 8, selfDamageMultiplier = 0.5
        )
        // 5 damage, halved rounded up => 3.
        assertEquals(3, HeroAbility.damageTaken(barb, room(5), listOf(barb)))
    }

    @Test
    fun clericAuraProtectsWhoeverIsHitFromUndead() {
        val cleric = Hero(
            id = "hero_cleric", name = "Cleric", preferredBait = Bait.UNDEAD, startingHp = 5,
            partyDamageReduction = 4, damageBaitFilter = Bait.UNDEAD
        )
        val ally = Hero(id = "ally", name = "Ally", preferredBait = Bait.GLORY, startingHp = 5)
        val undeadRoom = room(5, type = "monster", bait = mapOf("undead" to 1))
        assertEquals(1, HeroAbility.damageTaken(ally, undeadRoom, listOf(cleric, ally))) // 5 - 4
        assertEquals(5, HeroAbility.damageTaken(ally, undeadRoom, listOf(ally)))          // no cleric
    }

    @Test
    fun enticementSumsPreferredBaitAcrossMembers() {
        val d = Dungeon(boss(4, bait = mapOf("glory" to 1)))
        d.placeRoom(0, room(2, bait = mapOf("glory" to 1)))
        assertEquals(2, BaitCounter.enticement(d, Bait.GLORY))

        val g1 = Hero(id = "g1", name = "G1", preferredBait = Bait.GLORY, startingHp = 5)
        val g2 = Hero(id = "g2", name = "G2", preferredBait = Bait.GLORY, startingHp = 5)
        assertEquals(4, EnticePhase.enticement(d, Party(listOf(g1, g2)))) // 2 + 2
    }

    @Test
    fun restoreSlotsReversesAPlacement() {
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
