package com.dungeonboss.game

import com.dungeonboss.data.CardLibrary
import com.dungeonboss.game.phases.ArrivalPhase
import com.dungeonboss.game.phases.BuildPhase
import com.dungeonboss.game.phases.DiscardPhase
import com.dungeonboss.game.phases.DrawPhase
import com.dungeonboss.game.phases.EnticePhase
import com.dungeonboss.game.phases.GauntletPhase
import com.dungeonboss.game.phases.RechargePhase
import com.dungeonboss.game.phases.SetupPhase
import com.dungeonboss.model.AbilityCard
import com.dungeonboss.model.Bait
import com.dungeonboss.model.Boss
import com.dungeonboss.model.BuildCard
import com.dungeonboss.model.Card
import com.dungeonboss.model.Hero
import com.dungeonboss.model.PlacedRoom
import com.dungeonboss.model.Room
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owns the players and decks and drives the turn as a sequence of player
 * decisions and crawls. The player makes every choice the rules call for; the
 * automatic phases (Arrival, Bait, Crawl) run on their own. Bait is combined
 * with Crawl: parties are evaluated for entry one at a time, in town order, as
 * points change. Each party's pre-crawl window is a turn-based ability priority
 * loop ([passPriority]/[playAbility]): the player with priority plays one ability
 * or passes, a play lets everyone respond, and the crawl resolves once all pass
 * in a row (the automated players are driven by [driveCrawl]). Orchestration
 * only — all rules live in the phase/resolver classes. Mirrors `webapp/lib/game.rb`.
 *
 * A player may instead be controlled by an automated [Agent] (LogicAgent for the
 * heuristic opponent, or RandomAgent as a baseline); decisions for an
 * agent-controlled player are resolved by the agent and never surface. The app
 * uses this to make the opponents computers.
 */
class Game(
    library: CardLibrary,
    playerNames: List<String>,
    val rng: Random = Random.Default,
    agentsByName: Map<String, Agent> = emptyMap()
) {
    enum class Stage { UNSTARTED, SETUP, READY, BUILDING, CRAWLING, QUIET, OVER }

    val players: List<Player> = playerNames.map { Player(it) }

    val bossDeck: Deck<Boss> = Deck(library.bosses, rng).shuffle()
    // Basic and advanced rooms share one build deck. The number of copies of each
    // basic room scales with the player count: N players → N copies of every basic
    // room, with ceil(N/2) of those copies seeded into the discard pile (so they
    // only enter play after a reshuffle) and the rest forming the draw pile.
    //   2 players → 2 copies, 1 in discard
    //   3 players → 3 copies, 2 in discard
    //   4 players → 4 copies, 2 in discard
    // Advanced rooms also scale with the player count — N players → N copies of
    // each advanced room — but always seed the discard pile (they can't be in an
    // opening hand). The card library holds enough copies of every room to cover
    // 4 players.
    val roomDeck: Deck<BuildCard> = run {
        val copiesPerCard = players.size
        val toDiscard = (players.size + 1) / 2   // ceil(N/2)
        val draw = mutableListOf<BuildCard>()
        val seeded = mutableListOf<BuildCard>()
        library.rooms.groupBy { it.id }.values.forEach { copies ->
            val used = copies.take(copiesPerCard)
            val n = toDiscard.coerceIn(0, used.size)
            seeded.addAll(used.take(n))
            draw.addAll(used.drop(n))
        }
        Deck<BuildCard>(draw, rng).shuffle().also { deck ->
            seeded.forEach { deck.discard(it) }
            library.advancedRooms.groupBy { it.id }.values.forEach { copies ->
                copies.take(copiesPerCard).forEach { deck.discard(it) }
            }
        }
    }
    val heroDeck: Deck<Hero> = Deck(library.heroes, rng).shuffle()
    val abilityDeck: Deck<AbilityCard> = Deck(library.abilityCards, rng).shuffle()

    private val agents: Map<Player, Agent> = buildAgents(agentsByName)

    val town: MutableList<Party> = mutableListOf() // a lone hero is a party of one

    var round: Int = 0
        private set
    var lastOutcomes: List<GauntletPhase.Outcome> = emptyList()
        private set
    var winner: Player? = null
        private set
    // The player whose crawl ended the game; they earn the end-game bonus.
    var endedBy: Player? = null
        private set
    var stage: Stage = Stage.UNSTARTED
        private set

    private val decisions = ArrayDeque<Decision>()
    private val crawlQueue = mutableListOf<Party>()           // parties still to evaluate this turn
    private val turnParties = mutableListOf<Party>()          // stable ordered snapshot of the turn's parties (UI)
    private val turnOutcomes = LinkedHashMap<Party, GauntletPhase.Outcome>() // resolved crawl per party this turn (UI)
    private var currentCrawl: Pair<Player, Party>? = null     // party in the pre-crawl window
    private var anyEntered = false                            // did any party enter this turn?
    private val attackedThisTurn = mutableSetOf<Player>()     // players whose dungeon was crawled
    private val crawlSurvivors = linkedSetOf<Hero>()          // heroes that survived a crawl this round
    private var crawlModifiers = CrawlModifiers()             // per-crawl ability/boost modifiers
    private var waitingParties = mutableListOf<Party>()       // parties that did not enter (Recharge)
    private var undoableDiscard: UndoableDiscard? = null
    // The human's most recent boss pick: (player, chosen, discarded) — for undo.
    private var lastBossChoice: Triple<Player, Boss, List<Boss>>? = null
    private val bossCandidates = mutableMapOf<Player, List<Boss>>()
    // The human's most recent room placement this turn — for undo before any crawl.
    private var undoablePlacement: UndoablePlacement? = null
    // The human's ability plays this pre-crawl window, newest last — for undo.
    private val undoableAbilities = ArrayDeque<UndoableAbility>()

    // --- Ability priority loop (the pre-crawl window). Each party's window is a
    // turn-based loop: the player with priority may play one ability or pass; a
    // play resets the pass streak so everyone gets another chance, and the crawl
    // resolves once every living player passes in a row (docs/phases.md 7b). ---
    private var priorityOrder: List<Player> = emptyList() // living players, board order
    private var priorityIndex: Int = 0                    // whose turn it is to act or pass
    private var consecutivePasses: Int = 0                // players who passed in a row this window
    // The ability cards played on the current crawl, in order, for display (each
    // shown beneath its target room). Cleared when a new window opens.
    private val crawlPlays = mutableListOf<AbilityPlayRecord>()

    /** One ability card played on the current crawl: who played it, and its target encounter (null if none). */
    data class AbilityPlayRecord(val player: Player, val card: AbilityCard, val targetIndex: Int?)

    /** A captured build placement so it can be fully reversed (see [undoPlacement]). */
    private class UndoablePlacement(
        val player: Player,
        val card: BuildCard,                 // the card played (returned to hand on undo)
        val slots: List<PlacedRoom?>,        // a copy of the dungeon's 5 slots before placing
        val discarded: List<BuildCard>       // cards place() pushed to the deck (reclaimed on undo)
    )

    /** A captured discard+draw so it can be reversed (see [undoDiscard]). */
    private class UndoableDiscard(
        val player: Player,
        val discarded: List<BuildCard>,      // cards discarded (reclaimed to hand on undo)
        val drawn: List<BuildCard>           // cards the Draw phase added (returned to deck on undo)
    )

    /** A captured ability play so it can be reversed (see [undoAbility]). */
    private class UndoableAbility(
        val player: Player,
        val card: AbilityCard,           // the card played (returned to hand on undo)
        val modsBefore: CrawlModifiers,  // crawl modifiers snapshot before this play
        val drawnRooms: List<BuildCard>  // rooms this play drew into hand (Blueprints)
    )

    /** Begin the game: deal hands and queue each player's Setup decisions. */
    fun start(): Game {
        SetupPhase.deal(this)
        players.forEach { enqueue(DecisionKind.CHOOSE_BOSS, it, bossCandidatesFor(it)) }
        // Only basic rooms can be the first placed room (not upgrades or advanced).
        players.forEach { p ->
            enqueue(
                DecisionKind.PLACE_FIRST_ROOM, p,
                p.roomHand.filterIsInstance<Room>().filterNot { it.advanced },
                mandatory = true
            )
        }
        stage = Stage.SETUP
        autoAdvance()
        return this
    }

    /**
     * Begin one round of play. Only valid once setup (and any prior round) is
     * resolved. Arrival runs automatically; Build queues a decision per living
     * player.
     */
    fun startRound(): Game {
        require(ready()) { "not ready to start a round" }

        round += 1
        lastOutcomes = emptyList() // clear last turn's crawl so it doesn't linger
        // The crawl-progress row shows the finished crawl through the following
        // READY, then clears here so it's empty during the new turn's build.
        turnParties.clear()
        turnOutcomes.clear()
        undoablePlacement = null
        undoableDiscard = null // a new turn starts fresh; last turn's discard is final
        undoableAbilities.clear()
        ArrivalPhase.run(this)
        // Discard (0–2, optional) then Build, per living player. The Draw phase runs
        // when each player's discard resolves (draw = 1 + cards discarded).
        livingPlayers().forEach { p ->
            enqueue(DecisionKind.DISCARD_ROOMS, p, p.roomHand, allowSkip = true)
            enqueue(DecisionKind.BUILD_ROOM, p, p.roomHand, allowSkip = true)
        }
        stage = Stage.BUILDING
        autoAdvance()
        return this
    }

    /**
     * The player who currently holds priority in the pre-crawl window (may play
     * one ability or pass), or null when no window is open or it has closed. The
     * crawl loop auto-runs automated players, so whenever this returns the human
     * the UI should let them play or [passPriority].
     */
    fun priorityHolder(): Player? {
        if (currentCrawl == null || priorityOrder.isEmpty()) return null
        if (consecutivePasses >= priorityOrder.size) return null
        return priorityOrder[priorityIndex]
    }

    /** The ability cards played on the current crawl, in order (for display under rooms). */
    fun crawlPlays(): List<AbilityPlayRecord> = crawlPlays.toList()

    /**
     * The player with priority passes. When every living player has passed in a
     * row the window closes and the crawl resolves; otherwise priority moves on.
     * After the human passes, the loop runs the automated players' responses.
     */
    fun passPriority(player: Player): Game {
        if (player !== priorityHolder()) return this
        passOf()
        driveCrawl()
        return this
    }

    /** Modifiers being assembled for the party currently in the pre-crawl window. */
    fun crawlMods(): CrawlModifiers = crawlModifiers

    /**
     * Play an ability card from [player]'s hand. During a crawl the player must
     * hold priority; the play takes effect, records itself for display, resets the
     * pass streak so others may respond, and the loop then runs the automated
     * players' responses. On a quiet round (no crawl) only non-targeting cards
     * (Blueprints) can be played, outside the priority loop. [target] is a room
     * index in the crawled dungeon, or null for non-targeting cards.
     */
    fun playAbility(player: Player, cardId: String, target: Any? = null): Game {
        if (currentCrawl == null) {
            if (stage == Stage.QUIET) playQuietAbility(player, cardId)
            return this
        }
        if (player !== priorityHolder()) return this
        if (player.abilityHand.none { it.id == cardId }) return this
        applyAbility(player, cardId, target)
        driveCrawl()
        return this
    }

    /**
     * Apply one ability play (shared by the human and the agents): spend the card,
     * fold its effect into [crawlModifiers], record it for display, and hand
     * priority on with the pass streak reset. Crawl window only.
     */
    private fun applyAbility(player: Player, cardId: String, target: Any?) {
        val card = player.abilityHand.firstOrNull { it.id == cardId } ?: return
        val spec = AbilityEffect.forCard(card)
        player.takeAbilityFromHand(cardId)
        undoablePlacement = null // a crawl is being acted on; the build can no longer be undone
        val room = targetIndexOrNull(target)
        if (room != null) {
            spec.addDamage?.let { crawlModifiers.addDamage(room, it) }
            if (spec.unreducible) crawlModifiers.unreducibleMark(room)
            if (spec.zero) crawlModifiers.zero(room)
            if (spec.retreat) crawlModifiers.retreat(room)
        }
        spec.drawRooms?.let { drawRoomsFor(player, it) }
        abilityDeck.discard(card)
        crawlPlays.add(AbilityPlayRecord(player, card, room))
        consecutivePasses = 0        // a play gives everyone another chance to respond
        advancePriority()
    }

    /** A quiet-round Blueprints play (no crawl, no priority loop); undoable by the human. */
    private fun playQuietAbility(player: Player, cardId: String) {
        val card = player.abilityHand.firstOrNull { it.id == cardId } ?: return
        val spec = AbilityEffect.forCard(card)
        if (spec.targetsRoom()) return // only non-targeting abilities (Blueprints) on a quiet round
        player.takeAbilityFromHand(cardId)
        undoablePlacement = null
        val modsBefore = crawlModifiers.copy()
        val drawn = spec.drawRooms?.let { drawRoomsFor(player, it) } ?: emptyList()
        abilityDeck.discard(card)
        if (!automated(player)) undoableAbilities.addLast(UndoableAbility(player, card, modsBefore, drawn))
    }

    /**
     * True while the human's most recent ability play can still be taken back —
     * only on a quiet round. A card played during a crawl is committed: it opens
     * the priority window for others to respond, so it cannot be unwound.
     */
    fun canUndoAbility(): Boolean =
        undoableAbilities.isNotEmpty() && stage == Stage.QUIET

    /**
     * Reverse the human's most recent quiet-round ability play: restore the crawl
     * modifiers as they were, return any rooms it drew (Blueprints) to the deck,
     * and put the ability card back in hand.
     */
    fun undoAbility(): Game {
        if (!canUndoAbility()) return this
        val a = undoableAbilities.removeLast()
        crawlModifiers = a.modsBefore
        a.drawnRooms.forEach { card -> a.player.takeRoomFromHand(card.id)?.let { roomDeck.discard(it) } }
        abilityDeck.reclaim(a.card)
        a.player.addAbilityToHand(a.card)
        return this
    }

    /**
     * The dungeon owner discards a room card to temporarily boost one of their
     * boostable rooms (Power Word / Undead Hands). Each discarded card adds the
     * room's discard amount to its damage for this crawl only — boosts stack.
     */
    fun boostRoom(cardId: String, roomIndex: Int): Game {
        val owner = currentCrawl?.first ?: return this
        val room = owner.dungeon!!.rooms[roomIndex]
        if (!RoomEffect.boostable(room)) return this

        val discard = owner.takeRoomFromHand(cardId)
            ?: throw IllegalArgumentException("room not in hand: $cardId")
        roomDeck.discard(discard)
        // A boost spends a room card, so the build can no longer be undone.
        undoablePlacement = null

        crawlModifiers.addDamage(roomIndex, RoomEffect.boostAmount(room))
        return this
    }

    /** The game is decided; no more turns. */
    fun over(): Boolean = stage == Stage.OVER

    /** Final standings (best first) for display; the ender's bonus is included. */
    fun standings(): List<Scoreboard.Standing> = Scoreboard.standings(players, endedBy)

    /** True while a party is in the pre-crawl window this turn. */
    fun crawling(): Boolean = stage == Stage.CRAWLING

    /** True on a round where no hero attacked: players may play Blueprints, then continue. */
    fun quiet(): Boolean = stage == Stage.QUIET

    /**
     * End a quiet round once the player is done playing no-attack abilities, then
     * Recruitment runs and the turn is over. The ability-card grant for unattacked
     * players happens in finishTurn (on a quiet round that is everyone).
     */
    fun finishQuietRound(): Game {
        if (stage != Stage.QUIET) return this
        finishTurn()
        return this
    }

    /** The party currently in the pre-crawl window, or null. */
    fun nextCrawl(): Pair<Player, Party>? = currentCrawl

    /**
     * Ordered snapshot of every party that will be evaluated for a crawl this
     * turn (town order, captured when the Crawl phase begins). Drives the
     * crawl-progress row: its position of [nextCrawl] marks which have already
     * been dealt with (before), which is about to go (at), and which still wait
     * (after). Empty outside the Crawl phase.
     */
    fun crawlOrder(): List<Party> = turnParties.toList()

    /**
     * The resolved crawl for [party] this turn, or null if it has not crawled yet
     * (or never entered a dungeon). Lets the crawl-progress row mark each member
     * as died / survived / fled once its party has gone.
     */
    fun crawlOutcomeFor(party: Party): GauntletPhase.Outcome? = turnOutcomes[party]

    /**
     * Predict the result of the pending crawl WITHOUT committing it — who dies,
     * where, and who survives — using the modifiers assembled so far. Side-effect
     * free, so the UI can call it to preview the crawl before Send. Null when no
     * party is in the pre-crawl window.
     */
    fun predictCurrentCrawl(): PartyCrawlResolver.Result? {
        val (player, party) = currentCrawl ?: return null
        val dungeon = player.dungeon ?: return null
        return PartyCrawlResolver.resolve(party, dungeon, player.points, crawlModifiers, dryRun = true)
    }

    // --- decision loop ---

    fun currentDecision(): Decision? = decisions.firstOrNull()

    fun awaitingDecision(): Boolean = decisions.isNotEmpty()

    /** True when nothing is pending (no decisions, no crawls) and a round may start. */
    fun ready(): Boolean =
        decisions.isEmpty() && currentCrawl == null && stage == Stage.READY

    /**
     * Apply the player's choice to the current decision. [choiceId] is a card id
     * (or a comma-joined list for DISCARD_ROOMS), or null to skip (only allowed for
     * skippable decisions). [target] is used by placement decisions: a slot index
     * (0..4), or "upgrade:<slot>" to spend a room card upgrading a placed room.
     * After applying, any following agent decisions resolve on their own.
     */
    fun decide(choiceId: String?, target: Any? = null): Game {
        require(decisions.isNotEmpty()) { "no pending decision" }
        processHead(choiceId, target)
        autoAdvance()
        return this
    }

    /** Whether the given player is controlled by an automated agent. */
    fun automated(player: Player): Boolean = agents.containsKey(player)

    /** A player with 5+ wounds is eliminated: out of the game. */
    fun eliminated(player: Player): Boolean = Scoreboard.eliminated(player)

    /** Players still in the game. */
    fun livingPlayers(): List<Player> = players.filterNot { Scoreboard.eliminated(it) }

    /** True while the human's most recent discard can still be taken back. */
    fun canUndoDiscard(): Boolean {
        val u = undoableDiscard ?: return false
        val decision = currentDecision() ?: return false
        return decision.kind == DecisionKind.BUILD_ROOM && decision.player === u.player
    }

    /**
     * Reverse the most recent discard+draw: return the cards the Draw phase added
     * to the deck, put the discarded cards back in hand, and re-prompt the discard.
     * No-op unless an undo is currently available.
     */
    fun undoDiscard(): Game {
        if (!canUndoDiscard()) return this
        val u = undoableDiscard!!
        // First take the drawn cards back out of hand and return them to the deck.
        u.drawn.forEach { card -> u.player.takeRoomFromHand(card.id)?.let { roomDeck.discard(it) } }
        // Then reclaim the discarded cards from the deck back into hand.
        u.discarded.forEach { card -> roomDeck.reclaim(card)?.let { u.player.addRoomToHand(it) } }
        decisions.addFirst(Decision(DecisionKind.DISCARD_ROOMS, u.player, u.player.roomHand.toList(), allowSkip = true))
        undoableDiscard = null
        return this
    }

    // --- boss candidate storage (used by SetupPhase) ---

    fun setBossCandidates(player: Player, bosses: List<Boss>) {
        bossCandidates[player] = bosses
    }

    fun bossCandidatesFor(player: Player): List<Boss> = bossCandidates[player] ?: emptyList()

    fun clearBossCandidates(player: Player) {
        bossCandidates.remove(player)
    }

    // --- town management (used by the phases) ---

    /** Heroes arrive as lone parties (a party of one). */
    fun addToTown(hero: Hero) {
        town.add(Party(listOf(hero)))
    }

    /** Remove a party from town (when all its members die, or when it merges). */
    fun removePartyFromTown(party: Party) {
        town.removeAll { it === party }
    }

    // --- internals ---

    /**
     * Pull parties off the queue (town order) until one enters a dungeon at the
     * current points, opening its pre-crawl priority window. Parties that won't
     * enter wait for Recruitment. When the queue is exhausted, the turn ends — or,
     * if nobody attacked, a quiet round begins. Callers drive the window with
     * [driveCrawl].
     */
    private fun advanceToNextCrawl() {
        while (crawlQueue.isNotEmpty()) {
            val party = crawlQueue.removeAt(0)
            val player = EnticePhase.targetFor(this, party)
            if (player != null) {
                currentCrawl = player to party
                crawlModifiers = CrawlModifiers()
                anyEntered = true
                attackedThisTurn.add(player) // this dungeon was attacked this turn
                openPriorityWindow(player)
                return
            }
            waitingParties.add(party)
        }

        currentCrawl = null
        if (anyEntered) {
            finishTurn()
        } else {
            stage = Stage.QUIET // no hero attacked; players may play Blueprints
        }
    }

    /**
     * Begin a party's pre-crawl priority window: reset the priority loop over the
     * living players, clear the display log, and let the automated owner take its
     * discard-to-boost first (a room-card boost, not an ability play).
     */
    private fun openPriorityWindow(owner: Player) {
        priorityOrder = priorityOrderForRound()
        priorityIndex = 0
        consecutivePasses = 0
        crawlPlays.clear()
        agentPreCrawl(owner)
    }

    /**
     * The player order for this turn's priority loop, rotated so a **different
     * player leads each turn** (turn 1 → player 1, turn 2 → player 2, wrapping),
     * with eliminated players skipped. Player-agnostic: humans and agents take
     * their turns through the same order, so the loop plays identically whether an
     * opponent is a computer or another person.
     */
    private fun priorityOrderForRound(): List<Player> {
        val n = players.size
        if (n == 0) return emptyList()
        val start = (round - 1).coerceAtLeast(0) % n // round is 1-based during play
        return (0 until n)
            .map { players[(start + it) % n] }
            .filterNot { Scoreboard.eliminated(it) }
    }

    /**
     * Run the pre-crawl priority loop: give each automated priority holder its
     * turn (play one ability or pass) and resolve the crawl once everyone has
     * passed in a row. Returns as soon as it is the human's turn (waiting for the
     * UI) or the crawl phase ends. After a crawl resolves this advances into the
     * next window and keeps driving, so one human pass advances exactly one party.
     */
    private fun driveCrawl() {
        while (currentCrawl != null) {
            if (priorityOrder.isEmpty() || consecutivePasses >= priorityOrder.size) {
                resolveCurrentCrawl()
                continue
            }
            val actor = priorityOrder[priorityIndex]
            val agent = agents[actor] ?: return // the human holds priority: wait for the UI
            val (owner, party) = currentCrawl!!
            val play = agent.preCrawlPlay(
                PreCrawlContext(actor, owner, party, owner.dungeon!!, owner.points, crawlModifiers)
            )
            if (play != null) applyAbility(actor, play.cardId, play.target) else passOf()
        }
    }

    /** The priority holder passes: count it and hand priority to the next player. */
    private fun passOf() {
        consecutivePasses += 1
        advancePriority()
    }

    /** Move priority to the next player in the window's order (wrapping). */
    private fun advancePriority() {
        if (priorityOrder.isNotEmpty()) priorityIndex = (priorityIndex + 1) % priorityOrder.size
    }

    /**
     * Close the current window and resolve its crawl: send the party in with the
     * assembled modifiers, score it, then advance to the next party (which reopens
     * a fresh window) or end the turn.
     */
    private fun resolveCurrentCrawl() {
        val (player, party) = currentCrawl ?: return
        undoablePlacement = null
        undoableAbilities.clear()
        val outcome = GauntletPhase.resolveParty(this, player, party, crawlModifiers)
        lastOutcomes = listOf(outcome)
        turnOutcomes[party] = outcome    // retained for the crawl-progress row's fate markers
        crawlSurvivors.addAll(outcome.result.survivors) // survivors level up in Recharge
        applyDeathDraws(player, outcome.result)
        currentCrawl = null

        if (Scoreboard.over(players)) {
            endedBy = player // the crawl's owner ended the game (gains the bonus)
            winner = Scoreboard.winner(players, player)
            crawlQueue.clear()
            stage = Stage.OVER
        } else {
            advanceToNextCrawl()
        }
    }

    private fun finishTurn() {
        undoablePlacement = null
        undoableAbilities.clear()
        // Recharge: un-attacked players draw an ability card, waiting parties
        // consolidate, and every hero that survived a crawl this round levels up.
        RechargePhase.run(this, waitingParties, attackedThisTurn, crawlSurvivors)
        waitingParties = mutableListOf()
        crawlSurvivors.clear()
        stage = Stage.READY
    }

    private fun buildAgents(byName: Map<String, Agent>): Map<Player, Agent> {
        val map = mutableMapOf<Player, Agent>()
        for (player in players) byName[player.name]?.let {
            map[player] = it
            it.attach(this) // some agents (e.g. LogicAgent) read the town to weigh choices
        }
        return map
    }

    /**
     * Resolve, in turn, every leading decision that belongs to an automated
     * player, using that player's agent to choose.
     */
    private fun autoAdvance() {
        while (true) {
            val decision = decisions.firstOrNull() ?: break
            val agent = agents[decision.player] ?: break
            val (choiceId, target) = agent.choose(decision)
            processHead(choiceId, target)
        }
    }

    /** Apply a choice to the head decision (shared by human and agent paths). */
    private fun processHead(choiceId: String?, target: Any?) {
        val decision = decisions.removeFirst()
        if (choiceId == null && !decision.allowSkip) {
            throw IllegalArgumentException("${decision.kind} requires a choice")
        }
        // A discard becomes the new undoable action, and it stays undoable through
        // the Build that follows so the player can unwind the whole turn (undo the
        // room, then the discard). Any OTHER choice closes the window on it; the
        // turn/round and crawl boundaries clear it too.
        if (decision.kind != DecisionKind.DISCARD_ROOMS && decision.kind != DecisionKind.BUILD_ROOM) {
            undoableDiscard = null
        }
        apply(decision, choiceId, target)
        resolveIfIdle()
    }

    private fun enqueue(
        kind: DecisionKind,
        player: Player,
        options: List<Card>,
        allowSkip: Boolean = false,
        mandatory: Boolean = false
    ) {
        decisions.add(Decision(kind, player, options, allowSkip && !mandatory))
    }

    private fun apply(decision: Decision, choiceId: String?, target: Any?) {
        when (decision.kind) {
            DecisionKind.CHOOSE_BOSS -> {
                // Remember the human's choice so it can be undone (the discarded
                // candidates go to the boss deck and are reclaimed on undo).
                val candidates = bossCandidatesFor(decision.player)
                val chosen = candidates.firstOrNull { it.id == choiceId }
                if (chosen != null && !automated(decision.player)) {
                    lastBossChoice = Triple(decision.player, chosen, candidates.filterNot { it === chosen })
                }
                SetupPhase.chooseBoss(this, decision.player, choiceId!!)
            }
            DecisionKind.PLACE_FIRST_ROOM -> {
                SetupPhase.placeFirstRoom(decision.player, choiceId!!, slotOf(target))
                if (lastBossChoice?.first === decision.player) lastBossChoice = null
            }
            DecisionKind.DISCARD_ROOMS -> {
                val player = decision.player
                val ids = parseIds(choiceId)
                val discarded = DiscardPhase.discard(this, player, ids)
                // Draw 1 + (cards discarded), capturing the drawn cards for undo.
                val drawn = drawRoomsFor(player, DrawPhase.BASE_DRAW + discarded.size)
                // Only the human's discard is undoable; an agent's must not clobber
                // it (agents build after the human, sharing this field).
                if (!automated(player)) undoableDiscard = UndoableDiscard(player, discarded, drawn)
            }
            DecisionKind.BUILD_ROOM -> {
                val player = decision.player
                // Capture a human placement so it can be undone before any crawl.
                // Agent builds run after the human's and must not clear it.
                if (!automated(player) && choiceId != null) {
                    val played = player.roomHand.firstOrNull { it.id == choiceId }
                    val before = player.dungeon!!.snapshotSlots()
                    val discarded = BuildPhase.place(this, player, choiceId, target)
                    if (played != null) undoablePlacement = UndoablePlacement(player, played, before, discarded)
                } else {
                    BuildPhase.place(this, player, choiceId, target)
                }
            }
        }
    }

    /** Parse a comma-joined choice id list (DISCARD_ROOMS) into individual ids. */
    private fun parseIds(choiceId: String?): List<String> =
        choiceId?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    /** A target slot index (0..4), accepting an Int, an "upgrade:N" string, or null (→ 0). */
    private fun slotOf(target: Any?): Int = when (target) {
        null -> 0
        is Int -> target
        else -> target.toString().substringAfter(':', target.toString()).trim().toIntOrNull() ?: 0
    }

    /** True while the human's most recent room placement can still be taken back. */
    fun canUndoPlacement(): Boolean {
        val p = undoablePlacement ?: return false
        return (stage == Stage.CRAWLING || stage == Stage.QUIET) && !automated(p.player)
    }

    /**
     * Reverse the human's most recent room placement and return the turn to the
     * Build phase, re-prompting that player's build choice. No-op once a party has
     * crawled (the window closes when the first crawl resolves).
     */
    fun undoPlacement(): Game {
        if (!canUndoPlacement()) return this
        val p = undoablePlacement!!
        val dungeon = p.player.dungeon ?: return this
        dungeon.restoreSlots(p.slots)                 // dungeon back to before the placement
        p.discarded.forEach { roomDeck.reclaim(it) }  // pull its discards back out of the deck
        p.player.addRoomToHand(p.card)                // return the played card to hand
        // Roll the turn back to building and re-prompt this player's build choice;
        // other players keep the dungeons they already built.
        currentCrawl = null
        crawlModifiers = CrawlModifiers()
        crawlQueue.clear()
        waitingParties = mutableListOf()
        anyEntered = false
        attackedThisTurn.clear()
        crawlSurvivors.clear()
        lastOutcomes = emptyList()
        decisions.clear()
        decisions.addFirst(Decision(DecisionKind.BUILD_ROOM, p.player, p.player.roomHand, allowSkip = true))
        stage = Stage.BUILDING
        undoablePlacement = null
        return this
    }

    /** True while the human's boss choice can still be taken back (before placing the first room). */
    fun canUndoBossChoice(): Boolean {
        val choice = lastBossChoice ?: return false
        val decision = currentDecision() ?: return false
        return stage == Stage.SETUP &&
            decision.kind == DecisionKind.PLACE_FIRST_ROOM && decision.player === choice.first
    }

    /** Undo the human's boss pick: restore the candidates and re-prompt the choice. */
    fun undoBossChoice(): Game {
        if (!canUndoBossChoice()) return this
        val (player, chosen, discarded) = lastBossChoice!!
        val restored = mutableListOf(chosen)
        discarded.forEach { boss -> bossDeck.reclaim(boss)?.let { restored.add(it) } }
        setBossCandidates(player, restored)
        player.dungeon = null
        lastBossChoice = null
        // Replace the player's pending first-room decision with a fresh
        // choose-boss followed by place-first.
        decisions.removeFirst()
        decisions.addFirst(
            Decision(DecisionKind.PLACE_FIRST_ROOM, player,
                player.roomHand.filterIsInstance<Room>().filterNot { it.advanced }, false)
        )
        decisions.addFirst(Decision(DecisionKind.CHOOSE_BOSS, player, restored, false))
        return this
    }

    /**
     * When the decision queue empties, finish whatever stage we were collecting
     * choices for.
     */
    private fun resolveIfIdle() {
        if (decisions.isNotEmpty()) return

        when (stage) {
            Stage.SETUP -> stage = Stage.READY
            Stage.BUILDING -> {
                // Entice + Gauntlet combined: evaluate parties for entry one at a time.
                crawlQueue.clear()
                crawlQueue.addAll(town)
                turnParties.clear()
                turnParties.addAll(town)          // stable order for the crawl-progress row
                turnOutcomes.clear()
                waitingParties = mutableListOf()
                anyEntered = false
                attackedThisTurn.clear()
                crawlSurvivors.clear()
                stage = Stage.CRAWLING
                advanceToNextCrawl()
                driveCrawl() // run any leading automated priority turns; stop at the human
            }
            else -> Unit
        }
    }

    /** An automated owner uses discard-to-boost on its own boostable rooms as the window opens. */
    private fun agentPreCrawl(owner: Player) {
        if (!automated(owner)) return
        val dungeon = owner.dungeon ?: return
        dungeon.rooms.forEachIndexed { i, room ->
            if (!RoomEffect.boostable(room)) return@forEachIndexed
            if (crawlModifiers.boosted(i)) return@forEachIndexed
            val spare = owner.roomHand.firstOrNull()
            if (spare != null && rng.nextDouble() < 0.5) boostRoom(spare.id, i)
        }
    }

    /** After a crawl, draw the cards earned by draw-on-death rooms. */
    private fun applyDeathDraws(player: Player, result: PartyCrawlResolver.Result) {
        repeat(result.draws["ability"] ?: 0) { player.addAbilityToHand(abilityDeck.draw()) }
        drawRoomsFor(player, result.draws["room"] ?: 0)
    }

    /**
     * Draw n cards from the build deck into a player's hand (respecting the cap).
     * Returns the cards actually added to hand (for undoing a draw).
     */
    private fun drawRoomsFor(player: Player, count: Int): List<BuildCard> {
        val added = mutableListOf<BuildCard>()
        repeat(count) {
            val card = roomDeck.draw() ?: return@repeat
            if (player.addRoomToHand(card)) added.add(card) else roomDeck.discard(card)
        }
        return added
    }

    private fun targetIndexOrNull(target: Any?): Int? = when (target) {
        null -> null
        is Int -> target
        else -> target.toString().trim().ifEmpty { null }?.toIntOrNull()
    }

    // ---------------------------------------------------------------------
    // Persistence: full-state snapshot to/from JSON. Only meaningful at a
    // stable, non-crawl stage — the transient crawl/recharge fields are empty
    // there, so they are not serialized. Card definitions come from the asset
    // library on restore; we store only ids plus the mutable per-instance state
    // (room level + granted bait, hero level). See GameViewModel autosave.
    // ---------------------------------------------------------------------

    /** True when the state is at a stable point safe to serialize/restore. */
    fun savable(): Boolean =
        stage == Stage.SETUP || stage == Stage.BUILDING || stage == Stage.READY || stage == Stage.OVER

    fun exportJson(): String {
        val root = JSONObject()
        root.put("v", SAVE_VERSION)
        root.put("round", round)
        root.put("stage", stage.name)
        winner?.let { root.put("winner", it.name) }
        endedBy?.let { root.put("endedBy", it.name) }

        val playersJson = JSONArray()
        for (p in players) {
            val pj = JSONObject()
            pj.put("name", p.name)
            pj.put("points", p.points)
            pj.put("wounds", p.wounds)
            pj.put("roomHand", JSONArray(p.roomHand.map { it.id }))
            pj.put("abilityHand", JSONArray(p.abilityHand.map { it.id }))
            p.dungeon?.let { d ->
                pj.put("boss", d.boss.id)
                val slots = JSONArray()
                for (slot in d.slots) {
                    if (slot == null) {
                        slots.put(JSONObject.NULL)
                    } else {
                        val bait = JSONObject()
                        slot.grantedBaitMap().forEach { (b, c) -> bait.put(b.name.lowercase(), c) }
                        slots.put(
                            JSONObject().put("room", slot.baseRoom.id)
                                .put("level", slot.level).put("bait", bait)
                        )
                    }
                }
                pj.put("dungeon", slots)
            }
            playersJson.put(pj)
        }
        root.put("players", playersJson)

        val decks = JSONObject()
        decks.put("boss", deckIdsJson(bossDeck) { it.id })
        decks.put("room", deckIdsJson(roomDeck) { it.id })
        decks.put("ability", deckIdsJson(abilityDeck) { it.id })
        val (hdraw, hdisc) = heroDeck.snapshot()
        decks.put("hero", JSONObject().put("draw", heroesJson(hdraw)).put("discard", heroesJson(hdisc)))
        root.put("decks", decks)

        val townJson = JSONArray()
        for (party in town) {
            townJson.put(JSONObject().put("name", party.name ?: JSONObject.NULL).put("members", heroesJson(party.heroes)))
        }
        root.put("town", townJson)

        val decJson = JSONArray()
        for (d in decisions) {
            decJson.put(JSONObject().put("kind", d.kind.name).put("player", d.player.name).put("skip", d.allowSkip))
        }
        root.put("decisions", decJson)

        val cand = JSONObject()
        for ((pl, bosses) in bossCandidates) cand.put(pl.name, JSONArray(bosses.map { it.id }))
        root.put("bossCandidates", cand)

        return root.toString()
    }

    private fun <T> deckIdsJson(deck: Deck<T>, id: (T) -> String): JSONObject {
        val (draw, discard) = deck.snapshot()
        return JSONObject().put("draw", JSONArray(draw.map(id))).put("discard", JSONArray(discard.map(id)))
    }

    private fun heroesJson(heroes: List<Hero>): JSONArray {
        val arr = JSONArray()
        heroes.forEach { arr.put(JSONObject().put("id", it.id).put("level", it.level)) }
        return arr
    }

    companion object {
        const val SAVE_VERSION = 1

        /**
         * Rebuild a game from an [exportJson] snapshot, minting fresh card
         * instances from [library] by id. Throws on any inconsistency (an unknown
         * id, a missing field) so the caller can discard a corrupt/old save.
         */
        fun importJson(text: String, library: CardLibrary, agentsByName: Map<String, Agent>): Game {
            val root = JSONObject(text)
            require(root.optInt("v", -1) == SAVE_VERSION) { "unsupported save version" }

            val playersJson = root.getJSONArray("players")
            val names = (0 until playersJson.length()).map { playersJson.getJSONObject(it).getString("name") }
            val game = Game(library, names, agentsByName = agentsByName)

            val bossQ = queueById(library.bosses) { it.id }
            // Rooms and advanced rooms share the build deck (Deck<BuildCard>); Room
            // is the only BuildCard, so the queue is typed BuildCard and cast to
            // Room where a placed room needs one.
            val roomQ = queueById<BuildCard>(library.rooms + library.advancedRooms) { it.id }
            val heroQ = queueById(library.heroes) { it.id }
            val abilQ = queueById(library.abilityCards) { it.id }

            game.round = root.getInt("round")
            game.stage = Stage.valueOf(root.getString("stage"))

            for (i in 0 until playersJson.length()) {
                val pj = playersJson.getJSONObject(i)
                val p = game.players.first { it.name == pj.getString("name") }
                p.points = pj.getInt("points")
                p.wounds = pj.getInt("wounds")
                p.roomHand.clear()
                idList(pj.getJSONArray("roomHand")).forEach { p.roomHand.add(take(roomQ, it)) }
                p.abilityHand.clear()
                idList(pj.getJSONArray("abilityHand")).forEach { p.abilityHand.add(take(abilQ, it)) }
                if (pj.has("boss")) {
                    val dungeon = Dungeon(take(bossQ, pj.getString("boss")))
                    val slots = pj.getJSONArray("dungeon")
                    val restored = ArrayList<PlacedRoom?>()
                    for (s in 0 until slots.length()) {
                        if (slots.isNull(s)) {
                            restored.add(null)
                        } else {
                            val sj = slots.getJSONObject(s)
                            val bj = sj.getJSONObject("bait")
                            val bait = LinkedHashMap<Bait, Int>()
                            bj.keys().forEach { k -> bait[Bait.normalize(k)] = bj.getInt(k) }
                            val base = take(roomQ, sj.getString("room")) as Room
                            restored.add(PlacedRoom.restored(base, sj.getInt("level"), bait))
                        }
                    }
                    dungeon.restoreSlots(restored)
                    p.dungeon = dungeon
                } else {
                    p.dungeon = null
                }
            }

            val decks = root.getJSONObject("decks")
            restoreDeck(game.bossDeck, decks.getJSONObject("boss"), bossQ)
            restoreDeck(game.roomDeck, decks.getJSONObject("room"), roomQ)
            restoreDeck(game.abilityDeck, decks.getJSONObject("ability"), abilQ)
            val hero = decks.getJSONObject("hero")
            game.heroDeck.restore(restoreHeroes(hero.getJSONArray("draw"), heroQ), restoreHeroes(hero.getJSONArray("discard"), heroQ))

            game.town.clear()
            val townJson = root.getJSONArray("town")
            for (t in 0 until townJson.length()) {
                val tj = townJson.getJSONObject(t)
                val heroes = restoreHeroes(tj.getJSONArray("members"), heroQ)
                game.town.add(Party(heroes, if (tj.isNull("name")) null else tj.getString("name")))
            }

            game.bossCandidates.clear()
            val cand = root.getJSONObject("bossCandidates")
            cand.keys().forEach { pn ->
                val pl = game.players.first { it.name == pn }
                game.bossCandidates[pl] = idList(cand.getJSONArray(pn)).map { take(bossQ, it) }
            }

            game.decisions.clear()
            val decJson = root.getJSONArray("decisions")
            for (d in 0 until decJson.length()) {
                val dj = decJson.getJSONObject(d)
                val kind = DecisionKind.valueOf(dj.getString("kind"))
                val pl = game.players.first { it.name == dj.getString("player") }
                val options: List<Card> = when (kind) {
                    DecisionKind.CHOOSE_BOSS -> game.bossCandidates[pl] ?: emptyList()
                    else -> pl.roomHand.toList()
                }
                game.decisions.add(Decision(kind, pl, options, dj.getBoolean("skip")))
            }

            root.optString("winner").takeIf { it.isNotEmpty() }?.let { wn -> game.winner = game.players.firstOrNull { it.name == wn } }
            root.optString("endedBy").takeIf { it.isNotEmpty() }?.let { en -> game.endedBy = game.players.firstOrNull { it.name == en } }

            return game
        }

        private fun <T> queueById(items: List<T>, id: (T) -> String): MutableMap<String, ArrayDeque<T>> {
            val map = HashMap<String, ArrayDeque<T>>()
            items.forEach { map.getOrPut(id(it)) { ArrayDeque() }.addLast(it) }
            return map
        }

        private fun <T> take(queue: MutableMap<String, ArrayDeque<T>>, id: String): T =
            queue[id]?.removeFirstOrNull() ?: throw IllegalStateException("save references a missing card: $id")

        private fun idList(arr: JSONArray): List<String> = (0 until arr.length()).map { arr.getString(it) }

        private fun restoreHeroes(arr: JSONArray, queue: MutableMap<String, ArrayDeque<Hero>>): List<Hero> =
            (0 until arr.length()).map {
                val hj = arr.getJSONObject(it)
                take(queue, hj.getString("id")).also { h -> h.level = hj.getInt("level") }
            }

        private fun <T> restoreDeck(deck: Deck<T>, json: JSONObject, queue: MutableMap<String, ArrayDeque<T>>) {
            fun ids(a: JSONArray) = (0 until a.length()).map { take(queue, a.getString(it)) }
            deck.restore(ids(json.getJSONArray("draw")), ids(json.getJSONArray("discard")))
        }
    }
}
