package com.dungeonboss

import com.dungeonboss.data.CardLibrary
import com.dungeonboss.game.RandomAgent
import com.dungeonboss.net.MatchConfig
import com.dungeonboss.net.MoveMessage
import com.dungeonboss.net.OnlineMatch
import com.dungeonboss.net.Transport
import com.dungeonboss.net.TransportListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * End-to-end proof that two devices playing over the network stay in lockstep
 * (docs/networking.md). Two [OnlineMatch] instances — each with its own card
 * library and its own local seat — are wired to an in-process [FakeRelay] that
 * mimics the real server: it auto-matches the two, mints one shared seed, and
 * sequences every move. Each device answers only its own seat's decisions (a
 * seeded RandomAgent stands in for the human). Driven to game over, the two games
 * must be byte-identical (via `exportJson`) at every step.
 *
 * This exercises the whole client model: submit-then-apply-on-echo, ordered move
 * application, and the local drive of the input-free phases (crawl, recharge,
 * next round) — without any real network.
 */
class OnlineMatchTest {

    @Test
    fun twoDevicesStayInLockstep() {
        val relay = FakeRelay(seed = 8675309L)
        val a = Device(relay, "Ada", seat = 0, agentSeed = 11L)
        val b = Device(relay, "Bo", seat = 1, agentSeed = 22L)

        a.transport.queue("Ada", 2)
        b.transport.queue("Bo", 2) // second queue forms the table → both onMatched

        assertTrue("both devices should be matched", a.match != null && b.match != null)
        assertEquals("diverged at the opening deal", a.json(), b.json())

        var step = 0
        while (!a.match!!.over() && step++ < 100_000) {
            // Exactly one device owns the current decision; it answers, the relay
            // sequences it, and both devices apply it before we compare.
            val acted = a.act() || b.act()
            if (!acted) break
            assertEquals("diverged at step $step", a.json(), b.json())
        }

        assertTrue("game did not finish within the step bound", a.match!!.over())
        assertEquals(a.json(), b.json())
    }

    // --- harness --------------------------------------------------------------

    /** One simulated device: its transport, its OnlineMatch, and a stand-in human. */
    private inner class Device(
        private val relay: FakeRelay,
        val name: String,
        val seat: Int,
        agentSeed: Long,
    ) {
        val transport = FakeTransport(relay)
        var match: OnlineMatch? = null
        private val agent = RandomAgent(Random(agentSeed))

        init {
            transport.listener = object : TransportListener {
                override fun onMatched(config: MatchConfig) {
                    match = OnlineMatch(library(), config, transport)
                }
                override fun onMove(move: MoveMessage) {
                    match?.onMove(move)
                }
            }
            relay.register(this)
        }

        /** If it is our turn, answer with the stand-in agent and submit. */
        fun act(): Boolean {
            val m = match ?: return false
            val d = m.localDecision() ?: return false
            val (choiceId, target) = agent.choose(d)
            m.submitLocal(choiceId, target)
            return true
        }

        fun json(): String = match!!.game.exportJson()
    }

    /** In-process stand-in for the matchmaking server: pairs devices, sequences moves. */
    private inner class FakeRelay(private val seed: Long) {
        private val devices = mutableListOf<Device>()
        private var nextSeq = 0
        private var matched = false

        fun register(device: Device) {
            devices.add(device)
        }

        fun queue(from: FakeTransport) {
            if (matched || devices.size < 2) return
            if (devices.all { it.transport.queued }) formMatch()
        }

        private fun formMatch() {
            matched = true
            val seats = devices.mapIndexed { i, d -> MatchConfig.Seat(i, "id-$i", d.name) }
            for (d in devices) {
                d.transport.listener?.onMatched(
                    MatchConfig(matchId = "match-1", seed = seed, players = seats, you = d.seat),
                )
            }
        }

        /** Stamp a seq and broadcast the ordered move to every device (incl. sender). */
        fun submit(move: MoveMessage) {
            val stamped = move.copy(seq = nextSeq++)
            for (d in devices) d.transport.listener?.onMove(stamped)
        }
    }

    /** A [Transport] backed by the in-process relay. */
    private inner class FakeTransport(private val relay: FakeRelay) : Transport {
        override var listener: TransportListener? = null
        var queued = false

        override fun queue(name: String, players: Int) {
            queued = true
            relay.queue(this)
        }
        override fun cancel() { queued = false }
        override fun submitMove(move: MoveMessage) = relay.submit(move)
        override fun reconnect(matchId: String, playerId: String) {}
        override fun close() {}
    }

    /** A fresh library (distinct card instances) — never shared between devices. */
    private fun library(): CardLibrary {
        val candidates = listOf(
            "src/main/assets/cards.yaml",
            "app/src/main/assets/cards.yaml",
            "android/app/src/main/assets/cards.yaml",
        )
        val file = candidates.map(::File).firstOrNull { it.exists() }
            ?: error("cards.yaml not found from ${File(".").absolutePath}")
        return file.inputStream().use { CardLibrary.load(it) }
    }
}
