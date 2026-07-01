package com.dungeonboss.model

import kotlin.math.floor

/**
 * Card model: immutable value objects, mirroring the shared spec in docs/cards.md.
 * These are deliberately plain classes (not Kotlin `data class`es) so that two
 * physically distinct cards that share the same fields — e.g. two copies of the
 * Mage drawn into one party — remain *distinct* by identity. The deck builds
 * `copies` separate instances, and the party/crawl logic keys off object identity.
 *
 * Rooms use the **flat field schema** (v2): lead/all/rear damage with per-level
 * increments, a three-way [roomResist], a damage filter, unified poison, and the
 * level-driven growth/upgrade model. Bosses still carry a declarative [effect]
 * map interpreted by BossEffect (self bonus per point, room-bonus auras).
 */

/** Anything that can be referred to by id/name in a decision or in hand. */
interface Card {
    val id: String
    val name: String
}

/**
 * A crawl encounter exposes its damage channels, bait, a type and tags so the
 * resolver can apply a room and a boss uniformly without knowing which is which.
 * Each damage channel is `base + floor(increment * level)`; a plain card has
 * level 0. The boss only deals [leadDamage] (its printed damage).
 */
interface Encounter {
    val bait: BaitIcons
    val type: String? get() = null
    val tags: Set<String> get() = emptySet()

    /** The room's level (0 for a hand card or the boss). Scales every channel. */
    val level: Int get() = 0

    /** Declarative effect map — used by bosses only (rooms use the typed fields). */
    val effect: Map<String, Any?> get() = emptyMap()

    // --- base damage channels (before level scaling) ---
    val leadBase: Int get() = 0
    val leadIncrement: Double get() = 0.0
    val allBase: Int get() = 0
    val allIncrement: Double get() = 0.0
    val rearBase: Int get() = 0
    val rearIncrement: Double get() = 0.0

    // --- modifiers / flags ---
    /** If set, [damageAll] only hits heroes of this class (e.g. "mage"). */
    val damageFilter: String? get() = null

    /** null = normal, false = cannot be halved (Barbarian), true = cannot be reduced. */
    val roomResist: Boolean? get() = null

    /** Per discarded card during the crawl: +N to lead / all damage (temporary). */
    val discardLeadDamage: Int get() = 0
    val discardAllDamage: Int get() = 0

    /** Damage dealt to every hero this room damaged, in a later room (unreducible). */
    val poisonDamage: Int get() = 0

    /** false = lands once in the very next room; true = lands every later room. */
    val poisonPersists: Boolean get() = false

    /** How many times poison resolves AT this encounter (default 1; Maze = 3). */
    val poisonTicks: Int get() = 1

    /** Each hero death here raises this room's level by 1. */
    val growsOnDeath: Boolean get() = false

    /** The owner draws one room AND one ability card per hero that dies here. */
    val drawOnDeath: Boolean get() = false

    /** { match: {...}, amount: N } — +N to every other matching room. */
    val roomAura: Map<String, Any?>? get() = null

    fun trap(): Boolean = type?.lowercase()?.contains("trap") ?: false
    fun creature(): Boolean = type?.lowercase()?.let { it.contains("monster") || it.contains("creature") } ?: false

    // --- level-scaled channel values ---
    val leadDamage: Int get() = leadBase + floor(leadIncrement * level).toInt()
    val damageAll: Int get() = allBase + floor(allIncrement * level).toInt()
    val damageRear: Int get() = rearBase + floor(rearIncrement * level).toInt()

    /** Legacy single-target damage = the lead hit (used by the summary / legacy resolver). */
    val damage: Int get() = leadDamage

    /** The headline damage to show on a card: its primary (used) channel. */
    val displayDamage: Int get() = when {
        leadDamage > 0 -> leadDamage
        damageAll > 0 -> damageAll
        damageRear > 0 -> damageRear
        else -> leadDamage
    }

    /** Whether this card carries any special behaviour (for the ✨ marker). */
    val hasSpecial: Boolean
        get() = effect.isNotEmpty() || growsOnDeath || drawOnDeath || poisonDamage > 0 ||
            damageFilter != null || roomAura != null || roomResist != null ||
            discardLeadDamage > 0 || discardAllDamage > 0
}

/** A card that lives in the build deck / hand (a [Room]). */
sealed interface BuildCard : Card

/** One boss card's data: the back of a dungeon and the final crawl encounter. */
class Boss(
    override val id: String,
    override val name: String,
    damage: Int,
    override val bait: BaitIcons,
    override val effect: Map<String, Any?> = emptyMap(),
    override val tags: Set<String> = emptySet(),
    val abilityText: String = ""
) : Card, Encounter {
    /** The boss deals only lead damage (no level scaling). */
    override val leadBase: Int = damage
}

/** One room card's data: the body of a dungeon. */
class Room(
    override val id: String,
    override val name: String,
    override val type: String,
    override val bait: BaitIcons,
    override val leadBase: Int = 0,
    override val leadIncrement: Double = 0.0,
    override val allBase: Int = 0,
    override val allIncrement: Double = 0.0,
    override val rearBase: Int = 0,
    override val rearIncrement: Double = 0.0,
    override val damageFilter: String? = null,
    override val roomResist: Boolean? = null,
    override val discardLeadDamage: Int = 0,
    override val discardAllDamage: Int = 0,
    override val poisonDamage: Int = 0,
    override val poisonPersists: Boolean = false,
    override val poisonTicks: Int = 1,
    override val growsOnDeath: Boolean = false,
    override val drawOnDeath: Boolean = false,
    override val roomAura: Map<String, Any?>? = null,
    val description: String = "",
    override val tags: Set<String> = emptySet(),
    val advanced: Boolean = false,
    val abilityText: String = ""
) : BuildCard, Encounter

/**
 * One hero card's data: the adventurers who crawl dungeons. Fully data-driven —
 * no per-id code. A hero carries a mutable [level] (its only mutable field): it
 * is set to floor(round / 4) when the hero arrives, gains +1 each time the hero
 * survives a crawl, and persists until the hero dies. Three stats derive from it:
 *   maxHp           = startingHp + floor(level * hpLevelIncrement)
 *   courage         = startingCourage + level
 *   partyReduction  = partyDamageReduction + floor(level * partyDamageReductionLevelIncrement)
 */
class Hero(
    override val id: String,
    override val name: String,
    val preferredBait: Bait,
    val startingHp: Int,
    val startingCourage: Int = 1,
    val hpLevelIncrement: Double = 0.0,
    val selfDamageMultiplier: Double = 1.0,
    val partyDamageReduction: Int = 0,
    val partyDamageReductionLevelIncrement: Double = 0.0,
    /** When set, the party reduction only applies to encounters carrying this bait. */
    val damageBaitFilter: Bait? = null,
    /** When set, the party reduction only applies to rooms of this type ("trap"/"creature"). */
    val damageRoomTypeFilter: String? = null,
    val icon: String = "",
    val tags: Set<String> = emptySet(),
    val abilityText: String = ""
) : Card {
    /** The hero's current level (mutable; see the class doc). */
    var level: Int = 0

    /** Full (levelled) health: starting HP plus floored per-level growth. */
    val maxHp: Int get() = startingHp + floor(level * hpLevelIncrement).toInt()

    /** Combined into a party's courage; a per-class base that rises by 1 per level. */
    val courage: Int get() = startingCourage + level

    /** The levelled flat party-wide damage reduction this hero contributes. */
    val partyReduction: Int
        get() = partyDamageReduction + floor(level * partyDamageReductionLevelIncrement).toInt()
}

/** One ability card's data: held in hand and played before a crawl to alter it. */
class AbilityCard(
    override val id: String,
    override val name: String,
    val text: String = "",
    val effect: Map<String, Any?> = emptyMap(),
    val tags: Set<String> = emptySet()
) : Card

/**
 * A room as it sits in a dungeon: a base [Room] plus a [level] that starts at 0
 * and rises as the room is upgraded (a spent room card: +1 basic / +2 advanced)
 * or grows on death (+1 per kill). Every damage channel scales with the level.
 * Bait icons granted by upgrade-discards are merged into the room's bait.
 */
class PlacedRoom(val baseRoom: Room) : Encounter {
    /** The room's level (mutable). Scales every damage channel via the increments. */
    override var level: Int = 0

    /** Bait icons granted by room cards spent to upgrade this room. */
    private val grantedBait = LinkedHashMap<Bait, Int>()

    val id: String get() = baseRoom.id
    val name: String get() = baseRoom.name
    override val type: String get() = baseRoom.type
    val description: String get() = baseRoom.description

    override val tags: Set<String> get() = baseRoom.tags

    // The combat fields all delegate to the base room (scaling uses [level]).
    override val leadBase get() = baseRoom.leadBase
    override val leadIncrement get() = baseRoom.leadIncrement
    override val allBase get() = baseRoom.allBase
    override val allIncrement get() = baseRoom.allIncrement
    override val rearBase get() = baseRoom.rearBase
    override val rearIncrement get() = baseRoom.rearIncrement
    override val damageFilter get() = baseRoom.damageFilter
    override val roomResist get() = baseRoom.roomResist
    override val discardLeadDamage get() = baseRoom.discardLeadDamage
    override val discardAllDamage get() = baseRoom.discardAllDamage
    override val poisonDamage get() = baseRoom.poisonDamage
    override val poisonPersists get() = baseRoom.poisonPersists
    override val poisonTicks get() = baseRoom.poisonTicks
    override val growsOnDeath get() = baseRoom.growsOnDeath
    override val drawOnDeath get() = baseRoom.drawOnDeath
    override val roomAura get() = baseRoom.roomAura

    override val bait: BaitIcons
        get() {
            if (grantedBait.isEmpty()) return baseRoom.bait
            val totals = LinkedHashMap<Bait, Int>()
            baseRoom.bait.toMap().forEach { (b, c) -> totals[b] = (totals[b] ?: 0) + c }
            grantedBait.forEach { (b, c) -> totals[b] = (totals[b] ?: 0) + c }
            return BaitIcons(totals)
        }

    /**
     * Spend a basic/advanced room card to upgrade this room: it gains every bait
     * icon of the spent card, and its level rises by 1 (basic) or 2 (advanced).
     * The caller discards the spent card.
     */
    fun upgradeWith(card: Room) {
        card.bait.toMap().forEach { (b, c) -> grantedBait[b] = (grantedBait[b] ?: 0) + c }
        level += if (card.advanced) 2 else 1
    }

    /** A deep copy carrying the same level / granted bait (for undo). */
    fun copyState(): PlacedRoom {
        val copy = PlacedRoom(baseRoom)
        copy.level = level
        copy.grantedBait.putAll(grantedBait)
        return copy
    }
}
