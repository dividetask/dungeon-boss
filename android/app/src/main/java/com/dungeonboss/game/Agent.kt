package com.dungeonboss.game

/**
 * An automated player. Given a [Decision] it returns a (choiceId, target) pair:
 *   - choiceId: the chosen card id, or null to skip (a comma-joined id list for
 *               DISCARD_ROOMS).
 *   - target:   only meaningful for build decisions — a slot index (0..4) to
 *               place/replace a room, or "upgrade:<slot>" to spend a room card
 *               upgrading a placed room.
 *
 * [RandomAgent] picks at random; [LogicAgent] follows declarative heuristics.
 * The [Game] calls [attach] once when it wires up its agents, so an agent that
 * needs shared state (e.g. the town, for simulations) can capture it. The
 * default is a no-op, so simple agents ignore it.
 */
interface Agent {
    fun choose(decision: Decision): Pair<String?, Any?>

    fun attach(game: Game) {}

    /**
     * The agent's move when it holds priority in the pre-crawl window: the single
     * ability it wants to play now, or null to pass. Crawl actions are not
     * [Decision]s, so the [Game]'s priority loop calls this each time the agent
     * gets priority; returning a play resets the pass streak so others may
     * respond, and the agent is asked again when priority returns to it. The
     * default is to always pass.
     */
    fun preCrawlPlay(context: PreCrawlContext): AbilityPlay? = null
}

/** A single ability play an agent chooses in the pre-crawl window. */
data class AbilityPlay(val cardId: String, val target: Int?)

/**
 * The state an agent needs to weigh its pre-crawl ability plays: who is acting,
 * whose dungeon is being crawled, the party in the window, and the modifiers
 * assembled so far (including plays already made this window). [forecast] runs a
 * side-effect-free crawl under a set of modifiers, so the agent can compare the
 * outcome with and without a card. [bossBonus] is the owner's point total that
 * will apply to this crawl.
 */
class PreCrawlContext(
    val actor: Player,
    val owner: Player,
    val party: Party,
    val dungeon: Dungeon,
    val bossBonus: Int,
    val mods: CrawlModifiers
) {
    val actorIsOwner: Boolean get() = actor === owner

    fun forecast(modifiers: CrawlModifiers): PartyCrawlResolver.Result =
        PartyCrawlResolver.resolve(party, dungeon, bossBonus, modifiers, dryRun = true)
}
