package com.dungeonboss.game

import kotlin.random.Random

/**
 * Generates a whimsical name for a freshly-formed party, e.g. "The Doomed
 * Fellowship". Stateless; takes an rng so names are reproducible in tests.
 */
object PartyNamer {
    private val ADJECTIVES = listOf(
        "Brave", "Bold", "Doomed", "Hapless", "Gallant", "Reckless", "Cursed",
        "Merry", "Grim", "Lucky", "Restless", "Ragtag", "Fearless", "Ill-Fated"
    )
    private val NOUNS = listOf(
        "Fellowship", "Company", "Band", "Crew", "Posse", "Brigade", "Coterie",
        "Vanguard", "Wanderers", "Misfits", "Few", "Delvers"
    )

    fun generate(rng: Random = Random.Default): String =
        "The ${ADJECTIVES.random(rng)} ${NOUNS.random(rng)}"
}
