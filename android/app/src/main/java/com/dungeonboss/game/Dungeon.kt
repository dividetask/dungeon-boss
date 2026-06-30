package com.dungeonboss.game

import com.dungeonboss.model.Boss
import com.dungeonboss.model.Encounter
import com.dungeonboss.model.PlacedRoom
import com.dungeonboss.model.Room
import com.dungeonboss.model.Upgrade

/**
 * One player's dungeon: a boss plus [SLOTS] ordered room slots, any of which may
 * be empty. The entrance is the leftmost slot (index 0); the boss sits on the
 * right. A room may be placed into any slot (an empty slot is filled, an occupied
 * slot is replaced). The crawl runs through the occupied rooms left -> right
 * (skipping empties), then the boss. Mirrors `webapp/lib/dungeon.rb`.
 */
class Dungeon(val boss: Boss) {
    // null = an empty slot; index 0 is the leftmost (entrance) slot.
    private val _slots = arrayOfNulls<PlacedRoom>(SLOTS)

    /** All 5 slots in order, empties as null (for the build UI). */
    val slots: List<PlacedRoom?> get() = _slots.toList()

    /** Occupied rooms in slot order (left -> right), skipping empties. */
    val rooms: List<PlacedRoom> get() = _slots.filterNotNull()

    /** True when every slot is occupied (a new room can then only replace one). */
    fun isFull(): Boolean = _slots.all { it != null }

    /** Indices of the currently empty slots. */
    fun emptySlots(): List<Int> = _slots.indices.filter { _slots[it] == null }

    /**
     * Place a room into the given slot. An empty slot is filled; an occupied slot
     * is replaced, returning the old [PlacedRoom] (so the caller can discard it;
     * any upgrade on it is lost).
     */
    fun placeRoom(slot: Int, room: Room): PlacedRoom? {
        val old = _slots[slot]
        _slots[slot] = PlacedRoom(room)
        return old
    }

    /**
     * Attach an upgrade to the (occupied) room in [slot], returning the upgrade it
     * replaced (or null). Each room holds at most one upgrade.
     */
    fun applyUpgrade(slot: Int, upgrade: Upgrade): Upgrade? {
        val placed = requireNotNull(_slots[slot]) { "no room in slot $slot to upgrade" }
        val previous = placed.upgrade
        placed.upgrade = upgrade
        return previous
    }

    /**
     * Spend a basic/advanced room card to upgrade the (occupied) room in [slot]:
     * it gains the card's bait icons and its level rises by 1 (basic) or 2
     * (advanced). The caller discards the spent card.
     */
    fun upgradeRoomWith(slot: Int, card: Room) {
        val placed = requireNotNull(_slots[slot]) { "no room in slot $slot to upgrade" }
        placed.upgradeWith(card)
    }

    /** Deep-copy the 5 slots so a build placement can be fully undone. */
    fun snapshotSlots(): List<PlacedRoom?> = _slots.map { it?.copyState() }

    /** Restore a previously captured slot snapshot (used to undo a build). */
    fun restoreSlots(snapshot: List<PlacedRoom?>) {
        for (i in _slots.indices) _slots[i] = snapshot.getOrNull(i)
    }

    /** The leftmost occupied (entrance) room, or null if none yet. */
    fun entrance(): PlacedRoom? = rooms.firstOrNull()

    /** Encounters in crawl order: occupied rooms left -> right, then the boss last. */
    fun encounters(): List<Encounter> = rooms + boss

    companion object {
        const val SLOTS = 5
        const val MAX_ROOMS = 5
    }
}
