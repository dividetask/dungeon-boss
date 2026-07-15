package com.dungeonboss.net

/**
 * The start parameters the matchmaking server hands out in a `matched` message.
 * Every device in a match receives the identical config and builds the identical
 * `Game.seeded(seed)` from it (see docs/networking.md). Immutable.
 *
 *  - [seed]    the shared PRNG seed the server minted; seeds every shuffle.
 *  - [players] the seats in fixed order (seat 0 first); names are consistent
 *              across devices, which keeps each device's game identical.
 *  - [you]     which seat is local — the human on this device. Every other seat
 *              is driven by incoming moves (a remote player).
 */
data class MatchConfig(
    val matchId: String,
    val seed: Long,
    val players: List<Seat>,
    val you: Int,
) {
    data class Seat(val seat: Int, val id: String, val name: String)

    /** Player display names in seat order — the list passed to `Game.seeded`. */
    fun playerNames(): List<String> = players.sortedBy { it.seat }.map { it.name }

    val localName: String get() = players.first { it.seat == you }.name
}
