package com.dungeonboss.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dungeonboss.model.AbilityCard
import com.dungeonboss.model.Bait
import com.dungeonboss.model.BaitIcons
import com.dungeonboss.model.Boss
import com.dungeonboss.model.BuildCard
import com.dungeonboss.model.Hero
import com.dungeonboss.model.PlacedRoom
import com.dungeonboss.model.Room

private val CARD_WIDTH = 116.dp
// Board cards (boss / dungeon room) must still fit a two-line boss name plus its
// damage + optional breakdown line, so they only shrink modestly.
internal val CARD_HEIGHT = 64.dp
// Hand cards (rooms / abilities) carry a heading plus a damage + bait line. The
// bait pips render as pills (taller than plain text), so the card needs enough
// height to show them fully — a touch shorter than the board card, no more.
private val HAND_CARD_HEIGHT = 58.dp
private val CARD_SHAPE = RoundedCornerShape(10.dp)

/**
 * A card-shaped frame, optionally highlighted (selected / active in a crawl).
 * All cards share a fixed height so a row of them lines up.
 */
@Composable
fun CardFrame(
    bg: Color,
    border: Color,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    height: Dp = CARD_HEIGHT,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .width(CARD_WIDTH)
            .height(height)
            .clip(CARD_SHAPE)
            .background(bg)
            .border(
                if (highlighted) 3.dp else 1.dp,
                if (highlighted) Palette.Highlight else border,
                CARD_SHAPE
            )
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
        content = content
    )
}

/** Card icon and name on one line (the compact card heading). */
@Composable
private fun CardHeader(glyph: String, name: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(glyph, fontSize = 15.sp)
        // One line only — a wrapping name would push the bait/damage row out of
        // the (short) card; overflow is simply clipped.
        Text(name, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f))
    }
}

/** Bait pips followed by the special-effect / upgrade markers, on one line. */
@Composable
private fun BaitWithMarkers(
    bait: BaitIcons,
    hasEffect: Boolean,
    upgraded: Boolean,
    baitHighlight: Set<Bait> = emptySet(),
    baitGlow: Float = 1f
) {
    // Cards accumulate icons (one pip per bait type, plus the ✨ effect and ⬆️
    // upgrade markers). At 4+ they overflow the fixed-width card, so shrink the
    // whole row — font, padding and spacing — once we reach that count. Three
    // icons (e.g. two bait + ✨) still fit at full size.
    val iconCount = bait.toMap().size + (if (hasEffect) 1 else 0) + (if (upgraded) 1 else 0)
    val compact = iconCount >= 4
    val markerFont = if (compact) 8.sp else 11.sp
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 3.dp)
    ) {
        BaitPips(bait, baitHighlight, baitGlow, compact = compact)
        if (hasEffect) Text("✨", fontSize = markerFont)
        if (upgraded) Text("⬆️", fontSize = markerFont)
    }
}

/**
 * Wraps a card so a small ℹ button sits in its top-right corner. Tapping it
 * (without triggering the card's own placement/targeting tap) opens full
 * details. If [onInfo] is null the card is shown unchanged.
 */
@Composable
fun WithInfo(onInfo: (() -> Unit)?, card: @Composable () -> Unit) {
    if (onInfo == null) {
        card()
        return
    }
    Box {
        card()
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(3.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(Color(0xCCFFFFFF))
                .border(1.dp, Palette.CardBorder, CircleShape)
                .clickable { onInfo() },
            contentAlignment = Alignment.Center
        ) {
            Text("ℹ", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.Accent)
        }
    }
}

@Composable
private fun CardArtGlyph(glyph: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.CardArtBg)
            .padding(vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(glyph, fontSize = 22.sp)
    }
}

@Composable
private fun CardTitle(name: String) {
    Text(name, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 2)
}

@Composable
private fun CardType(type: String) {
    Text(type.uppercase(), color = Palette.TypeText, fontSize = 9.sp, letterSpacing = 0.4.sp)
}

@Composable
private fun CardDesc(text: String) {
    if (text.isNotEmpty()) {
        Text(text, fontSize = 9.sp, color = Palette.SubText, fontStyle = FontStyle.Italic, maxLines = 2)
    }
}

/**
 * Bait pips. Any bait in [highlight] gets a glowing ring whose opacity tracks
 * [glow] (0..1) — used by the tutorial to draw attention to specific bait. The
 * live game leaves both at their defaults, so pips render unchanged. [compact]
 * shrinks each pip (font, padding, spacing) so a crowded 4+-icon card still fits.
 */
@Composable
fun BaitPips(
    icons: BaitIcons,
    highlight: Set<Bait> = emptySet(),
    glow: Float = 1f,
    compact: Boolean = false
) {
    val map = icons.toMap()
    if (map.isEmpty()) {
        Box(Modifier) // keep the row height stable when there is no bait
        return
    }
    val font = if (compact) 8.sp else 11.sp
    val corner = if (compact) 6.dp else 9.dp
    val hPad = if (compact) 3.dp else 5.dp
    val vPad = if (compact) 0.dp else 1.dp
    Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 3.dp)) {
        map.forEach { (bait, count) ->
            val label = if (count > 1) "${CardArt.baitEmoji[bait]}×$count" else CardArt.baitEmoji[bait].orEmpty()
            val lit = bait in highlight
            Box(
                Modifier
                    .clip(RoundedCornerShape(corner))
                    .background(CardArt.pipColor(bait))
                    .then(
                        if (lit) Modifier.border(2.dp, Palette.Highlight.copy(alpha = glow), RoundedCornerShape(corner))
                        else Modifier
                    )
                    .padding(horizontal = hPad, vertical = vPad)
            ) {
                Text(label, fontSize = font)
            }
        }
    }
}

@Composable
private fun StatRow(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        left()
        right()
    }
}

@Composable
private fun Damage(value: Int) {
    Text("⚔ $value", color = Palette.Damage, fontWeight = FontWeight.Bold, fontSize = 13.sp)
}

/** "5 + 2 + 2" — shown when effective damage is more than the printed base. */
@Composable
private fun DamageBreakdown(parts: List<Int>?) {
    val shown = parts?.filter { it > 0 } ?: return
    if (shown.size <= 1) return
    Text(shown.joinToString(" + "), color = Color(0xFFC06666), fontSize = 9.sp, fontWeight = FontWeight.Bold)
}

/**
 * [parts] is the effective-damage breakdown for an encounter as it sits in a
 * dungeon (base + bonuses); when given, the card shows the effective total and a
 * breakdown line. Hand cards pass null and show their printed damage.
 */
@Composable
fun BossCardView(
    boss: Boss,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    parts: List<Int>? = null,
    ownerLabel: String? = null,
    onInfo: (() -> Unit)? = null,
    baitHighlight: Set<Bait> = emptySet(),
    baitGlow: Float = 1f
) {
    WithInfo(onInfo) {
        CardFrame(Palette.BossBg, Palette.BossBorder, modifier, highlighted) {
            CardHeader(CardArt.bossArt(boss.id), boss.name + (ownerLabel?.let { " ($it)" } ?: ""))
            StatRow(
                { Damage(parts?.sum() ?: boss.displayDamage) },
                { BaitWithMarkers(boss.bait, hasEffect = boss.hasSpecial, upgraded = false, baitHighlight = baitHighlight, baitGlow = baitGlow) }
            )
            DamageBreakdown(parts)
        }
    }
}

@Composable
fun RoomCardView(
    placed: PlacedRoom,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    parts: List<Int>? = null,
    onInfo: (() -> Unit)? = null,
    baitHighlight: Set<Bait> = emptySet(),
    baitGlow: Float = 1f
) {
    val advanced = placed.baseRoom.advanced
    WithInfo(onInfo) {
        CardFrame(
            if (advanced) Palette.AdvancedBg else Palette.CardBg,
            if (advanced) Palette.AdvancedBorder else Palette.CardBorder,
            modifier, highlighted
        ) {
            CardHeader(CardArt.roomArt(placed.type), placed.name)
            StatRow(
                { Damage(parts?.sum() ?: placed.displayDamage) },
                { BaitWithMarkers(placed.bait, hasEffect = placed.hasSpecial, upgraded = placed.level > 0, baitHighlight = baitHighlight, baitGlow = baitGlow) }
            )
            DamageBreakdown(parts)
        }
    }
}

/** A hand card: a base or advanced [Room]. */
@Composable
fun HandCardView(
    card: BuildCard,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    onInfo: (() -> Unit)? = null
) {
    WithInfo(onInfo) {
        when (card) {
            is Room -> CardFrame(
                if (card.advanced) Palette.AdvancedBg else Palette.CardBg,
                if (card.advanced) Palette.AdvancedBorder else Palette.CardBorder,
                modifier, highlighted, height = HAND_CARD_HEIGHT
            ) {
                CardHeader(CardArt.roomArt(card.type), card.name)
                StatRow(
                    { Damage(card.displayDamage) },
                    { BaitWithMarkers(card.bait, hasEffect = card.hasSpecial, upgraded = false) }
                )
            }
        }
    }
}

@Composable
fun HeroCardView(hero: Hero, modifier: Modifier = Modifier) {
    CardFrame(Palette.HeroBg, Palette.HeroBorder, modifier) {
        CardArtGlyph(hero.icon.ifEmpty { CardArt.heroArt(hero.id) })
        CardTitle(hero.name)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CardType("Hero")
            LevelBadge(hero.level)
        }
        StatRow(
            { Text("❤ ${hero.maxHp}", color = Palette.Health, fontWeight = FontWeight.Bold, fontSize = 13.sp) },
            {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("🦁 ${hero.courage}", fontSize = 12.sp)
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(CardArt.pipColor(hero.preferredBait))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(CardArt.baitEmoji[hero.preferredBait].orEmpty(), fontSize = 11.sp)
                    }
                }
            }
        )
        CardDesc(hero.abilityText)
    }
}

/** An ability card (held in hand). Tappable when playable; selected = highlighted. */
@Composable
fun AbilityCardView(
    card: AbilityCard,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    onInfo: (() -> Unit)? = null
) {
    WithInfo(onInfo) {
        CardFrame(Palette.AbilityBg, Palette.AbilityBorder, modifier, highlighted, height = HAND_CARD_HEIGHT) {
            CardHeader("✨", card.name)
        }
    }
}

/** A dashed "add a new room here" placeholder shown while building. */
@Composable
fun NewRoomSlot(modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(CARD_WIDTH)
            .height(CARD_HEIGHT)
            .clip(CARD_SHAPE)
            .background(Palette.NewSlotBg)
            .border(2.dp, Palette.NewSlotBorder, CARD_SHAPE)
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("+ new room", color = Palette.PartyHead, fontSize = 11.sp)
    }
}

/**
 * One of the dungeon's 5 slots when it is empty. [active] (a room is selected to
 * place) draws it as a tappable "+ place here" target; otherwise a faint gap.
 */
@Composable
fun EmptyRoomSlot(slot: Int, active: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(if (active) CARD_WIDTH else CARD_WIDTH / 2)
            .height(CARD_HEIGHT)
            .clip(CARD_SHAPE)
            .background(if (active) Palette.NewSlotBg else Color.Transparent)
            .border(2.dp, if (active) Palette.NewSlotBorder else Palette.HeroBorder, CARD_SHAPE)
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (active) "+ slot ${slot + 1}" else "·",
            color = Palette.PartyHead,
            fontSize = if (active) 11.sp else 14.sp
        )
    }
}

/**
 * A walking-party hero chip shown under the dungeon during a crawl. A hero that
 * [dead] is red with 💀; one that [fled] (survived only because the party
 * retreated) is amber with ↩; one that survived the full crawl is the normal
 * accent colour — so fleeing and surviving read differently.
 */
@Composable
fun HeroChip(name: String, hp: Int, maxHp: Int, dead: Boolean, fled: Boolean = false) {
    val bg = when {
        dead -> Palette.DeadChip
        fled -> Palette.FledText // darker amber for white-text contrast
        else -> Palette.Accent
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 4.dp)
    ) {
        Text(
            text = "$name (HP $hp/$maxHp)" + when {
                dead -> " 💀"
                fled -> " ↩ fled"
                else -> ""
            },
            color = Color.White,
            fontSize = 12.sp
        )
    }
}

/**
 * A small "L{n}" level label — plain blue text (no box), gold once a hero is a
 * battle-hardened veteran (L4+) so higher levels still stand out. Heroes are
 * level 1 at minimum.
 */
@Composable
fun LevelBadge(level: Int) {
    val color = if (level >= 4) Color(0xFFC79A2E) else Palette.Accent
    Text("L$level", color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
}
