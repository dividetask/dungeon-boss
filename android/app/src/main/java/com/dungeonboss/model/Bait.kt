package com.dungeonboss.model

/**
 * The set of valid bait types and validation of a bait name.
 * Bait types are exactly: glory, riches, undead, arcane.
 */
enum class Bait {
    GLORY, RICHES, UNDEAD, ARCANE;

    companion object {
        /**
         * Normalize an arbitrary value (string, symbol, or a [Bait]) to a known
         * bait, raising if it is not one of the four valid types.
         */
        fun normalize(value: Any?): Bait {
            val name = value.toString().lowercase()
            return entries.firstOrNull { it.name.lowercase() == name }
                ?: throw IllegalArgumentException("unknown bait type: $value")
        }

        fun isValid(value: Any?): Boolean =
            entries.any { it.name.lowercase() == value.toString().lowercase() }
    }
}
