package com.dungeonboss.data

import com.dungeonboss.model.AbilityCard
import com.dungeonboss.model.Bait
import com.dungeonboss.model.BaitIcons
import com.dungeonboss.model.Boss
import com.dungeonboss.model.Hero
import com.dungeonboss.model.Room
import com.dungeonboss.model.Upgrade
import org.yaml.snakeyaml.Yaml
import java.io.InputStream

/**
 * Loads the card definitions and exposes the full pools of cards as model
 * objects: bosses, rooms, upgrades, advanced rooms, heroes, and ability cards.
 * Each definition may set `copies` (default 1) to put several identical cards in
 * the deck; the pools returned here are those physical cards, expanded into
 * *distinct* instances. Boss/room/ability `effect`s are carried as the raw
 * declarative map for the effect interpreters. Validates ids while building.
 * Holds no game-flow logic. Mirrors `webapp/lib/card_library.rb`.
 */
class CardLibrary(
    val bosses: List<Boss>,
    val rooms: List<Room>,
    val upgrades: List<Upgrade>,
    val advancedRooms: List<Room>,
    val heroes: List<Hero>,
    val abilityCards: List<AbilityCard>
) {
    companion object {
        fun load(input: InputStream): CardLibrary {
            input.use {
                @Suppress("UNCHECKED_CAST")
                val data = (Yaml().load<Any?>(it) as? Map<String, Any?>) ?: emptyMap()
                return from(data)
            }
        }

        fun from(data: Map<String, Any?>): CardLibrary = CardLibrary(
            bosses = expand(data["bosses"]) { buildBoss(it) },
            rooms = expand(data["rooms"]) { buildRoom(it, advanced = false) },
            upgrades = expand(data["upgrades"]) { buildUpgrade(it) },
            advancedRooms = expand(data["advanced_rooms"]) { buildRoom(it, advanced = true) },
            heroes = expand(data["heroes"]) { buildHero(it) },
            abilityCards = expand(data["ability_cards"]) { buildAbility(it) }
        )

        /**
         * Turn a list of definitions into the physical card pile: each definition
         * becomes `copies` distinct card objects (built fresh, so duplicate cards
         * are never the same instance). Definition ids must be unique.
         */
        private fun <T> expand(raw: Any?, build: (Map<String, Any?>) -> T): List<T> {
            @Suppress("UNCHECKED_CAST")
            val list = (raw as? List<Map<String, Any?>>) ?: emptyList()
            assertUniqueIds(list)
            val out = ArrayList<T>()
            for (entry in list) {
                val copies = (entry["copies"] as? Number)?.toInt() ?: 1
                repeat(copies) { out.add(build(entry)) }
            }
            return out
        }

        private fun buildBoss(c: Map<String, Any?>) = Boss(
            id = c.req("id"),
            name = c.req("name"),
            damage = (c["damage"] as Number).toInt(),
            bait = baitOf(c["bait"]),
            effect = effectOf(c["effect"]),
            tags = tagsOf(c["tags"]),
            abilityText = c["ability_text"]?.toString() ?: ""
        )

        private fun buildRoom(c: Map<String, Any?>, advanced: Boolean) = Room(
            id = c.req("id"),
            name = c.req("name"),
            type = c.req("type"),
            damage = (c["damage"] as Number).toInt(),
            bait = baitOf(c["bait"]),
            description = c["description"]?.toString() ?: "",
            effect = effectOf(c["effect"]),
            tags = tagsOf(c["tags"]),
            advanced = (c["advanced"] as? Boolean) ?: advanced,
            abilityText = c["ability_text"]?.toString() ?: ""
        )

        private fun buildUpgrade(c: Map<String, Any?>) = Upgrade(
            id = c.req("id"),
            name = c.req("name"),
            bonusDamage = (c["bonus_damage"] as? Number)?.toInt() ?: 0,
            bait = baitOf(c["bait"]),
            description = c["description"]?.toString() ?: "",
            tags = tagsOf(c["tags"])
        )

        private fun buildHero(c: Map<String, Any?>) = Hero(
            id = c.req("id"),
            name = c.req("name"),
            health = (c["health"] as Number).toInt(),
            preferredBait = Bait.normalize(c.req("preferred_bait")),
            courage = (c["courage"] as? Number)?.toInt() ?: 1,
            tags = tagsOf(c["tags"]),
            abilityText = c["ability_text"]?.toString() ?: ""
        )

        private fun buildAbility(c: Map<String, Any?>) = AbilityCard(
            id = c.req("id"),
            name = c.req("name"),
            text = c["text"]?.toString() ?: "",
            effect = effectOf(c["effect"]),
            tags = tagsOf(c["tags"])
        )

        private fun baitOf(raw: Any?): BaitIcons = BaitIcons(raw as? Map<*, *>)

        /** Carry the declarative `effect` map verbatim for the effect interpreters. */
        private fun effectOf(raw: Any?): Map<String, Any?> {
            @Suppress("UNCHECKED_CAST")
            return (raw as? Map<String, Any?>) ?: emptyMap()
        }

        /** Normalise a YAML `tags:` list into a lowercase set (blank/dupes dropped). */
        private fun tagsOf(raw: Any?): Set<String> =
            (raw as? List<*>)
                ?.mapNotNull { it?.toString()?.trim()?.lowercase()?.ifEmpty { null } }
                ?.toCollection(LinkedHashSet())
                ?: emptySet()

        private fun Map<String, Any?>.req(key: String): String =
            this[key]?.toString() ?: throw IllegalArgumentException("missing required field: $key")

        private fun assertUniqueIds(list: List<Map<String, Any?>>) {
            val ids = list.map { it["id"]?.toString() }
            val dupes = ids.filter { id -> ids.count { it == id } > 1 }.distinct()
            require(dupes.isEmpty()) { "duplicate card ids: ${dupes.joinToString(", ")}" }
        }
    }
}
