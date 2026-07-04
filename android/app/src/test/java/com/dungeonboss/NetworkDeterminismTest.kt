package com.dungeonboss

import com.dungeonboss.data.CardLibrary
import com.dungeonboss.game.Agent
import com.dungeonboss.game.Deck
import com.dungeonboss.game.Game
import com.dungeonboss.game.RandomAgent
import com.dungeonboss.model.Card
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * The determinism contract that online lockstep multiplayer relies on
 * (docs/networking.md): a [Game] built from a shared seed and fed the same
 * decisions in the same order advances through identical state on every device.
 * Only decision *inputs* travel the wire, never game state, so every device must
 * reconstruct the same game from `Game.seeded(seed)`.
 *
 * These tests exercise that end-to-end against the real card library: same seed →
 * identical shuffles, identical deal, and — driven by seeded agents standing in
 * for a full turn-by-turn input log — identical state all the way to game over.
 *
 * Each game gets its OWN [CardLibrary]: cards are identity-keyed plain objects and
 * a hero's level is mutable, so two live games must never share instances. State
 * is therefore compared by id via [exportJson] / [deckIds], never object identity.
 */
class NetworkDeterminismTest {

    private val names = listOf("Player 1", "Player 2", "Player 3")

    /** Same seed ⇒ every deck is shuffled into the identical order (compared by id). */
    @Test
    fun sameSeedShufflesEveryDeckIdentically() {
        val a = Game.seeded(library(), names, seed = 12345L)
        val b = Game.seeded(library(), names, seed = 12345L)

        assertEquals(deckIds(a.bossDeck), deckIds(b.bossDeck))
        assertEquals(deckIds(a.roomDeck), deckIds(b.roomDeck))
        assertEquals(deckIds(a.heroDeck), deckIds(b.heroDeck))
        assertEquals(deckIds(a.abilityDeck), deckIds(b.abilityDeck))
    }

    /** Same seed ⇒ the whole Setup deal (hands, boss candidates, decks) matches. */
    @Test
    fun sameSeedDealsIdenticalOpeningState() {
        val a = Game.seeded(library(), names, seed = 777L).start()
        val b = Game.seeded(library(), names, seed = 777L).start()

        assertEquals(a.exportJson(), b.exportJson())
    }

    /** Different seeds ⇒ the shuffles actually differ (the seed drives them). */
    @Test
    fun differentSeedsDiverge() {
        val a = Game.seeded(library(), names, seed = 1L)
        val b = Game.seeded(library(), names, seed = 2L)

        // With dozens of cards, two independent shuffles colliding is astronomically
        // unlikely; a match here means the seed is not actually driving the shuffle.
        assertNotEquals(deckIds(a.roomDeck), deckIds(b.roomDeck))
    }

    /**
     * A full playthrough stays in lockstep. Two games share the game seed and give
     * every player a seeded [RandomAgent] (the stand-in for an identical decision
     * input log). Driven through every phase to game over, their serialized state
     * must match at every step — this exercises the turn-loop randomness the deal
     * test can't reach: mid-game reshuffles, PartyNamer, and the auto-boost roll.
     */
    @Test
    fun fullPlaythroughStaysInLockstep() {
        val g1 = Game.seeded(library(), names, seed = 2024L, agentsByName = agents(seed = 99L)).start()
        val g2 = Game.seeded(library(), names, seed = 2024L, agentsByName = agents(seed = 99L)).start()

        assertEquals("diverged during the opening deal", g1.exportJson(), g2.exportJson())

        var step = 0
        // Bounded so a bug can never hang the suite; wounds accrue every round, so a
        // real game reaches game-over (5 wounds / 10 points) far sooner than this.
        while (!g1.over() && step++ < 100_000) {
            advance(g1)
            advance(g2)
            assertEquals("diverged at step $step (stage ${g1.stage})", g1.exportJson(), g2.exportJson())
        }

        assertTrue("game did not finish within the step bound", g1.over())
        assertEquals(g1.exportJson(), g2.exportJson())
    }

    /** One headless step of the turn loop — the same control flow the ViewModel drives. */
    private fun advance(game: Game) {
        when {
            game.crawling() && game.nextCrawl() != null -> game.sendNextParty()
            game.quiet() -> game.finishQuietRound()
            game.ready() -> game.startRound()
            // All players are agents, so no human decision ever pends; nothing to do.
            else -> {}
        }
    }

    /** A seeded RandomAgent per player — a reproducible stand-in for a decision log. */
    private fun agents(seed: Long): Map<String, Agent> =
        names.associateWith { RandomAgent(Random(seed)) }

    /** Draw/discard piles as id lists, so two games' decks compare by card id. */
    private fun deckIds(deck: Deck<out Card>): Pair<List<String>, List<String>> {
        val (draw, discard) = deck.snapshot()
        return draw.map { it.id } to discard.map { it.id }
    }

    /** A fresh library (distinct card instances) — never shared between live games. */
    private fun library(): CardLibrary {
        val candidates = listOf(
            "src/main/assets/cards.yaml",             // CWD = android/app (Gradle default)
            "app/src/main/assets/cards.yaml",         // CWD = android
            "android/app/src/main/assets/cards.yaml", // CWD = repo root
        )
        val file = candidates.map(::File).firstOrNull { it.exists() }
            ?: error("cards.yaml not found from ${File(".").absolutePath}; tried $candidates")
        return file.inputStream().use { CardLibrary.load(it) }
    }
}
