package com.dungeonboss

import com.dungeonboss.data.CardLibrary
import com.dungeonboss.game.AbilityEffect
import com.dungeonboss.game.CrawlModifiers
import com.dungeonboss.game.Dungeon
import com.dungeonboss.game.Party
import com.dungeonboss.game.PartyCrawlResolver
import com.dungeonboss.model.AbilityCard
import com.dungeonboss.model.Bait
import com.dungeonboss.model.BaitIcons
import com.dungeonboss.model.Boss
import com.dungeonboss.model.Hero
import com.dungeonboss.model.Room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fidelity checks for the flat field schema: damage channels (lead/all/rear) with
 * per-level increments, the three-way room resist, unified poison (and Maze's
 * triple tick), the damage filter, boss/aura bonuses, draw-on-death, and the
 * per-crawl modifiers.
 */
class EffectsTest {

    private fun hero(health: Int, bait: Bait = Bait.GLORY, id: String = "h") =
        Hero(id = id, name = id, preferredBait = bait, startingHp = health)

    private fun room(
        lead: Int = 0,
        type: String = "trap",
        bait: Map<String, Int> = emptyMap(),
        leadInc: Double = 0.0,
        all: Int = 0,
        allInc: Double = 0.0,
        rear: Int = 0,
        rearInc: Double = 0.0,
        filter: String? = null,
        resist: Boolean? = null,
        poison: Int = 0,
        poisonPersists: Boolean = false,
        poisonTicks: Int = 1,
        grows: Boolean = false,
        draw: Boolean = false,
        aura: Map<String, Any?>? = null,
        discardAll: Int = 0
    ) = Room(
        id = "r", name = "R", type = type, bait = BaitIcons(bait),
        leadBase = lead, leadIncrement = leadInc,
        allBase = all, allIncrement = allInc,
        rearBase = rear, rearIncrement = rearInc,
        damageFilter = filter, roomResist = resist,
        poisonDamage = poison, poisonPersists = poisonPersists, poisonTicks = poisonTicks,
        growsOnDeath = grows, drawOnDeath = draw, roomAura = aura, discardAllDamage = discardAll
    )

    private fun boss(damage: Int, effect: Map<String, Any?> = emptyMap()) =
        Boss(id = "b", name = "Boss", damage = damage, bait = BaitIcons(), effect = effect)

    private fun dungeon(boss: Boss, rooms: List<Room>): Dungeon {
        val d = Dungeon(boss)
        rooms.forEachIndexed { i, r -> d.placeRoom(i, r) } // listed order = left->right (slots 0..)
        return d
    }

    @Test
    fun bossDealsExtraDamagePerPoint() {
        // Vampire: self_damage_per_point 2. 4 + 2 * 3 points = 10.
        val d = dungeon(boss(4, mapOf("self_damage_per_point" to 2)), listOf(room(lead = 0)))
        val res = PartyCrawlResolver.resolve(Party(listOf(hero(100))), d, bossBonus = 3)
        assertEquals(10, res.log.last().damage)
    }

    @Test
    fun bossRoomBonusFlatToTraps() {
        val effect = mapOf("room_bonuses" to listOf(mapOf("match" to mapOf("type" to "trap"), "flat" to 2)))
        val d = dungeon(boss(0, effect), listOf(room(lead = 3, type = "trap")))
        val res = PartyCrawlResolver.resolve(Party(listOf(hero(100))), d)
        assertEquals(5, res.log.first().damage) // 3 + 2
    }

    @Test
    fun roomAuraBoostsOtherMatchingRooms() {
        // Trap Maker's Workshop: +2 to OTHER trap rooms (never itself / the boss).
        val trapMakers = room(lead = 0, type = "monster",
            aura = mapOf("match" to mapOf("type" to "trap"), "amount" to 2))
        val otherTrap = room(lead = 3, type = "trap")
        val d = dungeon(boss(0), listOf(trapMakers, otherTrap))
        val res = PartyCrawlResolver.resolve(Party(listOf(hero(100))), d)
        assertEquals(5, res.log.first().damage) // 3 + 2 aura
    }

    @Test
    fun growOnDeathRaisesRoomLevel() {
        val growRoom = room(lead = 4, type = "monster", grows = true, leadInc = 1.0)
        val d = dungeon(boss(0), listOf(growRoom))
        val res = PartyCrawlResolver.resolve(Party(listOf(hero(4))), d)
        assertEquals(1, res.deaths)
        assertEquals(1, d.rooms[0].level) // one death -> +1 level (permanent)
    }

    @Test
    fun levelScalesDamageByIncrement() {
        // A level-2 room with lead 4 (+2/level) deals 4 + 2*2 = 8.
        val placed = com.dungeonboss.model.PlacedRoom(room(lead = 4, type = "monster", leadInc = 2.0))
        placed.level = 2
        assertEquals(8, placed.leadDamage)
    }

    @Test
    fun dryRunPredictsDeathsWithoutLevelling() {
        val growRoom = room(lead = 4, type = "monster", grows = true, leadInc = 1.0)
        val d = dungeon(boss(0), listOf(growRoom))
        val res = PartyCrawlResolver.resolve(Party(listOf(hero(4))), d, dryRun = true)
        assertEquals(1, res.deaths)      // the preview still reports the death
        assertEquals(0, d.rooms[0].level) // but the room is not permanently levelled
    }

    @Test
    fun poisonNonPersistDealsDelayedDamageNextRoomOnly() {
        // Floor Spike: lead 2, then poison 8 once in the very next room.
        val spikes = room(lead = 2, poison = 8, poisonPersists = false)
        val d = dungeon(boss(0), listOf(spikes, room(lead = 0)))
        val res = PartyCrawlResolver.resolve(Party(listOf(hero(100))), d)
        assertTrue(res.log.any { it.damage == 8 })
        assertEquals(100 - 2 - 8, res.log.last().healthAfter)
    }

    @Test
    fun poisonPersistsEveryLaterEncounter() {
        // Poison Gas: lead 2, then poison 1 in every later room.
        val gas = room(lead = 2, poison = 1, poisonPersists = true)
        val d = dungeon(boss(0), listOf(gas, room(lead = 0)))
        val res = PartyCrawlResolver.resolve(Party(listOf(hero(100))), d)
        assertEquals(2, res.log.count { it.damage == 1 }) // ticks at the next room and the boss
    }

    @Test
    fun mazeTriplesPoisonTicks() {
        // A poison source then a Maze (poison_ticks 3): poison resolves 3x at the Maze.
        val gas = room(lead = 2, poison = 1, poisonPersists = true)
        val maze = room(all = 4, type = "trap", poisonTicks = 3)
        val d = dungeon(boss(0), listOf(gas, maze))
        val res = PartyCrawlResolver.resolve(Party(listOf(hero(100))), d)
        assertEquals(3, res.log.count { it.roomIndex == 1 && it.damage == 1 })
    }

    @Test
    fun damageAllHitsEveryHeroAndPoisonsThem() {
        val gas = room(type = "trap", all = 2, poison = 1, poisonPersists = true)
        val d = dungeon(boss(0), listOf(gas))
        val a = hero(100, id = "a")
        val b = hero(100, id = "b")
        val res = PartyCrawlResolver.resolve(Party(listOf(a, b)), d)
        assertEquals(2, res.log.count { it.roomIndex == 0 && it.damage == 2 }) // both hit
        assertEquals(2, res.log.count { it.damage == 1 })                      // both poisoned -> boss tick
    }

    @Test
    fun rogueProtectsThePartyFromAllDamageTrap() {
        val gas = room(type = "trap", all = 2, poison = 1, poisonPersists = true)
        val d = dungeon(boss(0), listOf(gas))
        val rogue = Hero(
            id = "hero_rogue", name = "Rogue", preferredBait = Bait.RICHES, startingHp = 100,
            partyDamageReduction = 2, damageRoomTypeFilter = "trap"
        )
        val ally = hero(100, id = "ally")
        val res = PartyCrawlResolver.resolve(Party(listOf(rogue, ally)), d)
        assertTrue(res.log.none { it.damage > 0 }) // reduced to 0; nobody poisoned
    }

    @Test
    fun damageFilterAndResistHitsOnlyMatchingClassUnreducibly() {
        // Antimagic Room: damage_all 4, filter mage, cannot be reduced.
        val antimagic = room(type = "trap", all = 4, filter = "mage", resist = true)
        val mage = Hero(id = "hero_mage", name = "Mage", preferredBait = Bait.ARCANE, startingHp = 4)
        val fighter = hero(8, Bait.GLORY, id = "f")
        val d = dungeon(boss(0), listOf(antimagic))
        val res = PartyCrawlResolver.resolve(Party(listOf(mage, fighter)), d)
        assertTrue(res.deadHeroes.contains(mage))
        assertFalse(res.deadHeroes.contains(fighter))
    }

    @Test
    fun resistFalseBlocksHalvingButNotAuras() {
        // "Cannot be halved": the Barbarian's self multiplier is skipped.
        val barb = Hero(
            id = "hero_barbarian", name = "Barbarian", preferredBait = Bait.GLORY,
            startingHp = 20, selfDamageMultiplier = 0.5
        )
        val halvable = dungeon(boss(0), listOf(room(lead = 6)))
        assertEquals(3, PartyCrawlResolver.resolve(Party(listOf(barb)), halvable).log.first().damage)

        val barb2 = Hero(
            id = "hero_barbarian", name = "Barbarian", preferredBait = Bait.GLORY,
            startingHp = 20, selfDamageMultiplier = 0.5
        )
        val unhalvable = dungeon(boss(0), listOf(room(lead = 6, resist = false)))
        assertEquals(6, PartyCrawlResolver.resolve(Party(listOf(barb2)), unhalvable).log.first().damage)
    }

    @Test
    fun drawOnDeathEarnsOneRoomAndOneAbilityPerDeath() {
        val sacrifice = room(lead = 4, type = "trap", draw = true)
        val d = dungeon(boss(0), listOf(sacrifice))
        val res = PartyCrawlResolver.resolve(Party(listOf(hero(4))), d)
        assertEquals(1, res.deaths)
        assertEquals(1, res.draws["room"])
        assertEquals(1, res.draws["ability"])
    }

    @Test
    fun overkillDamageSpillsToTheNextHero() {
        // 12 lead damage, three 4-health heroes: the leftover after each kill
        // carries to the next, so one room wipes the whole party.
        val a = hero(4, id = "a"); val b = hero(4, id = "b"); val c = hero(4, id = "c")
        val d = dungeon(boss(0), listOf(room(lead = 12)))
        val res = PartyCrawlResolver.resolve(Party(listOf(a, b, c)), d)
        assertEquals(3, res.deaths)
        assertTrue(res.survivors.isEmpty())
    }

    @Test
    fun overkillStopsWhenDamageIsExhausted() {
        // 7 lead damage, two 4-health heroes: first dies (4), 3 spills to the
        // second who survives at 1.
        val a = hero(4, id = "a"); val b = hero(4, id = "b")
        val d = dungeon(boss(0), listOf(room(lead = 7)))
        val res = PartyCrawlResolver.resolve(Party(listOf(a, b)), d)
        assertEquals(1, res.deaths)
        assertEquals(1, res.survivors.size)
        assertEquals(1, res.log.last().healthAfter)
    }

    @Test
    fun crawlModifiersCopyIsIndependent() {
        val mods = CrawlModifiers().apply { addDamage(0, 3); unreducibleMark(1) }
        val snap = mods.copy()
        mods.addDamage(0, 5)
        mods.zero(2)
        assertEquals(3, snap.bonus(0))
        assertEquals(8, mods.bonus(0))
        assertTrue(snap.reducible(2))
        assertFalse(snap.isZero(2))
    }

    @Test
    fun modifierAddDamageRaisesOneRoom() {
        val mods = CrawlModifiers().apply { addDamage(0, 3) }
        val d = dungeon(boss(0), listOf(room(lead = 2)))
        val res = PartyCrawlResolver.resolve(Party(listOf(hero(100))), d, modifiers = mods)
        assertEquals(5, res.log.first().damage)
    }

    @Test
    fun modifierZeroNegatesARoom() {
        val mods = CrawlModifiers().apply { zero(0) }
        val d = dungeon(boss(0), listOf(room(lead = 9), room(lead = 0)))
        val res = PartyCrawlResolver.resolve(Party(listOf(hero(100))), d, modifiers = mods)
        assertTrue(res.log.isEmpty())
    }

    @Test
    fun modifierUnreducibleBypassesBarbarianHalving() {
        val barb = Hero(
            id = "hero_barbarian", name = "Barbarian", preferredBait = Bait.GLORY,
            startingHp = 20, selfDamageMultiplier = 0.5
        )
        val d = dungeon(boss(0), listOf(room(lead = 6)))
        assertEquals(3, PartyCrawlResolver.resolve(Party(listOf(barb)), d).log.first().damage)

        val barb2 = Hero(
            id = "hero_barbarian", name = "Barbarian", preferredBait = Bait.GLORY,
            startingHp = 20, selfDamageMultiplier = 0.5
        )
        val d2 = dungeon(boss(0), listOf(room(lead = 6)))
        val mods = CrawlModifiers().apply { unreducibleMark(0) }
        assertEquals(6, PartyCrawlResolver.resolve(Party(listOf(barb2)), d2, modifiers = mods).log.first().damage)
    }

    @Test
    fun retreatStopsBeforeTheTargetRoom() {
        val d = dungeon(boss(3), listOf(room(lead = 3), room(lead = 3)))
        val mods = CrawlModifiers().apply { retreat(1) } // turn back at index 1
        val res = PartyCrawlResolver.resolve(Party(listOf(hero(100))), d, modifiers = mods)
        assertEquals(1, res.log.size) // only the entrance resolved
        assertTrue(mods.retreating())
    }

    @Test
    fun abilityEffectParsing() {
        val reinforce = AbilityEffect.forCard(AbilityCard("a", "A", effect = mapOf("add_damage" to 4)))
        assertEquals(4, reinforce.addDamage)
        assertTrue(reinforce.targetsRoom())

        val blueprints = AbilityEffect.forCard(AbilityCard("b", "B", effect = mapOf("draw_rooms" to 2)))
        assertEquals(2, blueprints.drawRooms)
        assertFalse(blueprints.targetsRoom())

        val retreat = AbilityEffect.forCard(AbilityCard("c", "C", effect = mapOf("retreat" to true)))
        assertTrue(retreat.retreat)
        assertTrue(retreat.targetsRoom())
    }

    @Test
    fun libraryLoadsAdvancedRoomsAndFields() {
        val data = mapOf(
            "advanced_rooms" to listOf(
                mapOf(
                    "id" to "adv_x", "name" to "X", "type" to "trap", "advanced" to true,
                    "bait" to mapOf("riches" to 1),
                    "lead_damage" to 2, "poison_damage" to 1, "poison_persists" to true
                )
            )
        )
        val lib = CardLibrary.from(data)
        assertEquals(1, lib.advancedRooms.size)
        assertTrue(lib.advancedRooms.first().advanced)
        assertEquals(2, lib.advancedRooms.first().leadBase)
        assertEquals(1, lib.advancedRooms.first().poisonDamage)
        assertTrue(lib.advancedRooms.first().poisonPersists)
    }

    @Test
    fun baitIconsShareAndContain() {
        val arcane2 = BaitIcons(mapOf("arcane" to 2))
        val arcaneRiches = BaitIcons(mapOf("arcane" to 1, "riches" to 1))
        assertTrue(arcaneRiches.shares(arcane2))
        assertTrue(arcaneRiches.contains(BaitIcons(mapOf("arcane" to 1))))
        assertFalse(arcaneRiches.contains(arcane2)) // only one arcane icon
    }
}
