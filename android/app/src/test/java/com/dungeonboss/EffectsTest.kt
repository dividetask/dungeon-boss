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
 * Fidelity checks for the declarative effects, the boss points bonus, the
 * crawl modifiers, and loading — mirroring the Ruby reference (webapp/test).
 */
class EffectsTest {

    private fun hero(health: Int, bait: Bait = Bait.GLORY, id: String = "h") =
        Hero(id = id, name = id, preferredBait = bait, startingHp = health)

    private fun room(
        damage: Int,
        type: String = "Basic Trap",
        bait: Map<String, Int> = emptyMap(),
        effect: Map<String, Any?> = emptyMap()
    ) = Room(id = "r", name = "R", type = type, damage = damage, bait = BaitIcons(bait), effect = effect)

    private fun boss(damage: Int, effect: Map<String, Any?> = emptyMap()) =
        Boss(id = "b", name = "Boss", damage = damage, bait = BaitIcons(), effect = effect)

    private fun dungeon(boss: Boss, rooms: List<Room>): Dungeon {
        val d = Dungeon(boss)
        rooms.forEachIndexed { i, r -> d.placeRoom(i, r) } // listed order = left->right (slots 0..)
        return d
    }

    @Test
    fun bossDealsExtraDamagePerPoint() {
        // Vampire: self_damage_per_point 2. With a 0-damage entrance the boss is
        // the only attacker: 4 + 2 * 3 points = 10.
        val d = dungeon(boss(4, mapOf("self_damage_per_point" to 2)), listOf(room(0)))
        val res = PartyCrawlResolver.resolve(Party(listOf(hero(100))), d, bossBonus = 3)
        assertEquals(10, res.log.last().damage)
    }

    @Test
    fun bossRoomBonusFlatToTraps() {
        // Kobold Chieftain style: +2 flat to trap rooms.
        val effect = mapOf("room_bonuses" to listOf(mapOf("match" to mapOf("type" to "trap"), "flat" to 2)))
        val d = dungeon(boss(0, effect), listOf(room(3, type = "Basic Trap")))
        val res = PartyCrawlResolver.resolve(Party(listOf(hero(100))), d)
        assertEquals(5, res.log.first().damage) // 3 + 2
    }

    @Test
    fun roomAuraBoostsOtherMatchingRooms() {
        // Trap Makers Workshop: +2 to OTHER trap rooms (never itself / the boss).
        val trapMakers = room(0, type = "Creature",
            effect = mapOf("room_auras" to listOf(mapOf("match" to mapOf("type" to "trap"), "flat" to 2))))
        val otherTrap = room(3, type = "Basic Trap")
        val d = dungeon(boss(0), listOf(trapMakers, otherTrap))
        val res = PartyCrawlResolver.resolve(Party(listOf(hero(100))), d)
        // Only the trap room deals damage; it gains +2 from the aura.
        assertEquals(5, res.log.first().damage)
    }

    @Test
    fun growOnDeathIncrementsPermanently() {
        val growRoom = room(4, type = "Creature", effect = mapOf("grows_on_death" to true))
        val d = dungeon(boss(0), listOf(growRoom))
        // The lone hero dies in the grow room; the placed room gains +1.
        val res = PartyCrawlResolver.resolve(Party(listOf(hero(4))), d)
        assertEquals(1, res.deaths)
        assertEquals(1, d.rooms[0].grow)
    }

    @Test
    fun dryRunPredictsDeathsWithoutGrowing() {
        val growRoom = room(4, type = "Creature", effect = mapOf("grows_on_death" to true))
        val d = dungeon(boss(0), listOf(growRoom))
        val res = PartyCrawlResolver.resolve(Party(listOf(hero(4))), d, dryRun = true)
        assertEquals(1, res.deaths)      // the preview still reports the death
        assertEquals(0, d.rooms[0].grow) // but the room is not permanently grown
    }

    @Test
    fun poisonedSpikesDealDelayedDamageNextRoom() {
        val spikes = room(2, effect = mapOf("next_room_damage" to 8))
        val d = dungeon(boss(0), listOf(spikes, room(0)))
        val res = PartyCrawlResolver.resolve(Party(listOf(hero(100))), d)
        // 2 in the spike room, then 8 (unreducible) as it enters the next room.
        assertTrue(res.log.any { it.damage == 8 })
        assertEquals(100 - 2 - 8, res.log.last().healthAfter)
    }

    @Test
    fun poisonGasTicksEveryLaterEncounter() {
        val gas = room(2, effect = mapOf("poisons_on_hit" to true))
        // Encounters after the gas: one room and the boss — two poison ticks.
        val d = dungeon(boss(0), listOf(gas, room(0)))
        val res = PartyCrawlResolver.resolve(Party(listOf(hero(100))), d)
        assertEquals(2, res.log.count { it.damage == 1 })
    }

    @Test
    fun overkillDamageSpillsToTheNextHero() {
        // A 10-damage room with three low-health heroes: the leftover after each
        // kill carries to the next, so one room wipes the whole party.
        val a = hero(4, id = "a")
        val b = hero(4, id = "b")
        val c = hero(4, id = "c")
        val d = dungeon(boss(0), listOf(room(10)))
        val res = PartyCrawlResolver.resolve(Party(listOf(a, b, c)), d)
        assertEquals(3, res.deaths)
        assertTrue(res.survivors.isEmpty())
    }

    @Test
    fun overkillStopsWhenDamageIsExhausted() {
        // 7 damage, two 4-health heroes: the first dies (4), 3 spills to the
        // second who survives at 1. No third hero is needed.
        val a = hero(4, id = "a")
        val b = hero(4, id = "b")
        val d = dungeon(boss(0), listOf(room(7)))
        val res = PartyCrawlResolver.resolve(Party(listOf(a, b)), d)
        assertEquals(1, res.deaths)
        assertEquals(1, res.survivors.size)
        assertEquals(1, res.log.last().healthAfter)
    }

    @Test
    fun poisonGasDamagesEveryHeroAndPoisonsThem() {
        // damages_all: the 2-damage gas hits BOTH heroes, and each then takes a
        // poison tick in the following room (the boss).
        val gas = room(2, type = "Trap", effect = mapOf("damages_all" to true, "poisons_on_hit" to true))
        val d = dungeon(boss(0), listOf(gas))
        val a = hero(100, id = "a")
        val b = hero(100, id = "b")
        val res = PartyCrawlResolver.resolve(Party(listOf(a, b)), d)
        // Two heroes hit for 2 in the gas room.
        assertEquals(2, res.log.count { it.roomIndex == 0 && it.damage == 2 })
        // Two poison ticks (one per hero) when entering the boss.
        assertEquals(2, res.log.count { it.damage == 1 })
    }

    @Test
    fun rogueProtectsThePartyFromPoisonGas() {
        // Rogue reduces trap damage by 2 party-wide, so the 2-damage gas deals 0
        // to everyone — and an undamaged hero is never poisoned.
        val gas = room(2, type = "Trap", effect = mapOf("damages_all" to true, "poisons_on_hit" to true))
        val d = dungeon(boss(0), listOf(gas))
        // Rogue: party-wide trap reduction of 2 (data-driven).
        val rogue = Hero(
            id = "hero_rogue", name = "Rogue", preferredBait = Bait.RICHES, startingHp = 100,
            partyDamageReduction = 2, damageRoomTypeFilter = "trap"
        )
        val ally = hero(100, id = "ally")
        val res = PartyCrawlResolver.resolve(Party(listOf(rogue, ally)), d)
        assertTrue(res.log.none { it.damage > 0 }) // nobody damaged, nobody poisoned
    }

    @Test
    fun antimagicPartyHitsOnlyArcaneHeroes() {
        val antimagic = room(0, type = "Trap",
            effect = mapOf("party_hits" to listOf(mapOf("match" to mapOf("preferred_bait" to "arcane"), "amount" to 4))))
        val mage = hero(4, Bait.ARCANE, id = "hero_mage")
        val fighter = hero(8, Bait.GLORY, id = "f")
        val d = dungeon(boss(0), listOf(antimagic))
        val res = PartyCrawlResolver.resolve(Party(listOf(mage, fighter)), d)
        assertTrue(res.deadHeroes.contains(mage))
        assertFalse(res.deadHeroes.contains(fighter))
    }

    @Test
    fun crawlModifiersCopyIsIndependent() {
        // Undoing an ability restores a pre-play snapshot, so the copy must not
        // change when the live modifiers are mutated afterward.
        val mods = CrawlModifiers().apply { addDamage(0, 3); unreducibleMark(1) }
        val snap = mods.copy()
        mods.addDamage(0, 5)
        mods.zero(2)
        assertEquals(3, snap.bonus(0))   // snapshot keeps the pre-play bonus
        assertEquals(8, mods.bonus(0))   // live modifiers moved on
        assertTrue(snap.reducible(2))    // and never saw the later Sabotage
        assertFalse(snap.isZero(2))
    }

    @Test
    fun modifierAddDamageRaisesOneRoom() {
        val mods = CrawlModifiers().apply { addDamage(0, 3) }
        val d = dungeon(boss(0), listOf(room(2)))
        val res = PartyCrawlResolver.resolve(Party(listOf(hero(100))), d, modifiers = mods)
        assertEquals(5, res.log.first().damage)
    }

    @Test
    fun modifierZeroNegatesARoom() {
        val mods = CrawlModifiers().apply { zero(0) }
        val d = dungeon(boss(0), listOf(room(9), room(0)))
        val res = PartyCrawlResolver.resolve(Party(listOf(hero(100))), d, modifiers = mods)
        // The zeroed room deals no damage, so it never lands a hit.
        assertTrue(res.log.isEmpty())
    }

    @Test
    fun modifierUnreducibleBypassesBarbarianHalving() {
        // Barbarian: self-damage multiplier 0.5 (rounded up), data-driven.
        val barb = Hero(
            id = "hero_barbarian", name = "Barbarian", preferredBait = Bait.GLORY,
            startingHp = 20, selfDamageMultiplier = 0.5
        )
        val d = dungeon(boss(0), listOf(room(6)))
        val plain = PartyCrawlResolver.resolve(Party(listOf(barb)), d)
        assertEquals(3, plain.log.first().damage) // halved rounded up

        val barb2 = Hero(
            id = "hero_barbarian", name = "Barbarian", preferredBait = Bait.GLORY,
            startingHp = 20, selfDamageMultiplier = 0.5
        )
        val d2 = dungeon(boss(0), listOf(room(6)))
        val mods = CrawlModifiers().apply { unreducibleMark(0) }
        val full = PartyCrawlResolver.resolve(Party(listOf(barb2)), d2, modifiers = mods)
        assertEquals(6, full.log.first().damage)
    }

    @Test
    fun retreatStopsBeforeTheTargetRoom() {
        val d = dungeon(boss(3), listOf(room(3), room(3)))
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
    fun libraryLoadsAdvancedRoomsAndEffectMaps() {
        val data = mapOf(
            "advanced_rooms" to listOf(
                mapOf(
                    "id" to "adv_x", "name" to "X", "type" to "Trap", "damage" to 2,
                    "bait" to mapOf("riches" to 1),
                    "effect" to mapOf("poisons_on_hit" to true)
                )
            )
        )
        val lib = CardLibrary.from(data)
        assertEquals(1, lib.advancedRooms.size)
        assertTrue(lib.advancedRooms.first().advanced)
        assertEquals(true, lib.advancedRooms.first().effect["poisons_on_hit"])
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
