package com.dungeonboss.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.dungeonboss.data.CardLibrary
import com.dungeonboss.game.Decision
import com.dungeonboss.game.Game
import com.dungeonboss.game.LogicAgent
import com.dungeonboss.game.Player

/**
 * Holds the in-memory [Game] and exposes it to the composition. The Game mutates
 * in place, so [tick] is bumped after every action to drive recomposition.
 * [sendCounter] keys the crawl animation (one bump per party sent).
 *
 * Player 1 is you; the remaining 1–3 players are LogicAgent computer opponents
 * driven by the heuristics in assets/ai_logic.yaml.
 *
 * Every action is wrapped so that a thrown exception is written to the debug log
 * (DebugLog) and surfaced in [lastError] instead of crashing the app — the log
 * file can then be uploaded to diagnose the failure.
 */
class GameViewModel(app: Application) : AndroidViewModel(app) {
    var game by mutableStateOf<Game?>(null)
        private set
    var tick by mutableIntStateOf(0)
        private set
    var sendCounter by mutableIntStateOf(0)
        private set

    /** The most recent action failure (shown as an on-screen banner), or null. */
    var lastError by mutableStateOf<String?>(null)
        private set

    val human: Player?
        get() = game?.players?.firstOrNull { game?.automated(it) == false }

    /** Absolute path of the uploadable debug log file. */
    fun logPath(): String = DebugLog.path()

    /** Start a new game with 1 human and (count − 1) computer opponents (2–4 total). */
    fun newGame(playerCount: Int = 2) = safe("newGame($playerCount)") {
        val count = playerCount.coerceIn(MIN_PLAYERS, MAX_PLAYERS)
        DebugLog.log("newGame: players=$count")
        val library = getApplication<Application>().assets.open(CARDS_ASSET).use { CardLibrary.load(it) }
        DebugLog.log(
            "loaded library: bosses=${library.bosses.size} rooms=${library.rooms.size} " +
                "advanced=${library.advancedRooms.size} " +
                "heroes=${library.heroes.size} abilities=${library.abilityCards.size}"
        )
        val names = (1..count).map { "Player $it" }
        // Player 1 is the human; every other player is a LogicAgent computer
        // opponent that reads its strategy from ai_logic.yaml.
        val assets = getApplication<Application>().assets
        val agents = names.drop(1).associateWith { assets.open(AI_LOGIC_ASSET).use { s -> LogicAgent.load(s) } }
        game = Game(library, names, agentsByName = agents).start()
        lastError = null
        DebugLog.log("newGame: started stage=${game?.stage} decision=${describe(game?.currentDecision())}")
    }

    /** Resolve the current pending decision (a blank choice means "skip"). */
    fun decide(choiceId: String?, target: Any? = null) = safe("decide(choice=$choiceId,target=$target)") {
        val g = game ?: return@safe
        DebugLog.log(
            "decide before: stage=${g.stage} decision=${describe(g.currentDecision())} " +
                "choice=$choiceId target=$target"
        )
        g.decide(choiceId, target)
        DebugLog.log("decide after:  stage=${g.stage} decision=${describe(g.currentDecision())}")
    }

    fun nextTurn() = safe("nextTurn") {
        val g = game ?: return@safe
        if (g.ready()) {
            g.startRound()
            DebugLog.log("nextTurn: round=${g.round} stage=${g.stage} decision=${describe(g.currentDecision())}")
        } else {
            DebugLog.log("nextTurn: ignored (not ready) stage=${g.stage}")
        }
    }

    fun sendNextParty() = safe("sendNextParty") {
        val g = game ?: return@safe
        DebugLog.log("sendNextParty: next=${g.nextCrawl()?.let { "${it.second.displayName()}->${it.first.name}" }}")
        g.sendNextParty()
        sendCounter += 1
        DebugLog.log("sendNextParty: after stage=${g.stage}")
    }

    /** Play an ability card from the human's hand on the current crawl (or quiet round). */
    fun playAbility(cardId: String, target: Any? = null) = safe("playAbility($cardId,$target)") {
        val g = game ?: return@safe
        human?.let { g.playAbility(it, cardId, target) }
        DebugLog.log("playAbility: $cardId target=$target stage=${g.stage}")
    }

    /** Discard a room card to boost the human's boostable room before its crawl. */
    fun boostRoom(cardId: String, roomIndex: Int) = safe("boostRoom($cardId,$roomIndex)") {
        game?.boostRoom(cardId, roomIndex)
        DebugLog.log("boostRoom: $cardId room=$roomIndex")
    }

    /** Take back the most recent mandatory discard during building. */
    fun undoDiscard() = safe("undoDiscard") {
        game?.undoDiscard()
    }

    /** Take back the boss choice during setup (before placing the first room). */
    fun undoBossChoice() = safe("undoBossChoice") {
        game?.undoBossChoice()
    }

    /** Take back the most recent room placement (before any party has crawled). */
    fun undoPlacement() = safe("undoPlacement") {
        game?.undoPlacement()
    }

    /** Take back the most recent ability card played in the pre-crawl window. */
    fun undoAbility() = safe("undoAbility") {
        game?.undoAbility()
    }

    /** Finish a quiet round (everyone draws an ability card), then recruit. */
    fun finishQuietRound() = safe("finishQuietRound") {
        game?.finishQuietRound()
    }

    private fun describe(decision: Decision?): String =
        decision?.let { "${it.kind}/${it.player.name}" } ?: "none"

    /** Run an action, logging and surfacing any exception instead of crashing. */
    private fun safe(label: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            DebugLog.error("action $label failed", t)
            lastError = "$label failed — ${t.javaClass.simpleName}: ${t.message}"
        }
        bump()
    }

    private fun bump() {
        tick += 1
    }

    companion object {
        const val HUMAN_PLAYER = "Player 1"
        const val CARDS_ASSET = "cards.yaml"
        const val AI_LOGIC_ASSET = "ai_logic.yaml"
        const val MIN_PLAYERS = 2
        const val MAX_PLAYERS = 4
    }
}
