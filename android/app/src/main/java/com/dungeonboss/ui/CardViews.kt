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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dungeonboss.model.AbilityCard
import com.dungeonboss.model.BaitIcons
import com.dungeonboss.model.Boss
import com.dungeonboss.model.BuildCard
import com.dungeonboss.model.Hero
import com.dungeonboss.model.PlacedRoom
import com.dungeonboss.model.Room
import com.dungeonboss.model.Upgrade

private val CARD_WIDTH = 116.dp
private val CARD_HEIGHT = 76.dp
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
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .width(CARD_WIDTH)
            .height(CARD_HEIGHT)
            .clip(CARD_SHAPE)
            .background(bg)
            .border(
                if (highlighted) 3.dp else 1.dp,
                if (highlighted) Palette.Highlight else border,
                CARD_SHAPE
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
        content = content
    )
}

/** Card icon and name on one line (the compact card heading). */
@Composable
private fun CardHeader(glyph: String, name: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(glyph, fontSize = 15.sp)
        Text(name, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 2, modifier = Modifier.weight(1f))
    }
}

/** Bait pips followed by the special-effect / upgrade markers, on one line. */
@Composable
private fun BaitWithMarkers(bait: BaitIcons, hasEffect: Boolean, upgraded: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        BaitPips(bait)
        if (hasEffect) Text("✨", fontSize = 11.sp)
        if (upgraded) Text("⬆️", fontSize = 11.sp)
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

@Composable
fun BaitPips(icons: BaitIcons) {
    val map = icons.toMap()
    if (map.isEmpty()) {
        Box(Modifier) // keep the row height stable when there is no bait
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        map.forEach { (bait, count) ->
            val label = if (count > 1) "${CardArt.baitEmoji[bait]}×$count" else CardArt.baitEmoji[bait].orEmpty()
            Box(
                Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(CardArt.pipColor(bait))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(label, fontSize = 11.sp)
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
    onInfo: (() -> Unit)? = null
) {
    WithInfo(onInfo) {
        CardFrame(Palette.BossBg, Palette.BossBorder, modifier, highlighted) {
            CardHeader(CardArt.bossArt(boss.id), boss.name + (ownerLabel?.let { " ($it)" } ?: ""))
            StatRow(
                { Damage(parts?.sum() ?: boss.damage) },
                { BaitWithMarkers(boss.bait, hasEffect = boss.effect.isNotEmpty(), upgraded = false) }
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
    onInfo: (() -> Unit)? = null
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
                { Damage(parts?.sum() ?: placed.damage) },
                { BaitWithMarkers(placed.bait, hasEffect = placed.effect.isNotEmpty(), upgraded = placed.upgrade != null) }
            )
            DamageBreakdown(parts)
        }
    }
}

/** A hand card: a base [Room] or an [Upgrade]. */
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
                modifier, highlighted
            ) {
                CardHeader(CardArt.roomArt(card.type), card.name)
                StatRow(
                    { Damage(card.damage) },
                    { BaitWithMarkers(card.bait, hasEffect = card.effect.isNotEmpty(), upgraded = false) }
                )
            }
            is Upgrade -> CardFrame(Palette.UpgradeBg, Palette.UpgradeBorder, modifier, highlighted) {
                CardHeader("⬆️", card.name)
                StatRow(
                    {
                        if (card.bonusDamage > 0) {
                            Text("+${card.bonusDamage} ⚔", color = Palette.Damage, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        } else {
                            Text("")
                        }
                    },
                    { BaitPips(card.bait) }
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
        CardType(if (hero.level > 0) "Hero · Lv ${hero.level}" else "Hero")
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
        CardFrame(Palette.AbilityBg, Palette.AbilityBorder, modifier, highlighted) {
            CardHeader("✨", card.name)
            CardType("Ability")
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

/** A walking-party hero chip shown under the dungeon during a crawl. */
@Composable
fun HeroChip(name: String, hp: Int, maxHp: Int, dead: Boolean) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (dead) Palette.DeadChip else Palette.Accent)
            .padding(horizontal = 7.dp, vertical = 4.dp)
    ) {
        Text(
            text = "$name (HP $hp/$maxHp)" + if (dead) " 💀" else "",
            color = Color.White,
            fontSize = 12.sp
        )
    }
}
