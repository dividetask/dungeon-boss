package com.dungeonboss.model

/**
 * Card model: immutable value objects, mirroring the reference engine in
 * webapp/lib. These are deliberately plain classes (not Kotlin `data class`es)
 * so that two physically distinct cards that happen to share the same fields —
 * e.g. two copies of the Mage drawn into one party — remain *distinct* by
 * identity, exactly as the Ruby objects do. The deck builds `copies` separate
 * instances, and the party/crawl logic keys off object identity.
 *
 * Boss/room/ability `effect`s are **declarative data**: the raw map loaded from
 * `data/cards/` is carried here verbatim and interpreted by the BossEffect /
 * RoomEffect / AbilityEffect helpers (see docs/cards.md). `ability_text` is
 * flavor only.
 */

/** Anything that can be referred to by id/name in a decision or in hand. */
interface Card {
    val id: String
    val name: String
}

/**
 * A crawl encounter exposes damage, bait, a type, tags and a declarative
 * `effect`, so the resolver can apply a room and a boss uniformly without
 * knowing which is which.
 */
interface Encounter {
    val damage: Int
    val bait: BaitIcons
    val type: String? get() = null

    /** Classification tags (e.g. "goblin") so effects can target a whole group. */
    val tags: Set<String> get() = emptySet()

    /** Raw declarative effect spec (interpreted by RoomEffect / BossEffect). */
    val effect: Map<String, Any?> get() = emptyMap()

    /** True when the encounter's type names a trap. */
    fun trap(): Boolean = type?.lowercase()?.contains("trap") ?: false

    /** True when the encounter's type names a monster or creature. */
    fun creature(): Boolean = type?.lowercase()?.let { it.contains("monster") || it.contains("creature") } ?: false
}

/** A card that lives in the build deck / hand: a [Room] or an [Upgrade]. */
sealed interface BuildCard : Card

/** One boss card's data: the back of a dungeon and the final crawl encounter. */
class Boss(
    override val id: String,
    override val name: String,
    override val damage: Int,
    override val bait: BaitIcons,
    override val effect: Map<String, Any?> = emptyMap(),
    override val tags: Set<String> = emptySet(),
    val abilityText: String = ""
) : Card, Encounter

/** One room card's data: the body of a dungeon. */
class Room(
    override val id: String,
    override val name: String,
    override val type: String,
    override val damage: Int,
    override val bait: BaitIcons,
    val description: String = "",
    override val effect: Map<String, Any?> = emptyMap(),
    override val tags: Set<String> = emptySet(),
    val advanced: Boolean = false,
    val abilityText: String = ""
) : BuildCard, Encounter

/**
 * An upgrade card. Drawn from the same deck as rooms, but instead of being
 * placed it attaches to an existing room to boost it (bonus damage / bait).
 * Any tags it carries merge onto the room it upgrades.
 */
class Upgrade(
    override val id: String,
    override val name: String,
    val bonusDamage: Int = 0,
    val bait: BaitIcons,
    val description: String = "",
    val tags: Set<String> = emptySet()
) : BuildCard {
    val type: String get() = "Upgrade"
}

/**
 * One hero card's data: the adventurers who crawl dungeons. Fully data-driven —
 * no per-id code. A hero carries a mutable [level] (its only mutable field): it
 * is set to floor(round / 4) when the hero arrives, gains +1 each time the hero
 * survives a crawl, and persists until the hero dies. Three stats derive from it:
 *   maxHp           = startingHp + floor(level * hpLevelIncrement)
 *   courage         = 1 + level                      (uniform base 1)
 *   partyReduction  = partyDamageReduction + floor(level * partyDamageReductionLevelIncrement)
 * See docs/cards.md (Levelling and derived stats).
 */
class Hero(
    override val id: String,
    override val name: String,
    val preferredBait: Bait,
    val startingHp: Int,
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
    val maxHp: Int get() = startingHp + kotlin.math.floor(level * hpLevelIncrement).toInt()

    /** Combined into a party's courage; rises by 1 per level (uniform base 1). */
    val courage: Int get() = 1 + level

    /** The levelled flat party-wide damage reduction this hero contributes. */
    val partyReduction: Int
        get() = partyDamageReduction + kotlin.math.floor(level * partyDamageReductionLevelIncrement).toInt()
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
 * A room as it sits in a dungeon: a base [Room] plus at most one [Upgrade], plus
 * a permanent [grow] bonus (from grow-on-death effects). Exposes the *effective*
 * damage and bait (base + upgrade + grow) so resolvers, bait counting and the
 * summary can treat it just like a room.
 */
class PlacedRoom(
    val baseRoom: Room,
    var upgrade: Upgrade? = null
) : Encounter {
    /** Permanent damage gained from grow-on-death effects (never reset). */
    var grow: Int = 0

    /**
     * The room's level, raised when a room card is spent to upgrade it (+1 for a
     * basic room, +2 for an advanced one). Permanent. The gameplay effect of a
     * room's level is being implemented on a separate branch; for now this only
     * records it (and the granted bait icons below).
     */
    var level: Int = 0

    /** Bait icons granted by room cards spent to upgrade this room. */
    private val grantedBait = LinkedHashMap<Bait, Int>()

    val id: String get() = baseRoom.id
    val name: String get() = baseRoom.name
    override val type: String get() = baseRoom.type
    val description: String get() = baseRoom.description
    override val effect: Map<String, Any?> get() = baseRoom.effect

    /** The base room's tags plus any the attached upgrade contributes. */
    override val tags: Set<String>
        get() = upgrade?.let { baseRoom.tags + it.tags } ?: baseRoom.tags

    override val damage: Int
        get() = baseRoom.damage + (upgrade?.bonusDamage ?: 0) + grow

    override val bait: BaitIcons
        get() {
            if (upgrade == null && grantedBait.isEmpty()) return baseRoom.bait
            val totals = LinkedHashMap<Bait, Int>()
            baseRoom.bait.toMap().forEach { (b, c) -> totals[b] = (totals[b] ?: 0) + c }
            upgrade?.bait?.toMap()?.forEach { (b, c) -> totals[b] = (totals[b] ?: 0) + c }
            grantedBait.forEach { (b, c) -> totals[b] = (totals[b] ?: 0) + c }
            return BaitIcons(totals)
        }

    /**
     * Spend a basic or advanced room card to upgrade this room: it gains every
     * bait icon of the spent card, and its level rises by 1 (basic) or 2
     * (advanced). The caller discards the spent card.
     */
    fun upgradeWith(card: Room) {
        card.bait.toMap().forEach { (b, c) -> grantedBait[b] = (grantedBait[b] ?: 0) + c }
        level += if (card.advanced) 2 else 1
    }

    /** A deep copy carrying the same upgrade/grow/level/granted bait (for undo). */
    fun copyState(): PlacedRoom {
        val copy = PlacedRoom(baseRoom, upgrade)
        copy.grow = grow
        copy.level = level
        copy.grantedBait.putAll(grantedBait)
        return copy
    }
}
