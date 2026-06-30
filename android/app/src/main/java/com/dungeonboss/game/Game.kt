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
import com.dungeonboss.model.Boss
import com.dungeonboss.model.BuildCard
import com.dungeonboss.model.Card
import com.dungeonboss.model.Hero
import com.dungeonboss.model.PlacedRoom
import com.dungeonboss.model.Room
import kotlin.random.Random

/**
 * Owns the players and decks and drives the turn as a sequence of player
 * decisions and crawls. The player makes every choice the rules call for; the
 * automatic phases (Arrival, Bait, Crawl) run on their own. Bait is combined
 * with Crawl: parties are evaluated for entry one at a time, in town order, as
 * points change. Just before each crawl the owner may play ability cards or
 * discard-to-boost a room. Orchestration only — all rules live in the
 * phase/resolver classes. Mirrors `webapp/lib/game.rb`.
 *
 * A player may instead be controlled by an automated agent (RandomAgent);
 * decisions for an agent-controlled player are resolved by the agent and never
 * surface. The app uses this to make the opponents random computers.
 */
class Game(
    library: CardLibrary,
    playerNames: List<String>,
    val rng: Random = Random.Default,
    agentsByName: Map<String, RandomAgent> = emptyMap()
) {
    enum class Stage { UNSTARTED, SETUP, READY, BUILDING, CRAWLING, QUIET, OVER }

    val players: List<Player> = playerNames.map { Player(it) }

    val bossDeck: Deck<Boss> = Deck(library.bosses, rng).shuffle()
    // Basic and advanced rooms share one build deck. Advanced rooms seed the
    // discard pile so they can't be in an opening hand; they enter circulation
    // only after the first reshuffle.
    val roomDeck: Deck<BuildCard> =
        Deck<BuildCard>(library.rooms, rng).shuffle().also { deck ->
            library.advancedRooms.forEach { deck.discard(it) }
        }
    val heroDeck: Deck<Hero> = Deck(library.heroes, rng).shuffle()
    val abilityDeck: Deck<AbilityCard> = Deck(library.abilityCards, rng).shuffle()

    private val agents: Map<Player, RandomAgent> = buildAgents(agentsByName)

    val town: MutableList<Party> = mutableListOf() // a lone hero is a party of one

    var round: Int = 0
        private set
    var lastOutcomes: List<GauntletPhase.Outcome> = emptyList()
        private set
    var winner: Player? = null
        private set
    var stage: Stage = Stage.UNSTARTED
        private set

    private val decisions = ArrayDeque<Decision>()
    private val crawlQueue = mutableListOf<Party>()           // parties still to evaluate this turn
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
        undoablePlacement = null
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
     * Send the current party into its dungeon (one crawl per call), then work out
     * which party (if any) enters next — re-checking courage against each owner's
     * now-current points. Just before resolving, an automated owner takes its
     * pre-crawl actions (discard-to-boost) and the accumulated modifiers apply.
     */
    fun sendNextParty(): Game {
        val (player, party) = currentCrawl ?: return this

        undoablePlacement = null // a crawl is resolving; the build can no longer be undone
        undoableAbilities.clear() // ability plays apply to this crawl; no undo after it resolves
        agentPreCrawl(player)
        val outcome = GauntletPhase.resolveParty(this, player, party, crawlModifiers)
        lastOutcomes = listOf(outcome)
        crawlSurvivors.addAll(outcome.result.survivors) // survivors level up in Recharge
        applyDeathDraws(player, outcome.result)
        currentCrawl = null

        if (Scoreboard.over(players)) {
            winner = Scoreboard.winner(players, player) // the crawl's owner ended the game
            crawlQueue.clear()
            stage = Stage.OVER
        } else {
            advanceToNextCrawl()
        }
        return this
    }

    /** Modifiers being assembled for the party currently in the pre-crawl window. */
    fun crawlMods(): CrawlModifiers = crawlModifiers

    /**
     * Play an ability card from [player]'s hand on the current crawl. [target] is
     * a room index in the crawled dungeon, or null for non-targeting cards.
     */
    fun playAbility(player: Player, cardId: String, target: Any? = null): Game {
        if (currentCrawl == null && stage != Stage.QUIET) return this

        val card = player.abilityHand.firstOrNull { it.id == cardId } ?: return this
        val spec = AbilityEffect.forCard(card)
        // On a quiet round there is no crawl, so only non-targeting abilities
        // (Blueprints) can be played; room-targeting cards are not spent.
        if (currentCrawl == null && spec.targetsRoom()) return this

        player.takeAbilityFromHand(cardId)
        // The card is now spent, so the build can no longer be undone (undoing it
        // would roll back the turn and lose this play).
        undoablePlacement = null
        val modsBefore = crawlModifiers.copy() // for undoing this ability play
        val room = if (currentCrawl != null) targetIndexOrNull(target) else null
        if (room != null) {
            spec.addDamage?.let { crawlModifiers.addDamage(room, it) }
            if (spec.unreducible) crawlModifiers.unreducibleMark(room)
            if (spec.zero) crawlModifiers.zero(room)
            if (spec.retreat) crawlModifiers.retreat(room)
        }
        val drawn = spec.drawRooms?.let { drawRoomsFor(player, it) } ?: emptyList()
        abilityDeck.discard(card)
        if (!automated(player)) undoableAbilities.addLast(UndoableAbility(player, card, modsBefore, drawn))
        return this
    }

    /** True while the human's most recent ability play can still be taken back. */
    fun canUndoAbility(): Boolean =
        undoableAbilities.isNotEmpty() && (stage == Stage.CRAWLING || stage == Stage.QUIET)

    /**
     * Reverse the human's most recent ability play: restore the crawl modifiers as
     * they were, return any rooms it drew (Blueprints) to the deck, and put the
     * ability card back in hand. No-op once a crawl has resolved.
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

    /** Final standings (best first) for display. */
    fun standings(): List<Scoreboard.Standing> = Scoreboard.standings(players)

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
     * current points, pausing there for the pre-crawl window. Parties that won't
     * enter wait for Recruitment. When the queue is exhausted, the turn ends — or,
     * if nobody attacked, a quiet round begins.
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

    private fun buildAgents(byName: Map<String, RandomAgent>): Map<Player, RandomAgent> {
        val map = mutableMapOf<Player, RandomAgent>()
        for (player in players) byName[player.name]?.let { map[player] = it }
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
        // A discard becomes the new undoable action; any other choice closes the
        // window on the previous discard.
        if (decision.kind != DecisionKind.DISCARD_ROOMS) undoableDiscard = null
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
                undoableDiscard = UndoableDiscard(player, discarded, drawn)
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
                waitingParties = mutableListOf()
                anyEntered = false
                attackedThisTurn.clear()
                crawlSurvivors.clear()
                stage = Stage.CRAWLING
                advanceToNextCrawl()
            }
            else -> Unit
        }
    }

    /**
     * An automated owner uses discard-to-boost on its own boostable rooms before
     * a crawl (it does not play ability cards yet).
     */
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
}
