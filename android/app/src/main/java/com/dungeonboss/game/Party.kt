package com.dungeonboss.game

import com.dungeonboss.model.Hero

/**
 * A group of one or more heroes that bait, crawl, and score together. A lone
 * hero is simply a party of one. Heroes are held in board order (the order they
 * were added), which is the final tie-breaker when an encounter chooses a target.
 */
class Party(heroes: List<Hero>, var name: String? = null) {
    private val _heroes: MutableList<Hero> = heroes.toMutableList()
    val heroes: List<Hero> get() = _heroes

    fun named(): Boolean = name != null
    fun size(): Int = _heroes.size
    fun lone(): Boolean = _heroes.size == 1
    fun isEmpty(): Boolean = _heroes.isEmpty()

    /** Combined courage of all members (used for the dungeon courage check). */
    fun courage(): Int = _heroes.sumOf { it.courage }

    fun add(hero: Hero): Party {
        _heroes.add(hero)
        return this
    }

    /** Remove a single hero (e.g. when it dies). Identity-based, like Ruby. */
    fun remove(hero: Hero): Party {
        val index = _heroes.indexOfFirst { it === hero }
        if (index >= 0) _heroes.removeAt(index)
        return this
    }

    /** The generated name if any, else the members joined, e.g. "Cleric & Mage". */
    fun displayName(): String = name ?: _heroes.joinToString(" & ") { it.name }

    fun roster(): String = _heroes.joinToString(" & ") { it.name }
}
