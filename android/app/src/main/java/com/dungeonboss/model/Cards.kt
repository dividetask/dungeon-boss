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

/** One hero card's data: the adventurers who crawl dungeons. */
class Hero(
    override val id: String,
    override val name: String,
    val health: Int,
    val preferredBait: Bait,
    val courage: Int = 1,
    val tags: Set<String> = emptySet(),
    val abilityText: String = ""
) : Card

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
            val up = upgrade ?: return baseRoom.bait
            val totals = LinkedHashMap<Bait, Int>()
            baseRoom.bait.toMap().forEach { (b, c) -> totals[b] = (totals[b] ?: 0) + c }
            up.bait.toMap().forEach { (b, c) -> totals[b] = (totals[b] ?: 0) + c }
            return BaitIcons(totals)
        }
}
