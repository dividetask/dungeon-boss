package com.dungeonboss.game

import com.dungeonboss.model.BuildCard
import com.dungeonboss.model.Room
import com.dungeonboss.model.Upgrade
import kotlin.random.Random

/**
 * An automated player. Given a [Decision], it picks at random and returns a
 * (choiceId, target) pair:
 *   - choiceId: the chosen card id, or null to skip
 *   - target:   only meaningful for build decisions — "new" to add a room, or a
 *               room index to replace / attach to. Holds no game state.
 */
class RandomAgent(private val rng: Random = Random.Default) {

    fun choose(decision: Decision): Pair<String?, Any?> = when (decision.kind) {
        DecisionKind.BUILD_ROOM -> buildMove(decision)
        DecisionKind.DISCARD_ROOM ->
            Pair(decision.player.roomHand.randomOrNull(rng)?.id, null)
        else ->
            Pair(decision.options.randomOrNull(rng)?.id, null)
    }

    /**
     * Randomly build nothing, play a basic room (add/replace), attach an upgrade
     * to an existing room, or place an advanced room on a bait-sharing room.
     * Reads the live hand (it may have changed since queuing).
     */
    private fun buildMove(decision: Decision): Pair<String?, Any?> {
        val pool: List<BuildCard?> = decision.player.roomHand + listOf(null)
        val pick = pool.random(rng) ?: return Pair(null, null)

        val dungeon = decision.player.dungeon ?: return Pair(null, null)
        if (pick is Upgrade) {
            if (dungeon.rooms.isEmpty()) return Pair(null, null)
            return Pair(pick.id, rng.nextInt(dungeon.rooms.size)) // a room index to attach to
        }

        if (pick is Room && pick.advanced) {
            val indexes = dungeon.rooms.indices.filter { dungeon.rooms[it].bait.shares(pick.bait) }
            if (indexes.isEmpty()) return Pair(null, null) // nowhere valid to place it
            return Pair(pick.id, indexes.random(rng))
        }

        val targets = mutableListOf<Any>()
        if (!dungeon.isFull()) targets.add("new")
        dungeon.rooms.indices.forEach { targets.add(it) }
        return Pair(pick.id, targets.random(rng))
    }
}
