package com.dungeonboss.net

/**
 * The link to the matchmaking server. Hides whichever socket/library is used
 * (OkHttp in the app, a fake in tests) so [OnlineMatch] never depends on a
 * particular backend. Knows nothing about game rules — it only carries the wire
 * messages defined in server/README.md.
 */
interface Transport {
    var listener: TransportListener?

    /** Join matchmaking for a [players]-sized table (2..4). The server auto-pairs. */
    fun queue(name: String, players: Int)

    /** Leave the queue while still waiting. */
    fun cancel()

    /** Submit the local player's answer to the current decision. */
    fun submitMove(move: MoveMessage)

    /** Rejoin a match after dropping; the server replays the move log. */
    fun reconnect(matchId: String, playerId: String)

    /** Close the connection. */
    fun close()
}

/**
 * Callbacks for messages arriving from the server. All are optional; implement
 * only what is needed. Delivery is expected on a single thread (the app posts to
 * the main thread) so [OnlineMatch] needs no locking.
 */
interface TransportListener {
    /** Our own player id, issued on connect (kept for [Transport.reconnect]). */
    fun onHello(playerId: String) {}
    fun onQueued(players: Int, waiting: Int) {}
    fun onMatched(config: MatchConfig) {}
    fun onMove(move: MoveMessage) {}
    fun onLog(matchId: String, moves: List<MoveMessage>) {}
    fun onPeer(event: String, seat: Int) {}
    fun onError(message: String) {}
    fun onClosed() {}
}
