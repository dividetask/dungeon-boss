package com.dungeonboss.game

import com.dungeonboss.model.Bait
import com.dungeonboss.model.BaitIcons
import com.dungeonboss.model.Card
import com.dungeonboss.model.Encounter
import com.dungeonboss.model.Room
import org.yaml.snakeyaml.Yaml
import java.io.InputStream
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * An automated player driven by declarative heuristics (assets/ai_logic.yaml).
 * Like [RandomAgent] it answers a [Decision] with a (choiceId, target) pair, but
 * instead of picking at random it scores the candidates with the tie-break chain
 * configured for that decision (see the file and docs/ai.md for the vocabulary).
 *
 * The agent is the interpreter; the YAML is the logic. To weigh a placement by
 * how it would actually play out, the agent needs to know who is in town, so the
 * [Game] hands itself over via [attach]. Without a game (or when the town is
 * empty) the simulation comparators score 0 and the chain falls through to the
 * static ones.
 */
class LogicAgent(
    private val logic: Map<String, List<Map<String, Any?>>> = emptyMap(),
    private val rng: Random = Random.Default,
    private val abilityPolicy: AbilityPolicy = AbilityPolicy.NONE
) : Agent {
    private var game: Game? = null

    override fun attach(game: Game) {
        this.game = game
    }

    /**
     * The agent's move when it holds pre-crawl priority: its single best ability
     * play (or null to pass). [AbilityChooser] weighs each ability the actor holds
     * against [abilityPolicy] by forecasting the crawl under the modifiers so far;
     * the first play it returns is the one worth making now. On the agent's next
     * priority it is re-evaluated against the updated board, so it plays its cards
     * one at a time and passes once none clears its objective.
     */
    override fun preCrawlPlay(context: PreCrawlContext): AbilityPlay? =
        AbilityChooser.choose(context, abilityPolicy).firstOrNull()

    override fun choose(decision: Decision): Pair<String?, Any?> {
        val candidates = candidatesFor(decision)
        if (candidates.isEmpty()) return Pair(null, null)

        val rules = logic[decision.kind.name.lowercase()] ?: emptyList()
        val chosen = select(candidates, rules, decision)
        return Pair(chosen.id, chosen.target)
    }

    /**
     * A possible answer to a decision: the card to play (or null to skip), the
     * build target, and — for build moves — the dungeon the move would produce (so
     * the simulation comparators can crawl it). The forecast is cached per
     * candidate.
     */
    private class Candidate(
        val id: String?,
        val target: Any?,
        val card: Card?,
        val dungeon: Dungeon?
    ) {
        private var cached: DungeonForecast.Outcome? = null

        fun forecast(parties: List<Party>, bossBonus: Int): DungeonForecast.Outcome =
            cached ?: DungeonForecast.run(dungeon!!, parties, bossBonus).also { cached = it }
    }

    private fun candidatesFor(decision: Decision): List<Candidate> = when (decision.kind) {
        DecisionKind.BUILD_ROOM -> buildMoves(decision)
        DecisionKind.DISCARD_ROOMS -> decision.player.roomHand.map { Candidate(it.id, null, it, null) }
        else -> decision.options.map { Candidate(it.id, null, it, null) }
    }

    /**
     * Every legal build move (mirrors BuildPhase.place), each paired with the
     * dungeon it would produce, plus the option to build nothing. A room card may
     * be placed into a slot (empty fills, occupied replaces — advanced rooms only
     * into an empty slot or a bait-sharing one) or spent to upgrade a placed room.
     */
    private fun buildMoves(decision: Decision): List<Candidate> {
        val dungeon = decision.player.dungeon ?: return listOf(Candidate(null, null, null, null))
        val slots = dungeon.slots
        val occupied = slots.indices.filter { slots[it] != null }
        val moves = mutableListOf(Candidate(null, null, null, DungeonForecast.clone(dungeon))) // build nothing

        for (card in decision.player.roomHand) {
            if (card !is Room) continue
            // Spend the card to upgrade any placed room (grants its bait + a level).
            occupied.forEach { i -> moves.add(move(card, "upgrade:$i", dungeon) { it.upgradeRoomWith(i, card) }) }
            // Or place it into a slot.
            val placeSlots = if (card.advanced) {
                dungeon.emptySlots() + occupied.filter { slots[it]!!.bait.shares(card.bait) }
            } else {
                slots.indices.toList()
            }
            placeSlots.forEach { i -> moves.add(move(card, i, dungeon) { it.placeRoom(i, card) }) }
        }
        return moves
    }

    /** Build a candidate from a move: clone the dungeon, apply the change to the clone. */
    private fun move(card: Room, target: Any?, dungeon: Dungeon, apply: (Dungeon) -> Unit): Candidate {
        val candidateDungeon = DungeonForecast.clone(dungeon)
        apply(candidateDungeon)
        return Candidate(card.id, target, card, candidateDungeon)
    }

    /** Narrow the candidates with each rule in turn; break any final tie at random. */
    private fun select(candidates: List<Candidate>, rules: List<Map<String, Any?>>, decision: Decision): Candidate {
        var surviving = candidates
        for (rule in rules) {
            if (surviving.size <= 1) break
            surviving = keepBest(surviving, rule["prefer"], decision)
        }
        return surviving.random(rng)
    }

    /** Keep only the candidates whose score ties for the best under one comparator. */
    private fun keepBest(candidates: List<Candidate>, comparator: Any?, decision: Decision): List<Candidate> {
        val scorer = scorerFor(comparator)
        val scored = candidates.map { it to scorer(it, decision) }
        val best = scored.map { it.second }.maxWith { x, y -> compareScores(x, y) }
        return scored.filter { compareScores(it.second, best) == 0 }.map { it.first }
    }

    /**
     * A comparator name (or bait-priority list) -> a function scoring a candidate
     * as a list of ints, HIGHER is better (minimising comparators are negated). A
     * single number is a one-element list; a bait-priority list is compared
     * lexicographically (most of the first bait, then the second, ...).
     */
    private fun scorerFor(comparator: Any?): (Candidate, Decision) -> List<Int> {
        if (comparator is List<*>) {
            val order = comparator.map { Bait.normalize(it) }
            return { c, _ -> order.map { cardBait(c.card).count(it) } }
        }
        return when (comparator.toString()) {
            "highest_damage" -> { c, _ -> listOf(cardDamage(c.card)) }
            "lowest_damage" -> { c, _ -> listOf(-cardDamage(c.card)) }
            "bait_count" -> { c, _ -> listOf(cardBait(c.card).total()) }
            "most_points" -> { c, d -> listOf(forecast(c, d).kills) }
            "fewest_wounds" -> { c, d -> listOf(-forecast(c, d).wounds) }
            "highest_avg_damage" -> { c, d -> listOf((forecast(c, d).avgDamage * 10_000).roundToInt()) }
            else -> throw IllegalArgumentException("unknown ai_logic comparator: $comparator")
        }
    }

    /** Run (and cache) the crawl forecast for a build candidate against the town. */
    private fun forecast(candidate: Candidate, decision: Decision): DungeonForecast.Outcome {
        if (candidate.dungeon == null) return DungeonForecast.Outcome(0, 0, 0, 0)
        return candidate.forecast(town(), decision.player.points)
    }

    /** The parties currently in town, or none if the agent has no game attached. */
    private fun town(): List<Party> = game?.town ?: emptyList()

    /** A card's headline damage — its primary channel (lead, else all, else rear). */
    private fun cardDamage(card: Card?): Int = when (card) {
        is Encounter -> card.displayDamage // Boss / Room
        else -> 0
    }

    private fun cardBait(card: Card?): BaitIcons = when (card) {
        is Encounter -> card.bait // Boss / Room
        else -> BaitIcons()
    }

    /** Lexicographic comparison of two equal-length score lists (higher is better). */
    private fun compareScores(a: List<Int>, b: List<Int>): Int {
        for (i in a.indices) {
            val cmp = a[i].compareTo(b[i])
            if (cmp != 0) return cmp
        }
        return 0
    }

    companion object {
        /** Load the heuristics from a YAML stream (e.g. an Android asset). */
        fun load(input: InputStream, rng: Random = Random.Default): LogicAgent {
            input.use {
                @Suppress("UNCHECKED_CAST")
                val data = (Yaml().load<Any?>(it) as? Map<String, Any?>) ?: emptyMap()
                return LogicAgent(parse(data), rng, AbilityPolicy.from(Effects.mapOf(data["ai_abilities"])))
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun parse(data: Map<String, Any?>): Map<String, List<Map<String, Any?>>> =
            data.mapValues { (_, value) -> (value as? List<Map<String, Any?>>) ?: emptyList() }
    }
}
