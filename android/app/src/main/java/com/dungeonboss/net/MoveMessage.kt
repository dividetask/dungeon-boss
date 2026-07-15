package com.dungeonboss.net

/**
 * One player's answer to one `Decision`, the only in-game message. Carries just
 * the *input* — never game state — because every device recomputes the game
 * itself (lockstep; see docs/networking.md).
 *
 *  - [seq]        the server-assigned global order; null on an outbound submit
 *                 (the server stamps it), set on every inbound move.
 *  - [player]     the seat (0..3) that made the choice.
 *  - [decisionId] which decision this answers — a device asserts it matches the
 *                 decision its own game is currently waiting on, catching desync.
 *  - [choiceId]   the chosen card id, a comma-joined list for a discard, or null
 *                 to skip.
 *  - [target]     a placement target (slot index as a string, or "upgrade:<slot>")
 *                 or null.
 */
data class MoveMessage(
    val seq: Int?,
    val player: Int,
    val decisionId: String?,
    val choiceId: String?,
    val target: String?,
)
