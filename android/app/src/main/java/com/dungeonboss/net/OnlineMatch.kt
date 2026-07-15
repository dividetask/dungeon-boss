package com.dungeonboss.net

import com.dungeonboss.data.CardLibrary
import com.dungeonboss.game.Decision
import com.dungeonboss.game.Game
import com.dungeonboss.game.Player

/**
 * Drives one online match on this device (see docs/networking.md). It owns a
 * local [Game] built from the shared seed and keeps it in lockstep with the other
 * devices by a single rule: **only decision inputs cross the wire, and every
 * device applies the same ordered move stream to its own game.**
 *
 * The game is built with **no agents**, so the engine pauses at every pending
 * decision. This coordinator then:
 *   - surfaces the pending decision to the UI only when it belongs to [localSeat]
 *     ([localDecision]); the local human's answer is *sent* (not applied locally)
 *     and comes back in the ordered stream like everyone else's;
 *   - applies each inbound [MoveMessage] in `seq` order via `Game.decide`;
 *   - after each applied move, auto-drives the deterministic, input-free steps
 *     (arrival/draw, the crawl, recharge, next round) locally — identical on
 *     every device — until the next decision pends or the game ends.
 *
 * No AI, no game rules here that the engine doesn't already own; this is pure
 * message-to-`decide` plumbing.
 */
class OnlineMatch(
    library: CardLibrary,
    val config: MatchConfig,
    private val transport: Transport,
    /** Invoked after any state change so the UI can recompose. */
    private val onChanged: () -> Unit = {},
    /** Invoked on a detected desync / protocol error. */
    private val onDesync: (String) -> Unit = {},
) {
    val game: Game = Game.seeded(library, config.playerNames(), config.seed)
    val localSeat: Int = config.you

    /** Moves already applied; also the next `seq` we expect. */
    private var applied = 0

    /** Out-of-order moves held until their turn (a socket should be in order, but be safe). */
    private val pending = HashMap<Int, MoveMessage>()

    init {
        game.start()
        drive() // resolve any input-free opening steps and settle on the first decision
    }

    val localPlayer: Player get() = game.players[localSeat]

    /** The seat index of a player (its position in the fixed player list). */
    private fun seatOf(player: Player): Int = game.players.indexOf(player)

    /** The decision the local human should answer now, or null (not our turn / none). */
    fun localDecision(): Decision? {
        val d = game.currentDecision() ?: return null
        return if (seatOf(d.player) == localSeat) d else null
    }

    /** A pending decision that belongs to a *remote* seat (show "waiting for …"). */
    fun waitingOnSeat(): Int? {
        val d = game.currentDecision() ?: return null
        val seat = seatOf(d.player)
        return if (seat == localSeat) null else seat
    }

    /**
     * The local human answered [localDecision]: send it as a move. It is NOT
     * applied here — it returns in the ordered stream (server-stamped) and is
     * applied in [onMove], so every device applies in the identical order.
     */
    fun submitLocal(choiceId: String?, target: Any?) {
        val d = localDecision() ?: return
        transport.submitMove(
            MoveMessage(
                seq = null,
                player = localSeat,
                decisionId = decisionId(d),
                choiceId = choiceId,
                target = target?.toString(),
            ),
        )
    }

    /** An ordered move arrived from the server: buffer, then apply in `seq` order. */
    fun onMove(move: MoveMessage) {
        val seq = move.seq
        if (seq == null) {
            onDesync("move without seq")
            return
        }
        if (seq < applied) return // a duplicate (e.g. after reconnect); already applied
        pending[seq] = move
        var next = pending.remove(applied)
        while (next != null) {
            if (!apply(next)) return
            applied += 1
            next = pending.remove(applied)
        }
        drive()
        onChanged()
    }

    /** Reconnect replay: rebuild from seq 0 by applying the whole log in order. */
    fun onLog(moves: List<MoveMessage>) {
        // The game is already at seed-start (fresh instance semantics on this path
        // are the caller's responsibility); apply any moves we have not yet seen.
        for (move in moves.sortedBy { it.seq ?: Int.MAX_VALUE }) onMove(move)
    }

    /** Apply one ordered move to the local game. Returns false on a fatal desync. */
    private fun apply(move: MoveMessage): Boolean {
        val d = game.currentDecision()
        if (d == null) {
            onDesync("move $move but no decision pending")
            return false
        }
        val expected = decisionId(d)
        if (move.decisionId != null && move.decisionId != expected) {
            onDesync("move for ${move.decisionId} but expected $expected (desync)")
            return false
        }
        game.decide(move.choiceId, move.target)
        return true
    }

    /**
     * Run the input-free part of the turn locally: the automatic phases, the whole
     * crawl (no per-crawl input in the current rules — pre-crawl abilities are not
     * yet networked), and the next round's start, stopping as soon as a decision
     * pends or the game is over. Deterministic, so every device lands identically.
     */
    private fun drive() {
        var guard = 0
        while (!game.over() && guard++ < 100_000) {
            if (game.awaitingDecision()) break
            when {
                game.crawling() && game.nextCrawl() != null -> game.sendNextParty()
                game.quiet() -> game.finishQuietRound()
                game.ready() -> game.startRound()
                else -> break
            }
        }
    }

    fun over(): Boolean = game.over()

    fun close() = transport.close()

    /**
     * A stable id for a pending decision, identical on every device (lockstep):
     * round + kind + seat is unique within a turn (each seat gets at most one of
     * each kind per round; Setup is round 0).
     */
    private fun decisionId(d: Decision): String = "${game.round}:${d.kind}:${seatOf(d.player)}"
}
