package com.dungeonboss.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.dungeonboss.data.CardLibrary
import com.dungeonboss.game.Agent
import com.dungeonboss.game.Decision
import com.dungeonboss.game.Game
import com.dungeonboss.game.LogicAgent
import com.dungeonboss.game.Player
import com.dungeonboss.model.Boss
import com.dungeonboss.model.Encounter
import com.dungeonboss.model.PlacedRoom
import org.json.JSONObject
import java.io.File

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

    /** The autosave file (internal storage), so a game survives an app kill/reboot. */
    private val saveFile: File get() = File(getApplication<Application>().filesDir, SAVE_FILE)

    private fun loadLibrary(): CardLibrary =
        getApplication<Application>().assets.open(CARDS_ASSET).use { CardLibrary.load(it) }

    /** LogicAgent opponents for every non-human name (Player 2..N). */
    private fun buildAgents(names: List<String>): Map<String, Agent> {
        val assets = getApplication<Application>().assets
        return names.drop(1).associateWith { assets.open(AI_LOGIC_ASSET).use { s -> LogicAgent.load(s) } }
    }

    /** Start a new game with 1 human and (count − 1) computer opponents (2–4 total). */
    fun newGame(playerCount: Int = 2) = safe("newGame($playerCount)") {
        val count = playerCount.coerceIn(MIN_PLAYERS, MAX_PLAYERS)
        DebugLog.log("newGame: players=$count")
        val library = loadLibrary()
        DebugLog.log(
            "loaded library: bosses=${library.bosses.size} rooms=${library.rooms.size} " +
                "advanced=${library.advancedRooms.size} " +
                "heroes=${library.heroes.size} abilities=${library.abilityCards.size}"
        )
        val names = (1..count).map { "Player $it" }
        // Player 1 is the human; every other player is a LogicAgent computer
        // opponent that reads its strategy from ai_logic.yaml.
        game = Game(library, names, agentsByName = buildAgents(names)).start()
        lastError = null
        DebugLog.log("newGame: started stage=${game?.stage} decision=${describe(game?.currentDecision())}")
    }

    /** True if a saved game is on disk (for a start-screen "Resume"/"New game" choice). */
    fun hasSavedGame(): Boolean = saveFile.exists()

    /** True only if the saved game exists AND is still in progress (not finished). */
    fun savedGameInProgress(): Boolean {
        if (!saveFile.exists()) return false
        return try {
            JSONObject(saveFile.readText()).optString("stage") != "OVER"
        } catch (t: Throwable) {
            false
        }
    }

    /** Load the saved game if present; a corrupt/old save is discarded, never fatal. */
    fun restoreIfSaved() {
        if (!saveFile.exists()) return
        try {
            val text = saveFile.readText()
            val names = JSONObject(text).getJSONArray("players").let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }
            }
            game = Game.importJson(text, loadLibrary(), buildAgents(names))
            lastError = null
            DebugLog.log("restored save: round=${game?.round} stage=${game?.stage}")
            bump()
        } catch (t: Throwable) {
            DebugLog.error("restore failed; discarding save", t)
            runCatching { saveFile.delete() }
            game = null
        }
    }

    /** Persist the game whenever it sits at a stable, non-crawl point. */
    private fun autosave() {
        val g = game ?: return
        try {
            if (g.savable()) saveFile.writeText(g.exportJson())
        } catch (t: Throwable) {
            DebugLog.error("autosave failed", t)
        }
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

    /**
     * The human passes pre-crawl priority. If every player has now passed in a
     * row the Game resolves the crawl (and advances into the next party's window);
     * otherwise the window stays open for the humans to respond. A resolved crawl
     * (detected as a new outcome) bumps [sendCounter] to play its animation.
     */
    fun passPriority() = safe("passPriority") {
        val g = game ?: return@safe
        // Player-agnostic: pass for whoever currently holds priority, as long as
        // it is a local (non-automated) player — the same path a human uses
        // against other humans or against the AIs.
        val actor = g.priorityHolder() ?: return@safe
        if (g.automated(actor)) return@safe
        DebugLog.log("passPriority: ${actor.name} next=${g.nextCrawl()?.let { "${it.second.displayName()}->${it.first.name}" }}")
        val before = g.lastOutcomes
        g.passPriority(actor)
        if (g.lastOutcomes !== before) {
            sendCounter += 1
            logCrawl(g)
        }
        DebugLog.log("passPriority: after stage=${g.stage} priority=${g.priorityHolder()?.name}")
    }

    /** Log the just-resolved crawl hit-by-hit so logs capture per-hit damage. */
    private fun logCrawl(g: Game) {
        val o = g.lastOutcomes.firstOrNull() ?: return
        val r = o.result
        DebugLog.log(
            "crawl: ${o.party.displayName()} -> ${o.player.name}'s dungeon" +
                (if (o.retreated) " [RETREATED]" else "") + " (boss +${o.bossBonus})"
        )
        if (r.log.isEmpty()) DebugLog.log("  (no damage dealt)")
        r.log.forEach { s ->
            DebugLog.log(
                "  [${s.roomIndex}] ${encName(s.encounter)}: ${s.hero.name} " +
                    "-${s.damage} -> ${s.healthAfter} hp" + if (s.died) "  ** DIED **" else ""
            )
        }
        DebugLog.log(
            "  => deaths=${r.deaths}, survivors=[${r.survivors.joinToString(", ") { it.name }}]"
        )
    }

    private fun encName(e: Encounter): String = when (e) {
        is PlacedRoom -> e.name
        is Boss -> e.name
        else -> e.type ?: "encounter"
    }

    /** Play an ability card from the local player's hand on the current crawl (or quiet round). */
    fun playAbility(cardId: String, target: Any? = null) = safe("playAbility($cardId,$target)") {
        val g = game ?: return@safe
        // During a crawl the actor is the current (local) priority holder; on a
        // quiet round there is no priority loop, so it is simply the local human.
        val actor = g.priorityHolder() ?: human ?: return@safe
        if (g.automated(actor)) return@safe
        g.playAbility(actor, cardId, target)
        DebugLog.log("playAbility: ${actor.name} $cardId target=$target stage=${g.stage} priority=${g.priorityHolder()?.name}")
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
        autosave()
        bump()
    }

    private fun bump() {
        tick += 1
    }

    companion object {
        const val HUMAN_PLAYER = "Player 1"
        const val CARDS_ASSET = "cards.yaml"
        const val AI_LOGIC_ASSET = "ai_logic.yaml"
        const val SAVE_FILE = "savegame.json"
        const val MIN_PLAYERS = 2
        const val MAX_PLAYERS = 4
    }
}
