package com.dungeonboss.data

import com.dungeonboss.model.AbilityCard
import com.dungeonboss.model.Bait
import com.dungeonboss.model.BaitIcons
import com.dungeonboss.model.Boss
import com.dungeonboss.model.Hero
import com.dungeonboss.model.Room
import org.yaml.snakeyaml.Yaml
import java.io.InputStream

/**
 * Loads the card definitions and exposes the full pools of cards as model
 * objects: bosses, rooms, advanced rooms, heroes, and ability cards. Each
 * definition may set `copies` (default 1) to put several identical cards in the
 * deck; the pools returned here are those physical cards, expanded into
 * *distinct* instances. Rooms use the flat field schema; bosses carry a raw
 * declarative `effect` map for BossEffect. Validates ids while building.
 * Holds no game-flow logic.
 */
class CardLibrary(
    val bosses: List<Boss>,
    val rooms: List<Room>,
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
            damage = intOf(c["lead_damage"] ?: c["damage"]),
            bait = baitOf(c["bait"]),
            effect = effectOf(c["effect"]),
            tags = tagsOf(c["tags"]),
            abilityText = c["ability_text"]?.toString() ?: ""
        )

        private fun buildRoom(c: Map<String, Any?>, advanced: Boolean) = Room(
            id = c.req("id"),
            name = c.req("name"),
            type = c.req("type"),
            bait = baitOf(c["bait"]),
            leadBase = intOf(c["lead_damage"]),
            leadIncrement = dblOf(c["lead_damage_increment"]),
            allBase = intOf(c["damage_all"]),
            allIncrement = dblOf(c["damage_all_increment"]),
            rearBase = intOf(c["damage_rear"]),
            rearIncrement = dblOf(c["damage_rear_increment"]),
            damageFilter = c["damage_filter"]?.toString()?.trim()?.lowercase()?.ifEmpty { null },
            roomResist = boolOrNull(c["room_resist"]),
            discardLeadDamage = intOf(c["discard_lead_damage"]),
            discardAllDamage = intOf(c["discard_all_damage"]),
            poisonDamage = intOf(c["poison_damage"]),
            poisonPersists = boolOf(c["poison_persists"]),
            poisonTicks = (c["poison_ticks"] as? Number)?.toInt() ?: 1,
            growsOnDeath = boolOf(c["grows_on_death"]),
            drawOnDeath = boolOf(c["draw_on_death"]),
            roomAura = mapOrNull(c["room_aura"]),
            description = c["description"]?.toString() ?: "",
            tags = tagsOf(c["tags"]),
            advanced = (c["advanced"] as? Boolean) ?: advanced,
            startInDiscard = intOf(c["start_in_discard"]),
            abilityText = c["ability_text"]?.toString() ?: ""
        )

        private fun buildHero(c: Map<String, Any?>) = Hero(
            id = c.req("id"),
            name = c.req("name"),
            preferredBait = Bait.normalize(c.req("preferred_bait")),
            startingHp = (c["starting_hp"] as Number).toInt(),
            startingCourage = (c["starting_courage"] as? Number)?.toInt() ?: 1,
            hpLevelIncrement = (c["hp_level_increment"] as? Number)?.toDouble() ?: 0.0,
            selfDamageMultiplier = (c["self_damage_multiplier"] as? Number)?.toDouble() ?: 1.0,
            partyDamageReduction = (c["party_damage_reduction"] as? Number)?.toInt() ?: 0,
            partyDamageReductionLevelIncrement =
                (c["party_damage_reduction_level_increment"] as? Number)?.toDouble() ?: 0.0,
            damageBaitFilter = c["damage_bait_filter"]?.let { Bait.normalize(it) },
            damageRoomTypeFilter = c["damage_room_type_filter"]?.toString()?.lowercase(),
            icon = c["icon"]?.toString() ?: "",
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

        private fun intOf(raw: Any?): Int = when (raw) {
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull() ?: 0
            else -> 0
        }

        private fun dblOf(raw: Any?): Double = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull() ?: 0.0
            else -> 0.0
        }

        private fun boolOf(raw: Any?): Boolean = when (raw) {
            is Boolean -> raw
            is String -> raw.equals("true", ignoreCase = true)
            else -> false
        }

        /** null when the key is absent/null; otherwise the boolean value (false stays false). */
        private fun boolOrNull(raw: Any?): Boolean? = when (raw) {
            is Boolean -> raw
            is String -> raw.equals("true", ignoreCase = true)
            else -> null
        }

        @Suppress("UNCHECKED_CAST")
        private fun mapOrNull(raw: Any?): Map<String, Any?>? = raw as? Map<String, Any?>

        /** Carry the declarative `effect` map verbatim (bosses / ability cards). */
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
