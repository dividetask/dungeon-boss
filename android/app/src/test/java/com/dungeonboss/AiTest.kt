package com.dungeonboss

import com.dungeonboss.data.CardLibrary
import com.dungeonboss.game.Decision
import com.dungeonboss.game.DecisionKind
import com.dungeonboss.game.Dungeon
import com.dungeonboss.game.DungeonForecast
import com.dungeonboss.game.Game
import com.dungeonboss.game.LogicAgent
import com.dungeonboss.game.Party
import com.dungeonboss.game.Player
import com.dungeonboss.model.Bait
import com.dungeonboss.model.BaitIcons
import com.dungeonboss.model.Boss
import com.dungeonboss.model.Hero
import com.dungeonboss.model.Room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.random.Random

/**
 * The Android AI (LogicAgent + DungeonForecast) must behave like the Ruby
 * reference (see webapp/test/logic_agent_test.rb and dungeon_forecast_test.rb).
 */
class AiTest {

    private fun boss(id: String, damage: Int, bait: Map<String, Int> = emptyMap()) =
        Boss(id = id, name = id, damage = damage, bait = BaitIcons(bait))

    private fun room(id: String, damage: Int, bait: Map<String, Int> = emptyMap()) =
        Room(id = id, name = id, type = "trap", damage = damage, bait = BaitIcons(bait))

    private fun hero(id: String, health: Int) =
        Hero(id = id, name = id, health = health, preferredBait = Bait.GLORY)

    private fun agent(logic: Map<String, List<Map<String, Any?>>>) = LogicAgent(logic, Random(1))

    // --- DungeonForecast ---------------------------------------------------

    private fun dungeon(bossDamage: Int, roomDamages: List<Int>): Dungeon {
        val d = Dungeon(boss("b", bossDamage))
        roomDamages.asReversed().forEachIndexed { i, dmg -> d.addRoomToLeft(room("r$i", dmg)) }
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
        copy.rooms[0].grow = 3 // mutate the clone only

        assertEquals(1, copy.rooms.size)
        assertEquals(0, original.rooms[0].grow)  // original untouched
        assertEquals(8, copy.rooms[0].damage)    // 5 + grow 3
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
            "discard_room" to listOf(mapOf("prefer" to "lowest_damage"))
        )
        val decision = Decision(DecisionKind.DISCARD_ROOM, player, player.roomHand)

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
        d.addRoomToLeft(room("existing", 1))
        player.dungeon = d
        handDamages.forEachIndexed { i, dmg -> player.roomHand.add(room("hand${i}_$dmg", dmg)) }
        return Decision(DecisionKind.BUILD_ROOM, player, player.roomHand, allowSkip = true)
    }

    private fun gameWithTown(vararg heroes: Hero): Game {
        val library = CardLibrary(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
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
}
