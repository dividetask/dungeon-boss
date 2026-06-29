package com.dungeonboss.game

import kotlin.random.Random

/**
 * A drawable, discardable, shuffleable pile of cards. Generic over card type.
 * Knows nothing about game rules — only how to draw and discard. Reshuffles its
 * discard pile back in when drawn from empty.
 */
class Deck<T>(cards: List<T> = emptyList(), private val rng: Random = Random.Default) {
    private var drawPile: MutableList<T> = cards.toMutableList()
    private var discardPile: MutableList<T> = mutableListOf()

    fun shuffle(): Deck<T> {
        drawPile.shuffle(rng)
        return this
    }

    /** True only when both piles are empty (nothing left to draw or reshuffle). */
    fun isEmpty(): Boolean = drawPile.isEmpty() && discardPile.isEmpty()

    fun size(): Int = drawPile.size

    /**
     * Draw a single card. If the draw pile is empty, reshuffle the discard pile
     * back into it first. Returns null only when both piles are empty.
     */
    fun draw(): T? {
        if (drawPile.isEmpty()) reshuffleDiscards()
        return if (drawPile.isEmpty()) null else drawPile.removeAt(0)
    }

    /** Draw up to n cards (fewer if the pile runs out). */
    fun drawMany(n: Int): List<T> = (0 until n).mapNotNull { draw() }

    fun discard(card: T?): Deck<T> {
        if (card != null) discardPile.add(card)
        return this
    }

    /**
     * Take a specific card back out of the discard pile (e.g. to undo a discard).
     * Returns the card if it was there (by identity), else null.
     */
    fun reclaim(card: T): T? {
        val index = discardPile.indexOfFirst { it === card }
        return if (index >= 0) { discardPile.removeAt(index); card } else null
    }

    private fun reshuffleDiscards() {
        if (discardPile.isEmpty()) return
        drawPile = discardPile.shuffled(rng).toMutableList()
        discardPile = mutableListOf()
    }
}
