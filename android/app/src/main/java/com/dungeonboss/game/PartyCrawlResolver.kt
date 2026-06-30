package com.dungeonboss.game

import com.dungeonboss.model.Encounter
import com.dungeonboss.model.Hero
import com.dungeonboss.model.PlacedRoom

/**
 * Runs a whole party through a dungeon, applying room effects. At each room, in
 * order: a poison tick, a one-shot delayed hit (Poisoned Spikes), the room's
 * party-wide hits (Antimagic/Zealots, unreducible), then a single-target hit on
 * the highest-health hero (reducible unless the room or a modifier says
 * otherwise). Single-target damage includes the room's grow bonus, dungeon auras
 * (Trap Makers / Beast Tamer), the boss's points bonus, and per-crawl modifiers.
 * A hero hit by a Poison Gas room is poisoned (+1 unreducible per later room). A
 * grow-on-death room gains +1 damage permanently per death there. A Retreat
 * modifier turns the party back before its target room. Stateless entry point;
 * uses an instance to carry crawl state. Mirrors
 * `webapp/lib/party_crawl_resolver.rb`.
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

    private class Run(
        party: Party,
        private val dungeon: Dungeon,
        private val bossBonus: Int,
        private val mods: CrawlModifiers,
        // A dry run predicts the outcome without the one lasting side effect —
        // grow-on-death rooms are not permanently grown — so it is safe to run
        // repeatedly (e.g. to preview a crawl before it is committed).
        private val dryRun: Boolean = false
    ) {
        private val participants: List<Hero> = party.heroes.toList()
        private val health = HashMap<Hero, Int>()
        private val alive = participants.toMutableList()
        private val dead = mutableListOf<Hero>()
        private val poison = HashMap<Hero, Int>()    // hero -> poison stacks
        private val delayed = HashMap<Hero, Int>()   // hero -> unreducible damage next room
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
                if (alive.isNotEmpty()) applyPartyHits(encounter)
                if (alive.isNotEmpty()) applySingleTarget(encounter)
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

        /** Most current health, then most max health, then earliest board order. */
        private fun pickTarget(): Hero =
            alive.maxWith(compareBy({ health.getValue(it) }, { it.maxHp }))

        private fun poisonTick(encounter: Encounter) {
            for (hero in alive.toList()) {
                val stacks = poison[hero] ?: 0
                if (stacks > 0) hit(hero, stacks, encounter, reducible = false)
            }
        }

        private fun delayedTick(encounter: Encounter) {
            for (hero in alive.toList()) {
                val amount = delayed[hero] ?: 0
                if (amount > 0) {
                    hit(hero, amount, encounter, reducible = false)
                    delayed.remove(hero)
                }
            }
        }

        private fun applyPartyHits(encounter: Encounter) {
            RoomEffect.forEncounter(encounter).partyHits(alive.toList()).forEach { (hero, amount) ->
                if (alive.contains(hero)) hit(hero, amount, encounter, reducible = false)
            }
        }

        private fun applySingleTarget(encounter: Encounter) {
            val base = effectiveBase(encounter)
            if (base <= 0) return

            val effect = RoomEffect.forEncounter(encounter)
            // A per-crawl unreducible (Expose Weakness / Troll) OR the room's own
            // unreducible effect (Champion's Arena) makes this hit unreducible.
            val reducible = mods.reducible(roomIndex) && !effect.unreducible()

            if (effect.damagesAll()) {
                // The base hit lands on every alive hero (Poison Gas). Each hit is
                // reduced independently (so a Rogue protects the whole party), and
                // only heroes actually damaged are poisoned. Snapshot the list so
                // a hero killed by this room is not hit twice.
                for (hero in alive.toList()) {
                    applyHitWithEffects(hero, base, encounter, reducible, effect)
                }
                return
            }

            // Single-target hit on the highest-health hero. When the target dies,
            // the leftover (overkill) damage spills onto the next-highest hero, so
            // one big room can wipe a whole party.
            var remaining = base
            while (remaining > 0 && alive.isNotEmpty()) {
                val target = pickTarget()
                val before = health.getValue(target)
                val dealt = applyHitWithEffects(target, remaining, encounter, reducible, effect)
                if (dealt <= 0) break
                remaining = if (dealt > before) dealt - before else 0
            }
        }

        /** Hit one hero and apply this room's on-hit effects; returns damage dealt. */
        private fun applyHitWithEffects(
            hero: Hero,
            amount: Int,
            encounter: Encounter,
            reducible: Boolean,
            effect: RoomEffect.Spec
        ): Int {
            val dealt = hit(hero, amount, encounter, reducible)
            if (dealt <= 0) return 0
            if (alive.contains(hero)) {
                if (effect.poisonsOnHit()) poison[hero] = (poison[hero] ?: 0) + 1
                if (effect.nextRoomDamage() > 0) {
                    delayed[hero] = (delayed[hero] ?: 0) + effect.nextRoomDamage()
                }
            }
            return dealt
        }

        private fun recordDeathDraws(encounter: Encounter) {
            val deck = RoomEffect.forEncounter(encounter).drawsOnDeath()
            if (deck != null && deathsHere > 0) draws[deck] = (draws[deck] ?: 0) + deathsHere
        }

        private fun effectiveBase(encounter: Encounter): Int {
            if (mods.isZero(roomIndex)) return 0

            var base = if (mods.isSet(roomIndex)) mods.setValue(roomIndex) else encounter.damage
            val bossEffect = BossEffect.forBoss(dungeon.boss)
            base += if (encounter === dungeon.boss) {
                bossEffect.selfBonus(bossBonus)
            } else {
                bossEffect.roomBonus(encounter, bossBonus)
            }
            base += auraBonus(encounter)
            return base + mods.bonus(roomIndex)
        }

        /** Damage other rooms' auras grant to this room (never the granter itself). */
        private fun auraBonus(encounter: Encounter): Int =
            dungeon.rooms.sumOf { room ->
                if (room === encounter) 0 else RoomEffect.forEncounter(room).auraBonus(encounter, bossBonus)
            }

        private fun grow(encounter: Encounter) {
            if (dryRun) return // a preview must not permanently grow the room
            if (deathsHere <= 0) return
            if (!RoomEffect.forEncounter(encounter).growsOnDeath()) return
            if (encounter is PlacedRoom) encounter.grow += deathsHere
        }

        /** Deal damage to a hero and record it; returns the actual damage dealt. */
        private fun hit(hero: Hero, amount: Int, encounter: Encounter, reducible: Boolean): Int {
            if (amount <= 0) return 0

            val damage = if (reducible) {
                HeroAbility.damageTaken(hero, encounter, alive, amount)
            } else {
                amount
            }
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
