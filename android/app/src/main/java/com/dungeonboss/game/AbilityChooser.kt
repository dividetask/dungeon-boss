package com.dungeonboss.game

import com.dungeonboss.model.AbilityCard

/**
 * Chooses which ability cards an automated player spends in the pre-crawl window,
 * driven by an [AbilityPolicy]. Each card the actor holds is judged on its own by
 * forecasting the pending crawl with and without it (a dry run, so nothing is
 * mutated) and comparing the outcome. A card is played only when a listed
 * objective clears its threshold — and never in a direction that would help the
 * dungeon the actor is crawling against, because the owner's buff objectives only
 * apply to the owner and the disruption objectives only to opponents.
 *
 * Pure: it reads the context and returns the plays in order; the [Game] applies
 * them via [Game.playAbility]. See docs/ai.md.
 */
object AbilityChooser {
    /** A forecast crawl outcome reduced to what the objectives care about. */
    private data class Fate(val deaths: Int, val ownerWounded: Boolean)

    fun choose(context: PreCrawlContext, policy: AbilityPolicy): List<AbilityPlay> {
        val working = context.mods.copy()          // grows as plays this window accumulate
        var projectedHand = context.actor.roomHand.size
        val plays = mutableListOf<AbilityPlay>()

        for (card in context.actor.abilityHand.toList()) {
            val cardPolicy = policy.forCard(card.id) ?: continue
            val play = evaluate(card, cardPolicy, policy, context, working, projectedHand) ?: continue
            plays.add(play)
            // Reflect the play so later cards are judged against the new board.
            applyEffect(working, card, play.target)
            AbilityEffect.forCard(card).drawRooms?.let {
                projectedHand = minOf(Player.MAX_ROOM_HAND, projectedHand + it)
            }
        }
        return plays
    }

    /** The single best play for one card, or null if no objective justifies it. */
    private fun evaluate(
        card: AbilityCard,
        cardPolicy: AbilityPolicy.CardPolicy,
        policy: AbilityPolicy,
        context: PreCrawlContext,
        mods: CrawlModifiers,
        projectedHand: Int
    ): AbilityPlay? {
        val spec = AbilityEffect.forCard(card)
        val baseline = fate(context, mods)

        // Owner buff — spend it to stop a hero surviving my own dungeon (a survivor
        // is a wound). Prefer the target that also kills the most heroes.
        if (context.actorIsOwner && cardPolicy.preventSelfWound && spec.targetsRoom() && baseline.ownerWounded) {
            val best = targets(context)
                .map { it to fate(context, withEffect(mods, card, it)) }
                .filter { !it.second.ownerWounded }
                .maxByOrNull { it.second.deaths }
            if (best != null) return AbilityPlay(card.id, best.first)
        }

        // Opponent disruption — hand a *winning* owner a wound by making a hero
        // survive their dungeon (only worthwhile if no hero survives already).
        if (!context.actorIsOwner && cardPolicy.woundWinningOpponent && spec.targetsRoom() &&
            !baseline.ownerWounded && context.owner.points >= policy.winningOpponentPoints
        ) {
            val target = targets(context).firstOrNull { fate(context, withEffect(mods, card, it)).ownerWounded }
            if (target != null) return AbilityPlay(card.id, target)
        }

        // Opponent disruption — deny the owner points (fewer hero deaths). Prefer
        // the target that denies the most, but only if it clears the threshold.
        val denyThreshold = cardPolicy.denyOpponentPoints
        if (!context.actorIsOwner && denyThreshold != null && spec.targetsRoom()) {
            val best = targets(context)
                .map { it to (baseline.deaths - fate(context, withEffect(mods, card, it)).deaths) }
                .filter { it.second >= denyThreshold }
                .maxByOrNull { it.second }
            if (best != null) return AbilityPlay(card.id, best.first)
        }

        // Utility — a no-target draw card (Blueprints) when the room hand is thin.
        val refillBelow = cardPolicy.refillRoomHandBelow
        if (refillBelow != null && spec.drawRooms != null && projectedHand < refillBelow) {
            return AbilityPlay(card.id, null)
        }

        return null
    }

    /** Every encounter index a room-targeting card may aim at (rooms, then boss). */
    private fun targets(context: PreCrawlContext): IntRange = 0 until context.dungeon.encounters().size

    private fun withEffect(base: CrawlModifiers, card: AbilityCard, target: Int?): CrawlModifiers =
        base.copy().also { applyEffect(it, card, target) }

    /** Fold a card's effect into a set of modifiers (mirrors Game.playAbility). */
    private fun applyEffect(mods: CrawlModifiers, card: AbilityCard, target: Int?) {
        if (target == null) return
        val spec = AbilityEffect.forCard(card)
        spec.addDamage?.let { mods.addDamage(target, it) }
        if (spec.unreducible) mods.unreducibleMark(target)
        if (spec.zero) mods.zero(target)
        if (spec.retreat) mods.retreat(target)
    }

    /** Forecast the crawl under [mods]; the owner is wounded iff a hero survives a full crawl. */
    private fun fate(context: PreCrawlContext, mods: CrawlModifiers): Fate {
        val result = context.forecast(mods)
        val wounded = !mods.retreating() && result.survivors.isNotEmpty()
        return Fate(result.deaths, wounded)
    }
}
