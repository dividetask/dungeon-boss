package com.dungeonboss.game

import com.dungeonboss.game.HeroAbility.Resist
import com.dungeonboss.model.Encounter
import com.dungeonboss.model.Hero
import com.dungeonboss.model.PlacedRoom

/**
 * Runs a whole party through a dungeon, applying the flat field schema. At each
 * encounter, in order: any active poison resolves (×[Encounter.poisonTicks]),
 * then this room's damage channels — `damage_all` (optionally filtered to a hero
 * class), then the `lead` hit on the highest-HP hero (overkill cascades to the
 * next-highest), then the `rear` hit on the most-injured hero. Each channel base
 * is `base + floor(increment * level)`. Boss self/room bonuses, room auras and
 * per-crawl modifiers fold into the room's primary channel. A room's [roomResist]
 * sets how a hero may reduce its damage; poison is always unreducible. A hero
 * damaged by a poison room is poisoned (persisting or one-shot). A grow-on-death
 * room rises one level per death; a draw-on-death room earns the owner one room
 * and one ability card per death. A Retreat modifier turns the party back before
 * its target room. Stateless entry point; uses an instance to carry crawl state.
 */
object PartyCrawlResolver {
    data class Step(
        val encounter: Encounter,
        val hero: Hero,
        val damage: Int,
        val healthAfter: Int,
        val died: Boolean,
        val roomIndex: Int
    )

    data class Result(
        val participants: List<Hero>,
        val log: List<Step>,
        val deaths: Int,
        val deadHeroes: List<Hero>,
        val survivors: List<Hero>,
        /** {"ability"|"room" -> count} the owner draws from death-triggered rooms. */
        val draws: Map<String, Int>
    )

    fun resolve(
        party: Party,
        dungeon: Dungeon,
        bossBonus: Int = 0,
        modifiers: CrawlModifiers = CrawlModifiers(),
        dryRun: Boolean = false
    ): Result = Run(party, dungeon, bossBonus, modifiers, dryRun).resolve()

    private data class Channels(val lead: Int, val all: Int, val rear: Int)

    private class Run(
        party: Party,
        private val dungeon: Dungeon,
        private val bossBonus: Int,
        private val mods: CrawlModifiers,
        // A dry run predicts the outcome without the one lasting side effect —
        // grow-on-death rooms do not permanently level up — so it is safe to run
        // repeatedly (e.g. to preview a crawl before it is committed).
        private val dryRun: Boolean = false
    ) {
        private val participants: List<Hero> = party.heroes.toList()
        private val health = HashMap<Hero, Int>()
        private val alive = participants.toMutableList()
        private val dead = mutableListOf<Hero>()
        private val poison = HashMap<Hero, Int>()    // hero -> persisting poison per room
        private val delayed = HashMap<Hero, Int>()   // hero -> one-shot poison next room
        private val draws = HashMap<String, Int>()   // deck name -> cards owner draws
        private val log = mutableListOf<Step>()

        private var roomIndex = 0
        private var deathsHere = 0

        init {
            for (hero in participants) health[hero] = hero.maxHp
        }

        fun resolve(): Result {
            val encounters = dungeon.encounters()
            for ((index, encounter) in encounters.withIndex()) {
                if (alive.isEmpty()) break
                if (mods.retreatsAt(index)) break // Retreat: the party turns back here

                roomIndex = index
                deathsHere = 0

                poisonTick(encounter)
                if (alive.isNotEmpty()) delayedTick(encounter)
                if (alive.isNotEmpty()) applyDamage(encounter)
                grow(encounter)
                recordDeathDraws(encounter)
            }
            return Result(
                participants = participants,
                log = log,
                deaths = dead.size,
                deadHeroes = dead,
                survivors = alive,
                draws = draws
            )
        }

        /** Highest current health, then highest max health, then earliest board order. */
        private fun pickHighest(): Hero =
            alive.maxWith(compareBy({ health.getValue(it) }, { it.maxHp }))

        /** Lowest current health (most injured), then lowest max health, then board order. */
        private fun pickLowest(): Hero =
            alive.minWith(compareBy({ health.getValue(it) }, { it.maxHp }))

        private fun poisonTick(encounter: Encounter) {
            val ticks = maxOf(1, encounter.poisonTicks)
            for (hero in alive.toList()) {
                val amount = poison[hero] ?: 0
                if (amount <= 0) continue
                repeat(ticks) { if (alive.contains(hero)) hit(hero, amount, encounter, Resist.NO_REDUCE) }
            }
        }

        private fun delayedTick(encounter: Encounter) {
            val ticks = maxOf(1, encounter.poisonTicks)
            for (hero in alive.toList()) {
                val amount = delayed[hero] ?: 0
                if (amount <= 0) continue
                repeat(ticks) { if (alive.contains(hero)) hit(hero, amount, encounter, Resist.NO_REDUCE) }
                delayed.remove(hero)
            }
        }

        private fun applyDamage(encounter: Encounter) {
            if (mods.isZero(roomIndex)) return
            val ch = channels(encounter)
            val resist = resistMode(encounter)

            // 1) Damage-all (optionally filtered to a hero class), each reduced
            //    independently so e.g. a Rogue protects the whole party.
            if (ch.all > 0) {
                val filter = encounter.damageFilter
                val targets = if (filter != null) alive.toList().filter { matchesFilter(it, filter) } else alive.toList()
                for (hero in targets) applyHitWithPoison(hero, ch.all, encounter, resist)
            }
            // 2) Lead hit on the highest-health hero; overkill cascades.
            if (ch.lead > 0) cascade(ch.lead, encounter, resist) { pickHighest() }
            // 3) Rear hit on the most-injured hero; overkill cascades.
            if (ch.rear > 0) cascade(ch.rear, encounter, resist) { pickLowest() }
        }

        /** Apply a hit that cascades its overkill to the next picked hero. */
        private fun cascade(amount: Int, encounter: Encounter, resist: Resist, pick: () -> Hero) {
            var remaining = amount
            while (remaining > 0 && alive.isNotEmpty()) {
                val target = pick()
                val before = health.getValue(target)
                val dealt = applyHitWithPoison(target, remaining, encounter, resist)
                if (dealt <= 0) break
                remaining = if (dealt > before) dealt - before else 0
            }
        }

        /** Hit one hero and apply this room's poison; returns damage dealt. */
        private fun applyHitWithPoison(hero: Hero, amount: Int, encounter: Encounter, resist: Resist): Int {
            val dealt = hit(hero, amount, encounter, resist)
            if (dealt <= 0) return 0
            if (alive.contains(hero) && encounter.poisonDamage > 0) {
                if (encounter.poisonPersists) {
                    poison[hero] = (poison[hero] ?: 0) + encounter.poisonDamage
                } else {
                    delayed[hero] = (delayed[hero] ?: 0) + encounter.poisonDamage
                }
            }
            return dealt
        }

        private fun recordDeathDraws(encounter: Encounter) {
            if (!encounter.drawOnDeath || deathsHere <= 0) return
            draws["room"] = (draws["room"] ?: 0) + deathsHere
            draws["ability"] = (draws["ability"] ?: 0) + deathsHere
        }

        /**
         * This encounter's three damage channels at its current level, with the
         * boss self/room bonus, room auras and per-crawl modifiers folded into the
         * single channel the room actually uses (its primary channel).
         */
        private fun channels(encounter: Encounter): Channels {
            var lead = encounter.leadDamage
            var all = encounter.damageAll
            var rear = encounter.damageRear

            val bossEffect = BossEffect.forBoss(dungeon.boss)
            val ext = if (encounter === dungeon.boss) {
                bossEffect.selfBonus(bossBonus) + mods.bonus(roomIndex)
            } else {
                bossEffect.roomBonus(encounter, bossBonus) + auraBonus(encounter) + mods.bonus(roomIndex)
            }

            when {
                lead > 0 || (all == 0 && rear == 0) -> {
                    if (mods.isSet(roomIndex)) lead = mods.setValue(roomIndex)
                    lead += ext
                }
                all > 0 -> {
                    if (mods.isSet(roomIndex)) all = mods.setValue(roomIndex)
                    all += ext
                }
                else -> {
                    if (mods.isSet(roomIndex)) rear = mods.setValue(roomIndex)
                    rear += ext
                }
            }
            return Channels(lead, all, rear)
        }

        /** Damage other rooms' auras grant to this room (never the granter itself). */
        private fun auraBonus(encounter: Encounter): Int =
            dungeon.rooms.sumOf { room ->
                if (room === encounter) 0 else RoomEffect.auraBonus(room, encounter, bossBonus)
            }

        /** This room's resist mode, with an Expose-Weakness modifier forcing NO_REDUCE. */
        private fun resistMode(encounter: Encounter): Resist {
            if (!mods.reducible(roomIndex)) return Resist.NO_REDUCE
            return when (encounter.roomResist) {
                true -> Resist.NO_REDUCE
                false -> Resist.NO_HALVE
                null -> Resist.NORMAL
            }
        }

        /** Whether [hero] is of the class named by a damage filter ("mage" etc.). */
        private fun matchesFilter(hero: Hero, filter: String): Boolean =
            hero.name.equals(filter, ignoreCase = true) ||
                hero.id.equals("hero_$filter", ignoreCase = true)

        private fun grow(encounter: Encounter) {
            if (dryRun) return // a preview must not permanently level the room
            if (deathsHere <= 0) return
            if (!encounter.growsOnDeath) return
            if (encounter is PlacedRoom) encounter.level += deathsHere
        }

        /** Deal damage to a hero and record it; returns the actual damage dealt. */
        private fun hit(hero: Hero, amount: Int, encounter: Encounter, resist: Resist): Int {
            if (amount <= 0) return 0

            val damage = HeroAbility.damageTaken(hero, encounter, alive, amount, resist)
            val after = health.getValue(hero) - damage
            health[hero] = after
            val died = after <= 0
            log.add(Step(encounter, hero, damage, after, died, roomIndex))
            if (died) {
                alive.removeAt(alive.indexOfFirst { it === hero })
                dead.add(hero)
                deathsHere += 1
            }
            return damage
        }
    }
}
