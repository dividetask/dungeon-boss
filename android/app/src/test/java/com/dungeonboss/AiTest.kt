package com.dungeonboss

import com.dungeonboss.data.CardLibrary
import com.dungeonboss.game.AbilityChooser
import com.dungeonboss.game.AbilityPlay
import com.dungeonboss.game.AbilityPolicy
import com.dungeonboss.game.CrawlModifiers
import com.dungeonboss.game.Decision
import com.dungeonboss.game.DecisionKind
import com.dungeonboss.game.Dungeon
import com.dungeonboss.game.DungeonForecast
import com.dungeonboss.game.Game
import com.dungeonboss.game.LogicAgent
import com.dungeonboss.game.Party
import com.dungeonboss.game.Player
import com.dungeonboss.game.PreCrawlContext
import com.dungeonboss.model.AbilityCard
import com.dungeonboss.model.Bait
import com.dungeonboss.model.BaitIcons
import com.dungeonboss.model.Boss
import com.dungeonboss.model.Hero
import com.dungeonboss.model.Room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/**
 * The AI (LogicAgent + DungeonForecast) heuristics, adapted to the flat card
 * schema: candidates are scored by a tie-break chain, and build moves are weighed
 * by a dry-run crawl forecast against the town.
 */
class AiTest {

    private fun boss(id: String, damage: Int, bait: Map<String, Int> = emptyMap()) =
        Boss(id = id, name = id, damage = damage, bait = BaitIcons(bait))

    private fun room(id: String, damage: Int, bait: Map<String, Int> = emptyMap()) =
        Room(id = id, name = id, type = "trap", bait = BaitIcons(bait), leadBase = damage)

    private fun hero(id: String, health: Int) =
        Hero(id = id, name = id, preferredBait = Bait.GLORY, startingHp = health)

    private fun agent(logic: Map<String, List<Map<String, Any?>>>) = LogicAgent(logic, Random(1))

    // --- DungeonForecast ---------------------------------------------------

    private fun dungeon(bossDamage: Int, roomDamages: List<Int>): Dungeon {
        val d = Dungeon(boss("b", bossDamage))
        roomDamages.forEachIndexed { i, dmg -> d.placeRoom(i, room("r$i", dmg)) } // slots 0.. = left->right
        return d
    }

    @Test
    fun forecastCountsKillsWoundsAndDamage() {
        // entrance deals 5, boss 0. A 4-health hero dies; a 6-health hero lives.
        val parties = listOf(Party(listOf(hero("a", 4))), Party(listOf(hero("b", 6))))
        val outcome = DungeonForecast.run(dungeon(0, listOf(5)), parties)

        assertEquals(1, outcome.kills)          // the 4-health hero
        assertEquals(1, outcome.wounds)         // the 6-health hero's party survives
        assertEquals(10, outcome.totalDamage)   // 5 to each hero
        assertEquals(5.0, outcome.avgDamage, 0.0001)
    }

    @Test
    fun forecastOfAnEmptyTownIsZero() {
        val outcome = DungeonForecast.run(dungeon(3, listOf(3)), emptyList())
        assertEquals(0, outcome.kills)
        assertEquals(0, outcome.wounds)
        assertEquals(0.0, outcome.avgDamage, 0.0001)
    }

    @Test
    fun cloneIsAnIsolatedCopy() {
        val original = dungeon(0, listOf(5))
        val copy = DungeonForecast.clone(original)
        copy.rooms[0].level = 3 // mutate the clone only

        assertEquals(1, copy.rooms.size)
        assertEquals(0, original.rooms[0].level)  // original untouched
        assertEquals(3, copy.rooms[0].level)
    }

    // --- LogicAgent: static comparators ------------------------------------

    @Test
    fun chooseBossPrefersHighestDamage() {
        val logic = mapOf<String, List<Map<String, Any?>>>(
            "choose_boss" to listOf(mapOf("prefer" to "highest_damage"))
        )
        val decision = Decision(DecisionKind.CHOOSE_BOSS, Player("X"), listOf(boss("weak", 2), boss("strong", 7)))

        assertEquals(Pair<String?, Any?>("strong", null), agent(logic).choose(decision))
    }

    @Test
    fun discardPrefersLowestDamage() {
        val player = Player("X")
        player.roomHand.add(room("keep", 6))
        player.roomHand.add(room("toss", 1))
        val logic = mapOf<String, List<Map<String, Any?>>>(
            "discard_rooms" to listOf(mapOf("prefer" to "lowest_damage"))
        )
        val decision = Decision(DecisionKind.DISCARD_ROOMS, player, player.roomHand)

        assertEquals("toss", agent(logic).choose(decision).first)
    }

    @Test
    fun tieBreakChainFallsThroughToTheNextRule() {
        // Two bosses tie on damage; the bait-priority rule breaks it toward glory.
        val logic = mapOf<String, List<Map<String, Any?>>>(
            "choose_boss" to listOf(
                mapOf("prefer" to "highest_damage"),
                mapOf("prefer" to listOf("glory", "riches"))
            )
        )
        val decision = Decision(
            DecisionKind.CHOOSE_BOSS, Player("X"),
            listOf(boss("riches_boss", 5, mapOf("riches" to 2)), boss("glory_boss", 5, mapOf("glory" to 1)))
        )

        assertEquals("glory_boss", agent(logic).choose(decision).first)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownComparatorIsRejected() {
        val logic = mapOf<String, List<Map<String, Any?>>>(
            "choose_boss" to listOf(mapOf("prefer" to "nonsense"))
        )
        val decision = Decision(DecisionKind.CHOOSE_BOSS, Player("X"), listOf(boss("a", 1), boss("b", 2)))
        agent(logic).choose(decision)
    }

    // --- LogicAgent: build_room moves + simulation -------------------------

    private fun buildDecision(handDamages: List<Int>): Decision {
        val player = Player("X")
        val d = Dungeon(boss("boss", 0))
        d.placeRoom(0, room("existing", 1))
        player.dungeon = d
        handDamages.forEachIndexed { i, dmg -> player.roomHand.add(room("hand${i}_$dmg", dmg)) }
        return Decision(DecisionKind.BUILD_ROOM, player, player.roomHand, allowSkip = true)
    }

    private fun gameWithTown(vararg heroes: Hero): Game {
        val library = CardLibrary(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val game = Game(library, listOf("A"))
        heroes.forEach { game.addToTown(it) }
        return game
    }

    @Test
    fun buildCanSkipWhenNoLegalMoveExists() {
        // Empty hand and no rules: the only candidate is "build nothing".
        val decision = buildDecision(emptyList())
        assertEquals(Pair<String?, Any?>(null, null), agent(emptyMap()).choose(decision))
    }

    @Test
    fun buildSimulationPrefersTheMoveThatKillsHeroes() {
        val decision = buildDecision(listOf(8, 1)) // one lethal room, one feeble room
        val a = LogicAgent(mapOf("build_room" to listOf(mapOf("prefer" to "most_points"))), Random(1))
        a.attach(gameWithTown(hero("h", 5)))

        val (choiceId, target) = a.choose(decision)

        assertEquals("hand0_8", choiceId) // the 8-damage room is lethal to the 5-health hero
        assertNotNull(target)             // it is placed, not skipped
    }

    @Test
    fun buildWithoutAGameFallsBackToStaticComparators() {
        val decision = buildDecision(listOf(9, 2))
        // No town attached: simulation scores 0 for all, so highest_damage decides.
        val a = LogicAgent(
            mapOf(
                "build_room" to listOf(
                    mapOf("prefer" to "most_points"),
                    mapOf("prefer" to "highest_damage")
                )
            ),
            Random(1)
        )
        assertEquals("hand0_9", a.choose(decision).first)
    }

    // --- pre-crawl ability plays (AbilityChooser + AbilityPolicy) -----------

    private fun ability(id: String, effect: Map<String, Any?>) =
        AbilityCard(id = id, name = id, effect = effect)

    private fun bolster() = ability("ability_extra_damage", mapOf("add_damage" to 4))
    private fun sabotage() = ability("ability_no_damage", mapOf("zero" to true))
    private fun retreat() = ability("ability_return_to_town", mapOf("retreat" to true))
    private fun blueprints() = ability("ability_draw_rooms", mapOf("draw_rooms" to 2))

    private fun policy(winning: Int = 5, cards: Map<String, AbilityPolicy.CardPolicy> = emptyMap()) =
        AbilityPolicy(winning, cards)

    private fun context(actor: Player, owner: Player, party: Party, mods: CrawlModifiers = CrawlModifiers()) =
        PreCrawlContext(actor, owner, party, owner.dungeon!!, owner.points, mods)

    @Test
    fun ownerPlaysBolsterToPreventItsOwnWound() {
        val owner = Player("owner")
        owner.dungeon = dungeon(0, listOf(5)) // one room deals 5, boss 0
        owner.abilityHand.add(bolster())
        val party = Party(listOf(hero("h", 8))) // survives 5 -> the owner would be wounded
        val pol = policy(cards = mapOf("ability_extra_damage" to AbilityPolicy.CardPolicy(preventSelfWound = true)))

        val plays = AbilityChooser.choose(context(owner, owner, party), pol)

        assertEquals(1, plays.size)
        assertEquals(AbilityPlay("ability_extra_damage", 0), plays[0]) // +4 makes the room (5->9) lethal
    }

    @Test
    fun ownerHoldsBolsterWhenNoHeroWouldSurvive() {
        val owner = Player("owner")
        owner.dungeon = dungeon(0, listOf(5))
        owner.abilityHand.add(bolster())
        val party = Party(listOf(hero("h", 3))) // dies to 5 already: no wound to prevent
        val pol = policy(cards = mapOf("ability_extra_damage" to AbilityPolicy.CardPolicy(preventSelfWound = true)))

        assertEquals(emptyList<AbilityPlay>(), AbilityChooser.choose(context(owner, owner, party), pol))
    }

    @Test
    fun opponentRetreatsToDenyThreeOrMorePoints() {
        val owner = Player("owner")
        owner.dungeon = dungeon(0, listOf(10)) // a 10-lead room; overkill cascades along the party
        val actor = Player("bot")
        actor.abilityHand.add(retreat())
        val party = Party(listOf(hero("a", 1), hero("b", 1), hero("c", 1))) // all three die
        val pol = policy(cards = mapOf("ability_return_to_town" to AbilityPolicy.CardPolicy(denyOpponentPoints = 3)))

        val plays = AbilityChooser.choose(context(actor, owner, party), pol)

        assertEquals(1, plays.size)
        assertEquals(AbilityPlay("ability_return_to_town", 0), plays[0]) // turn back at the entrance: denies all 3
    }

    @Test
    fun opponentHoldsRetreatWhenItWouldSaveOnlyTwoPoints() {
        val owner = Player("owner")
        owner.dungeon = dungeon(0, listOf(10))
        val actor = Player("bot")
        actor.abilityHand.add(retreat())
        val party = Party(listOf(hero("a", 1), hero("b", 1))) // only two die: below the 3-point threshold
        val pol = policy(cards = mapOf("ability_return_to_town" to AbilityPolicy.CardPolicy(denyOpponentPoints = 3)))

        assertEquals(emptyList<AbilityPlay>(), AbilityChooser.choose(context(actor, owner, party), pol))
    }

    @Test
    fun opponentSabotagesToWoundAWinningOwner() {
        val owner = Player("owner")
        owner.points = 6                       // winning (>= 5); the boss adds +6
        owner.dungeon = dungeon(0, listOf(12)) // a lethal room
        val actor = Player("bot")
        actor.abilityHand.add(sabotage())
        val party = Party(listOf(hero("h", 10))) // dies to the room, so no wound yet
        val pol = policy(
            winning = 5,
            cards = mapOf("ability_no_damage" to AbilityPolicy.CardPolicy(woundWinningOpponent = true, denyOpponentPoints = 2))
        )

        val plays = AbilityChooser.choose(context(actor, owner, party), pol)

        assertEquals(1, plays.size)
        assertEquals(AbilityPlay("ability_no_damage", 0), plays[0]) // zero the room; hero survives the boss -> wound
    }

    @Test
    fun opponentHoldsSabotageAgainstANonWinningOwner() {
        val owner = Player("owner")
        owner.points = 0                        // not winning
        owner.dungeon = dungeon(0, listOf(12))
        val actor = Player("bot")
        actor.abilityHand.add(sabotage())
        val party = Party(listOf(hero("h", 10)))
        val pol = policy(
            winning = 5,
            cards = mapOf("ability_no_damage" to AbilityPolicy.CardPolicy(woundWinningOpponent = true, denyOpponentPoints = 5))
        )

        assertEquals(emptyList<AbilityPlay>(), AbilityChooser.choose(context(actor, owner, party), pol))
    }

    @Test
    fun playsBlueprintsWhenTheRoomHandIsThin() {
        val owner = Player("owner")
        owner.dungeon = dungeon(0, listOf(3))
        val actor = Player("bot")
        actor.abilityHand.add(blueprints()) // actor's room hand is empty (< 3)
        val party = Party(listOf(hero("h", 3)))
        val pol = policy(cards = mapOf("ability_draw_rooms" to AbilityPolicy.CardPolicy(refillRoomHandBelow = 3)))

        val plays = AbilityChooser.choose(context(actor, owner, party), pol)

        assertEquals(listOf(AbilityPlay("ability_draw_rooms", null)), plays)
    }

    @Test
    fun noConfiguredPolicyPlaysNothing() {
        val owner = Player("owner")
        owner.dungeon = dungeon(0, listOf(5))
        owner.abilityHand.add(bolster())
        val party = Party(listOf(hero("h", 8)))

        assertEquals(emptyList<AbilityPlay>(), AbilityChooser.choose(context(owner, owner, party), AbilityPolicy.NONE))
    }

    @Test
    fun logicAgentPlaysOneAbilityPerPriorityThenPasses() {
        val owner = Player("owner")
        owner.dungeon = dungeon(0, listOf(5))
        owner.abilityHand.add(bolster())
        val party = Party(listOf(hero("h", 8))) // survives 5 -> a wound to prevent
        val pol = policy(cards = mapOf("ability_extra_damage" to AbilityPolicy.CardPolicy(preventSelfWound = true)))
        val agent = LogicAgent(rng = Random(1), abilityPolicy = pol)
        val ctx = PreCrawlContext(owner, owner, party, owner.dungeon!!, owner.points, CrawlModifiers())

        // First priority: play the one beneficial ability.
        assertEquals(AbilityPlay("ability_extra_damage", 0), agent.preCrawlPlay(ctx))
        // Once it is spent, the agent passes (nothing left that clears an objective).
        owner.takeAbilityFromHand("ability_extra_damage")
        assertNull(agent.preCrawlPlay(ctx))
    }

    @Test
    fun logicAgentPassesWhenNoAbilityHelps() {
        val owner = Player("owner")
        owner.dungeon = dungeon(0, listOf(5))
        owner.abilityHand.add(bolster())
        val party = Party(listOf(hero("h", 3))) // dies already: no wound to prevent
        val pol = policy(cards = mapOf("ability_extra_damage" to AbilityPolicy.CardPolicy(preventSelfWound = true)))
        val agent = LogicAgent(rng = Random(1), abilityPolicy = pol)
        val ctx = PreCrawlContext(owner, owner, party, owner.dungeon!!, owner.points, CrawlModifiers())

        assertNull(agent.preCrawlPlay(ctx))
    }

    @Test
    fun abilityPolicyParsesFromYamlShape() {
        val raw = mapOf(
            "winning_opponent_points" to 7,
            "cards" to mapOf(
                "ability_return_to_town" to mapOf("deny_opponent_points" to 3),
                "ability_extra_damage" to mapOf("prevent_self_wound" to true)
            )
        )
        val pol = AbilityPolicy.from(raw)

        assertEquals(7, pol.winningOpponentPoints)
        assertEquals(3, pol.forCard("ability_return_to_town")?.denyOpponentPoints)
        assertEquals(true, pol.forCard("ability_extra_damage")?.preventSelfWound)
        assertNull(pol.forCard("ability_no_damage")) // not configured
    }
}
