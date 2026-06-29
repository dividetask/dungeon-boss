package com.dungeonboss.game

import com.dungeonboss.model.AbilityCard
import com.dungeonboss.model.BuildCard

/**
 * A participant in the game. Holds the player's dungeon, hand of room cards (and
 * ability cards, unused in v1), and score. Holds no phase logic.
 */
class Player(val name: String) {
    var dungeon: Dungeon? = null
    val roomHand: MutableList<BuildCard> = mutableListOf()
    val abilityHand: MutableList<AbilityCard> = mutableListOf()
    var points: Int = 0
    var wounds: Int = 0

    /** Add a room to hand unless the hand is at its cap. Returns true if added. */
    fun addRoomToHand(card: BuildCard?): Boolean {
        if (card == null || roomHandFull()) return false
        roomHand.add(card)
        return true
    }

    fun roomHandFull(): Boolean = roomHand.size >= MAX_ROOM_HAND

    fun addAbilityToHand(card: AbilityCard?) {
        if (card != null) abilityHand.add(card)
    }

    /** Remove and return a room/upgrade card from hand by id, or null if not held. */
    fun takeRoomFromHand(cardId: String): BuildCard? {
        val index = roomHand.indexOfFirst { it.id == cardId }
        return if (index >= 0) roomHand.removeAt(index) else null
    }

    /** Remove and return an ability card from hand by id, or null if not held. */
    fun takeAbilityFromHand(cardId: String): AbilityCard? {
        val index = abilityHand.indexOfFirst { it.id == cardId }
        return if (index >= 0) abilityHand.removeAt(index) else null
    }

    fun gainPoint() {
        points += 1
    }

    fun gainWound() {
        wounds += 1
    }

    companion object {
        const val MAX_ROOM_HAND = 6
    }
}
