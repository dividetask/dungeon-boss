package com.dungeonboss.game

import com.dungeonboss.model.Boss
import com.dungeonboss.model.Encounter
import com.dungeonboss.model.PlacedRoom
import com.dungeonboss.model.Room
import com.dungeonboss.model.Upgrade

/**
 * One player's dungeon: a boss plus an ordered list of rooms. The entrance is
 * the leftmost room; the boss sits on the right. Rooms are added to the left, so
 * the dungeon grows toward the entrance. Holds at most [MAX_ROOMS] rooms.
 */
class Dungeon(val boss: Boss) {
    private val _rooms = mutableListOf<PlacedRoom>() // index 0 is the leftmost (entrance) room
    val rooms: List<PlacedRoom> get() = _rooms

    fun isFull(): Boolean = _rooms.size >= MAX_ROOMS

    /** Place a room to the left of the current leftmost room (new entrance). */
    fun addRoomToLeft(room: Room): Dungeon {
        check(!isFull()) { "dungeon is full (max $MAX_ROOMS rooms)" }
        _rooms.add(0, PlacedRoom(room))
        return this
    }

    /**
     * Replace the room at the given index, returning the old [PlacedRoom] (so the
     * caller can discard it; any upgrade on it is lost).
     */
    fun replaceRoom(index: Int, room: Room): PlacedRoom {
        val old = _rooms[index]
        _rooms[index] = PlacedRoom(room)
        return old
    }

    /**
     * Attach an upgrade to the room at the given index, returning the upgrade it
     * replaced (or null). Each room holds at most one upgrade.
     */
    fun applyUpgrade(index: Int, upgrade: Upgrade): Upgrade? {
        val placed = _rooms[index]
        val previous = placed.upgrade
        placed.upgrade = upgrade
        return previous
    }

    /** Replace the room list wholesale (used to undo a build placement). */
    fun restoreRooms(snapshot: List<PlacedRoom>) {
        _rooms.clear()
        _rooms.addAll(snapshot)
    }

    /** The leftmost (entrance) room, or null if none yet. */
    fun entrance(): PlacedRoom? = _rooms.firstOrNull()

    /** Encounters in crawl order: rooms left -> right, then the boss last. */
    fun encounters(): List<Encounter> = _rooms + boss

    companion object {
        const val MAX_ROOMS = 5
    }
}
