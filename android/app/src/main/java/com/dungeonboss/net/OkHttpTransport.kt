package com.dungeonboss.net

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * The real [Transport]: a WebSocket to the matchmaking server (server/), speaking
 * the JSON protocol in server/README.md. Server callbacks arrive on OkHttp's
 * dispatcher thread and are re-posted to the main thread, so [OnlineMatch] and the
 * UI see them single-threaded.
 */
class OkHttpTransport(private val url: String) : Transport {
    override var listener: TransportListener? = null

    private val client = OkHttpClient()
    private val main = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null

    private var playerId: String? = null
    private var matchId: String? = null

    // Everything below is touched only on the main thread (callbacks are posted
    // there, and the ViewModel calls the senders from there), so no locking.
    private var open = false
    private val outbox = ArrayDeque<String>()

    fun connect() {
        socket = client.newWebSocket(Request.Builder().url(url).build(), Handler2())
    }

    override fun queue(name: String, players: Int) {
        send(JSONObject().put("type", "queue").put("name", name).put("players", players))
    }

    override fun cancel() = send(JSONObject().put("type", "cancel"))

    override fun submitMove(move: MoveMessage) {
        val json = JSONObject().put("type", "move").put("player", move.player)
        matchId?.let { json.put("matchId", it) }
        move.decisionId?.let { json.put("decisionId", it) }
        move.choiceId?.let { json.put("choiceId", it) }
        move.target?.let { json.put("target", it) }
        send(json)
    }

    override fun reconnect(matchId: String, playerId: String) {
        send(JSONObject().put("type", "reconnect").put("matchId", matchId).put("playerId", playerId))
    }

    override fun close() {
        socket?.close(1000, null)
        socket = null
    }

    private fun send(json: JSONObject) {
        val text = json.toString()
        if (open) socket?.send(text) else outbox.addLast(text)
    }

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    private inner class Handler2 : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = post {
            open = true
            while (outbox.isNotEmpty()) webSocket.send(outbox.removeFirst())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
            post { dispatch(msg) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            post { listener?.onClosed() }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            post { listener?.onError(t.message ?: "connection failed") }
        }
    }

    private fun dispatch(msg: JSONObject) {
        val l = listener ?: return
        when (msg.optString("type")) {
            "hello" -> {
                playerId = msg.optString("playerId")
                l.onHello(playerId ?: "")
            }
            "queued" -> l.onQueued(msg.optInt("players"), msg.optInt("waiting"))
            "matched" -> {
                val config = parseConfig(msg.getJSONObject("config"))
                matchId = config.matchId
                l.onMatched(config)
            }
            "move" -> l.onMove(parseMove(msg))
            "log" -> {
                val arr = msg.getJSONArray("moves")
                val moves = (0 until arr.length()).map { parseMove(arr.getJSONObject(it)) }
                l.onLog(msg.optString("matchId"), moves)
            }
            "peer" -> l.onPeer(msg.optString("event"), msg.optInt("player"))
            "pong" -> {}
            "error" -> l.onError(msg.optString("message"))
        }
    }

    private fun parseConfig(c: JSONObject): MatchConfig {
        val playersJson: JSONArray = c.getJSONArray("players")
        val seats = (0 until playersJson.length()).map {
            val p = playersJson.getJSONObject(it)
            MatchConfig.Seat(p.getInt("seat"), p.getString("id"), p.getString("name"))
        }
        return MatchConfig(
            matchId = c.getString("matchId"),
            seed = c.getLong("seed"),
            players = seats,
            you = c.getInt("you"),
        )
    }

    private fun parseMove(m: JSONObject): MoveMessage = MoveMessage(
        seq = if (m.has("seq")) m.getInt("seq") else null,
        player = m.getInt("player"),
        decisionId = m.optStringOrNull("decisionId"),
        choiceId = m.optStringOrNull("choiceId"),
        target = m.optStringOrNull("target"),
    )

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key) || !has(key)) null else optString(key)
}
