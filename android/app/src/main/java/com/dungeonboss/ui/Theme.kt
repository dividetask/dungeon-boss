package com.dungeonboss.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.dungeonboss.model.Bait

/**
 * Colours lifted from the web app's stylesheet so the two clients read the same.
 * (See webapp/views/index.erb.)
 */
object Palette {
    val Page = Color(0xFFDDDDDD)
    val AppBg = Color(0xFFF6F5F2)
    val CardBg = Color(0xFFF7F1E4)
    val CardBorder = Color(0xFFB3A98F)
    // Advanced rooms read with a pink tint, matching the web app's .card.advanced.
    val AdvancedBg = Color(0xFFF3D9E6)
    val AdvancedBorder = Color(0xFFB5739B)
    val CardArtBg = Color(0x14000000)
    val BossBg = Color(0xFFF7CFDF)
    val BossBorder = Color(0xFFCC6699)
    val HeroBg = Color(0xFFD9E4FF)
    val HeroBorder = Color(0xFF8FA8D8)
    val UpgradeBg = Color(0xFFCDEED8)
    val UpgradeBorder = Color(0xFF66AA99)
    val AbilityBg = Color(0xFFF7EFD6)
    val AbilityBorder = Color(0xFFC9A24B)
    val Damage = Color(0xFFAA3333)
    val Health = Color(0xFF117788)
    val Accent = Color(0xFF2C3E9E)
    val Highlight = Color(0xFF3366CC)
    val HighlightFill = Color(0xFFF4F7FF)
    val PipGlory = Color(0xFFF6E6A8)
    val PipRiches = Color(0xFFBFE6C6)
    val PipUndead = Color(0xFFD8CFE8)
    val PipPower = Color(0xFFCDD9F7)
    val PartyBg = Color(0xFFF3F7EC)
    val PartyBorder = Color(0xFF88AA66)
    val PartyHead = Color(0xFF558855)
    val SubText = Color(0xFF666666)
    val TypeText = Color(0xFF66AA55)
    val NewSlotBg = Color(0xFFFBFBF4)
    val NewSlotBorder = Color(0xFF99AA88)
    val GameOverBg = Color(0xFFFFF7E0)
    val GameOverBorder = Color(0xFFB8860B)
    val DeadChip = Color(0xFFBB0000)
    val DyingBg = Color(0xFFFBE3E6) // light red for a hero predicted to die
}

/** Emoji art and bait formatting — the Android analogue of CardPresenter. */
object CardArt {
    val baitEmoji = mapOf(
        Bait.GLORY to "🏆", Bait.RICHES to "💰", Bait.UNDEAD to "💀", Bait.POWER to "🔮"
    )

    private val heroArt = mapOf(
        "hero_cleric" to "⛪", "hero_barbarian" to "🪓",
        "hero_rogue" to "🗡️", "hero_mage" to "🧙"
    )

    private val bossArt = mapOf(
        "boss_lich" to "☠️", "boss_oni" to "👹",
        "boss_vampire" to "🧛", "boss_medusa" to "🐍",
        "boss_goblin_chieftain" to "👺", "boss_malevolent_spirit" to "👻",
        "boss_kobold_chieftain" to "🐲", "boss_necromancer" to "🧟"
    )

    fun heroArt(id: String): String = heroArt[id] ?: "🧝"
    fun bossArt(id: String): String = bossArt[id] ?: "👑"

    fun roomArt(type: String): String = when {
        type.lowercase().contains("trap") -> "🪤"
        type.lowercase().contains("monster") -> "👹"
        type.lowercase().contains("creature") -> "🐾"
        else -> "🏰"
    }

    fun pipColor(bait: Bait): Color = when (bait) {
        Bait.GLORY -> Palette.PipGlory
        Bait.RICHES -> Palette.PipRiches
        Bait.UNDEAD -> Palette.PipUndead
        Bait.POWER -> Palette.PipPower
    }

    /** Format a dungeon's bait totals as count + icon, e.g. "2 🏆, 5 💰". */
    fun baitSummary(totals: Map<Bait, Int>): String {
        val nonZero = totals.filterValues { it > 0 }
        if (nonZero.isEmpty()) return "—"
        return nonZero.entries.joinToString(", ") { (b, c) -> "$c ${baitEmoji[b]}" }
    }
}

@Composable
fun DungeonBossTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Palette.Accent,
            background = Palette.Page,
            surface = Palette.AppBg
        ),
        content = content
    )
}
