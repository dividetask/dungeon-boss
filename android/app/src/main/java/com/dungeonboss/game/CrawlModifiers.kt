package com.dungeonboss.game

/**
 * Per-crawl modifiers produced by ability cards and discard-to-boost decisions
 * made just before a party crawls. They affect only that one crawl, and are
 * keyed by room index within the dungeon's encounters. Mirrors
 * `webapp/lib/crawl_modifiers.rb`.
 */
class CrawlModifiers {
    private val plus = HashMap<Int, Int>()          // +damage (Reinforcements / boost)
    private val zeroed = HashMap<Int, Boolean>()    // damage forced to 0 (Sabotage)
    private val setTo = HashMap<Int, Int>()         // damage overridden (discard-to-boost)
    private val unreducibleAt = HashMap<Int, Boolean>() // damage cannot be reduced
    private var retreatAt: Int? = null              // party turns back at this index (Retreat)

    /** A deep copy, so a play can be snapshotted and restored on undo. */
    fun copy(): CrawlModifiers {
        val c = CrawlModifiers()
        c.plus.putAll(plus)
        c.zeroed.putAll(zeroed)
        c.setTo.putAll(setTo)
        c.unreducibleAt.putAll(unreducibleAt)
        c.retreatAt = retreatAt
        return c
    }

    fun addDamage(index: Int, amount: Int = 2) {
        plus[index] = (plus[index] ?: 0) + amount
    }

    fun zero(index: Int) { zeroed[index] = true }
    fun setDamage(index: Int, value: Int) { setTo[index] = value }
    fun unreducibleMark(index: Int) { unreducibleAt[index] = true }
    fun retreat(index: Int) { retreatAt = index }

    fun retreating(): Boolean = retreatAt != null
    fun retreatIndex(): Int? = retreatAt
    fun retreatsAt(index: Int): Boolean = retreatAt?.let { index >= it } ?: false

    fun isZero(index: Int): Boolean = zeroed[index] ?: false
    fun isSet(index: Int): Boolean = setTo.containsKey(index)
    fun setValue(index: Int): Int = setTo.getValue(index)
    fun reducible(index: Int): Boolean = !(unreducibleAt[index] ?: false)
    fun bonus(index: Int): Int = plus[index] ?: 0

    /** Whether a per-room boost/override has already been applied (once per room). */
    fun boosted(index: Int): Boolean =
        setTo.containsKey(index) || (unreducibleAt[index] ?: false) ||
            (zeroed[index] ?: false) || (plus[index] ?: 0) > 0
}
