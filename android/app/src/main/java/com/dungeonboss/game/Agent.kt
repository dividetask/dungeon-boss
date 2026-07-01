package com.dungeonboss.game

/**
 * An automated player. Given a [Decision] it returns a (choiceId, target) pair:
 *   - choiceId: the chosen card id, or null to skip
 *   - target:   only meaningful for build decisions — "new" to add a room, or a
 *               room index to replace / attach to.
 *
 * [RandomAgent] picks at random; [LogicAgent] follows declarative heuristics.
 * The [Game] calls [attach] once when it wires up its agents, so an agent that
 * needs shared state (e.g. the town, for simulations) can capture it. The
 * default is a no-op, so simple agents ignore it.
 */
interface Agent {
    fun choose(decision: Decision): Pair<String?, Any?>

    fun attach(game: Game) {}
}
