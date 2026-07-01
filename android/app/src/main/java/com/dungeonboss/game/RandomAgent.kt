package com.dungeonboss.game

import com.dungeonboss.model.BuildCard
import com.dungeonboss.model.Room
import kotlin.random.Random

/**
 * An automated player. Given a [Decision], it picks at random and returns a
 * (choiceId, target) pair:
 *   - choiceId: the chosen card id, or null to skip. For DISCARD_ROOMS it is a
 *               comma-joined list of 0–2 room ids (null/blank = discard nothing).
 *   - target:   only meaningful for build decisions — a slot index (Int) to place
 *               a room, or "upgrade:<slot>" to spend a room card upgrading a
 *               placed room. Holds no game state.
 */
class RandomAgent(private val rng: Random = Random.Default) : Agent {

    override fun choose(decision: Decision): Pair<String?, Any?> = when (decision.kind) {
        DecisionKind.BUILD_ROOM -> buildMove(decision)
        DecisionKind.DISCARD_ROOMS -> discardMove(decision)
        DecisionKind.PLACE_FIRST_ROOM -> firstRoomMove(decision)
        else -> Pair(decision.options.randomOrNull(rng)?.id, null)
    }

    /** Discard a random 0–2 room cards from hand. */
    private fun discardMove(decision: Decision): Pair<String?, Any?> {
        val hand = decision.player.roomHand
        val max = minOf(2, hand.size)
        val count = rng.nextInt(max + 1) // 0..max
        val ids = hand.shuffled(rng).take(count).map { it.id }
        return Pair(ids.joinToString(",").ifEmpty { null }, null)
    }

    /** Place the first room into a random slot. */
    private fun firstRoomMove(decision: Decision): Pair<String?, Any?> {
        val card = decision.options.randomOrNull(rng) ?: return Pair(null, null)
        val slot = (decision.player.dungeon?.emptySlots()?.randomOrNull(rng)) ?: 0
        return Pair(card.id, slot)
    }

    /**
     * Randomly build nothing, place a room into a slot, place an advanced room on
     * a valid slot, or spend a room card to upgrade a placed room. Reads the live
     * hand (it may have changed since queuing).
     */
    private fun buildMove(decision: Decision): Pair<String?, Any?> {
        val dungeon = decision.player.dungeon ?: return Pair(null, null)
        val pool: List<BuildCard?> = decision.player.roomHand + listOf(null)
        val pick = pool.random(rng) ?: return Pair(null, null)

        val slots = dungeon.slots
        val occupied = slots.indices.filter { slots[it] != null }

        if (pick is Room && pick.advanced) {
            val empties = dungeon.emptySlots()
            val baitShare = occupied.filter { slots[it]!!.bait.shares(pick.bait) }
            val valid = empties + baitShare
            if (valid.isEmpty()) return Pair(null, null)
            return Pair(pick.id, valid.random(rng))
        }

        // A basic room: place into any slot, or spend it to upgrade an occupied room.
        val targets = mutableListOf<Any>()
        slots.indices.forEach { targets.add(it) }              // place into slot i
        occupied.forEach { targets.add("upgrade:$it") }        // upgrade the room in slot i
        return Pair(pick.id, targets.random(rng))
    }
}
