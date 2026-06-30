package com.dungeonboss.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dungeonboss.data.CardLibrary
import com.dungeonboss.model.AbilityCard
import com.dungeonboss.model.Bait
import com.dungeonboss.model.Boss
import com.dungeonboss.model.BuildCard
import com.dungeonboss.model.Hero
import com.dungeonboss.model.PlacedRoom
import kotlinx.coroutines.delay

/**
 * The interactive-free tutorial: a sequence of narrated, static screens that
 * teach the rules. Each step shows a frozen board (built from real card data so
 * it looks exactly like a game) plus a narration panel with a Continue button.
 * The board cannot be touched — only Back / Continue / Exit move the flow. When
 * the last step is finished the player is returned to the Load screen via
 * [onExit]. See docs/screens.md ("Tutorial").
 */

/** What, if anything, animates on a step's board to draw the eye. */
private enum class Highlight { NONE, FLASH_ALL_BAIT, CYCLE_HERO_BAIT, CYCLE_HERO_TOTALS }

/** A waiting hero or party shown in town. A lone hero is a list of one. */
private class TownEntry(val heroes: List<Hero>, val timid: Boolean = false)

/** A dungeon snapshot: rooms (entrance on the left) behind a boss. */
private class DungeonView(
    val ownerLabel: String,
    val rooms: List<PlacedRoom>,
    val boss: Boss,
    val points: Int = 0
)

/** A single hero chip shown crawling under a dungeon. */
private class CrawlChip(val name: String, val hp: Int, val maxHp: Int, val dead: Boolean)

/** One row in the top-right bait-totals panel. */
private class BaitTotalsRow(val label: String, val totals: Map<Bait, Int>)

/** Everything a step might display; empty regions are simply omitted. */
private class TutorialBoard(
    val caption: String? = null,
    val baitTotals: List<BaitTotalsRow> = emptyList(),
    val town: List<TownEntry> = emptyList(),
    val bossChoice: List<Boss> = emptyList(),
    val hand: List<BuildCard> = emptyList(),
    val abilities: List<AbilityCard> = emptyList(),
    val dungeon: DungeonView? = null,
    val crawl: List<CrawlChip> = emptyList()
)

private class TutorialStep(
    val narration: String,
    val board: TutorialBoard,
    val highlight: Highlight = Highlight.NONE
)

@Composable
fun TutorialScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val steps = remember {
        val lib = context.assets.open(GameViewModel.CARDS_ASSET).use { CardLibrary.load(it) }
        buildTutorial(lib)
    }

    var index by remember { mutableStateOf(0) }
    val step = steps[index]

    // A pulsing opacity (0..1) that drives every bait glow on the board.
    var glow by remember { mutableStateOf(1f) }
    LaunchedEffect(Unit) {
        val frames = listOf(0.2f, 0.4f, 0.65f, 0.9f, 1f, 0.9f, 0.65f, 0.4f)
        var i = 0
        while (true) {
            glow = frames[i % frames.size]
            i += 1
            delay(150)
        }
    }

    // For the cycling steps, which town hero is currently spotlighted.
    val cycling = step.highlight == Highlight.CYCLE_HERO_BAIT || step.highlight == Highlight.CYCLE_HERO_TOTALS
    val cycleCount = step.board.town.size.coerceAtLeast(1)
    var cycle by remember(index) { mutableStateOf(0) }
    LaunchedEffect(index, cycleCount) {
        if (cycling) {
            while (true) {
                delay(1600)
                cycle = (cycle + 1) % cycleCount
            }
        }
    }

    val activeHero = if (cycling) step.board.town.getOrNull(cycle)?.heroes?.firstOrNull() else null
    val baitHighlight: Set<Bait> = when (step.highlight) {
        Highlight.FLASH_ALL_BAIT -> Bait.entries.toSet()
        Highlight.CYCLE_HERO_BAIT, Highlight.CYCLE_HERO_TOTALS ->
            activeHero?.let { setOf(it.preferredBait) } ?: emptySet()
        Highlight.NONE -> emptySet()
    }
    val totalsBait = if (step.highlight == Highlight.CYCLE_HERO_TOTALS) activeHero?.preferredBait else null

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Page)
            // Swallow taps on empty areas so nothing reaches the game underneath
            // when the tutorial is opened from the in-game ☰ menu.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .widthIn(max = 920.dp)
                .background(Palette.AppBg)
        ) {
            // Header: title + Exit.
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Dungeon Boss", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("· Tutorial", fontSize = 12.sp, color = Palette.SubText)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, Palette.CardBorder, RoundedCornerShape(6.dp))
                        .clickable { onExit() }
                        .padding(horizontal = 9.dp, vertical = 3.dp)
                ) {
                    Text("✕ Exit", fontSize = 13.sp, color = Palette.SubText)
                }
            }

            // Board (scrolls if tall).
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                BoardView(step.board, baitHighlight, glow, activeHero, totalsBait)
            }

            // Narration + controls, pinned at the bottom like the advance bar.
            NarrationPanel(
                text = step.narration,
                index = index,
                total = steps.size,
                onBack = { if (index > 0) index -= 1 },
                onNext = { if (index < steps.lastIndex) index += 1 else onExit() }
            )
        }
    }
}

@Composable
private fun BoardView(
    board: TutorialBoard,
    baitHighlight: Set<Bait>,
    glow: Float,
    highlightHero: Hero?,
    totalsBait: Bait?
) {
    board.caption?.let {
        Text(it, fontSize = 12.sp, color = Palette.SubText, fontWeight = FontWeight.Bold)
    }

    if (board.baitTotals.isNotEmpty()) {
        BaitTotalsPanel(board.baitTotals, totalsBait, glow)
    }

    if (board.town.isNotEmpty()) {
        Text("Town", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Palette.PartyHead)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            board.town.forEach { entry -> TownEntryView(entry, highlightHero) }
        }
    }

    if (board.bossChoice.isNotEmpty()) {
        Text("Choose your boss", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Palette.PartyHead)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            board.bossChoice.forEach { boss -> BossCardView(boss) }
        }
    }

    if (board.hand.isNotEmpty()) {
        Text("Your hand", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Palette.PartyHead)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            board.hand.forEach { card -> HandCardView(card) }
        }
    }

    if (board.abilities.isNotEmpty()) {
        Text("Ability cards", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Palette.PartyHead)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            board.abilities.forEach { card -> AbilityCardView(card) }
        }
    }

    board.dungeon?.let { d -> DungeonBoardView(d, baitHighlight, glow, board.crawl) }
}

@Composable
private fun DungeonBoardView(
    d: DungeonView,
    baitHighlight: Set<Bait>,
    glow: Float,
    crawl: List<CrawlChip>
) {
    val header = buildString {
        append(d.ownerLabel)
        append(" — dungeon (entrance on the left)")
        if (d.points > 0) append("   🪙 ${d.points}")
    }
    Text(header, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Palette.PartyHead)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        d.rooms.forEach { room ->
            RoomCardView(room, baitHighlight = baitHighlight, baitGlow = glow)
        }
        BossCardView(d.boss, baitHighlight = baitHighlight, baitGlow = glow)
    }
    if (crawl.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            crawl.forEach { c -> HeroChip(c.name, c.hp, c.maxHp, c.dead) }
        }
    }
}

@Composable
private fun TownEntryView(entry: TownEntry, highlightHero: Hero?) {
    if (entry.heroes.size == 1) {
        val hero = entry.heroes.first()
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            HeroCardView(hero, highlighted = highlightHero === hero)
            if (entry.timid) TimidBadge()
        }
    } else {
        // A formed party: members boxed together with their combined stats.
        val courage = entry.heroes.sumOf { it.courage }
        val baitTotals = LinkedHashMap<Bait, Int>()
        entry.heroes.forEach { baitTotals[it.preferredBait] = (baitTotals[it.preferredBait] ?: 0) + 1 }
        Column(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Palette.PartyBg)
                .border(1.dp, Palette.PartyBorder, RoundedCornerShape(10.dp))
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "Party · 🦁 $courage · ${CardArt.baitSummary(baitTotals)}",
                fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Palette.PartyHead
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                entry.heroes.forEach { hero -> HeroCardView(hero, highlighted = highlightHero === hero) }
            }
        }
    }
}

@Composable
private fun TimidBadge() {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.DyingBg)
            .border(1.dp, Palette.Damage, RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text("😨 too timid", fontSize = 10.sp, color = Palette.Damage, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BaitTotalsPanel(rows: List<BaitTotalsRow>, highlightBait: Bait?, glow: Float) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
        Column(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, Palette.CardBorder, RoundedCornerShape(8.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Bait totals", fontSize = 11.sp, color = Palette.SubText, fontWeight = FontWeight.Bold)
            rows.forEach { row ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(row.label, fontSize = 11.sp, color = Palette.SubText, modifier = Modifier.width(64.dp))
                    Bait.entries.forEach { bait ->
                        val lit = bait == highlightBait
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(CardArt.pipColor(bait))
                                .then(
                                    if (lit) Modifier.border(2.dp, Palette.Highlight.copy(alpha = glow), RoundedCornerShape(8.dp))
                                    else Modifier
                                )
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        ) {
                            Text("${CardArt.baitEmoji[bait]} ${row.totals[bait] ?: 0}", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NarrationPanel(
    text: String,
    index: Int,
    total: Int,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.HighlightFill)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(text, fontSize = 14.sp, color = Color(0xFF222222))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Step ${index + 1} / $total", fontSize = 11.sp, color = Palette.SubText)
            Spacer(Modifier.weight(1f))
            if (index > 0) {
                OutlinedButton(onClick = onBack) { Text("Back", fontSize = 13.sp) }
            }
            Button(
                onClick = onNext,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent)
            ) {
                Text(if (index < total - 1) "Continue" else "Finish", color = Color.White)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Content: the 13 narrated steps. Boards are built from real cards so they look
// identical to the live game. Text is the script agreed in docs/screens.md.
// ---------------------------------------------------------------------------

private fun buildTutorial(lib: CardLibrary): List<TutorialStep> {
    fun room(id: String) = PlacedRoom(lib.rooms.first { it.id == id })
    fun boss(id: String) = lib.bosses.first { it.id == id }
    fun hero(id: String) = lib.heroes.first { it.id == id }
    fun ability(id: String) = lib.abilityCards.first { it.id == id }

    val cleric = hero("hero_cleric")
    val barbarian = hero("hero_barbarian")
    val rogue = hero("hero_rogue")
    val mage = hero("hero_mage")

    // A varied full dungeon (one room of each bait, plus a second glory room) used
    // across the bait-teaching steps. Totals below match these rooms + the Lich.
    fun showcaseDungeon(owner: String = "Player 1", points: Int = 0) = DungeonView(
        ownerLabel = owner,
        rooms = listOf(
            room("room_stone_ball"),   // glory · 5
            room("room_golden_idol"),  // riches · 3
            room("room_ghoul_pit"),    // undead · 5
            room("room_will_o_wisps"), // power · 5
            room("room_gladiator")     // glory · 4
        ),
        boss = boss("boss_lich"),      // undead + power
        points = points
    )
    val showcaseTotals = listOf(
        BaitTotalsRow("Player 1", mapOf(Bait.GLORY to 2, Bait.RICHES to 1, Bait.UNDEAD to 2, Bait.POWER to 2)),
        BaitTotalsRow("Player 2", mapOf(Bait.GLORY to 1, Bait.RICHES to 2, Bait.UNDEAD to 1, Bait.POWER to 0))
    )
    // One of each hero, in town, as lone heroes (board/town order left to right).
    val oneOfEach = listOf(
        TownEntry(listOf(barbarian)),
        TownEntry(listOf(rogue)),
        TownEntry(listOf(cleric)),
        TownEntry(listOf(mage))
    )

    return listOf(
        // 1 — intro
        TutorialStep(
            "In Dungeon Boss you play the villain. You have a dungeon of 5 rooms plus the boss chamber where heroes come in and try to defeat you.",
            TutorialBoard(dungeon = showcaseDungeon())
        ),
        // 2 — choose boss + place rooms
        TutorialStep(
            "The game is played by first choosing a boss from two cards, then placing rooms in one of 5 slots.",
            TutorialBoard(
                bossChoice = listOf(boss("boss_medusa"), boss("boss_oni")),
                hand = listOf(
                    lib.rooms.first { it.id == "room_stone_ball" },
                    lib.rooms.first { it.id == "room_goblins" },
                    lib.rooms.first { it.id == "room_ghoul_pit" },
                    lib.rooms.first { it.id == "room_golden_idol" }
                )
            )
        ),
        // 3 — rooms have bait icons (flash all)
        TutorialStep(
            "Each room, including the boss room, has icons at the bottom right indicating which heroes will be attracted to them.",
            TutorialBoard(town = oneOfEach, dungeon = showcaseDungeon()),
            Highlight.FLASH_ALL_BAIT
        ),
        // 4 — heroes prefer a bait (cycle hero + matching room bait)
        TutorialStep(
            "Each hero prefers a specific type of bait. Barbarians seek glory, Rogues seek riches, Clerics seek to destroy the undead, and Mages seek Arcane Power.",
            TutorialBoard(town = oneOfEach, dungeon = showcaseDungeon()),
            Highlight.CYCLE_HERO_BAIT
        ),
        // 5 — heroes go to the dungeon with the most preferred bait; totals top-right
        TutorialStep(
            "Each hero will take turns, starting with the left most hero, crawling through a dungeon. The hero will only go to a dungeon if that dungeon has more of the hero's preferred bait than the other dungeons. If there is a tie then the hero will stay in town. At the top right of the screen we can see each player's bait totals.",
            TutorialBoard(baitTotals = showcaseTotals, town = oneOfEach, dungeon = showcaseDungeon()),
            Highlight.CYCLE_HERO_TOTALS
        ),
        // 6 — crawl room by room; hero dies
        TutorialStep(
            "When a hero crawls a dungeon they will go through each room from left to right. Each room will deal the listed damage to the hero until that hero dies or each room is completed.",
            TutorialBoard(
                caption = "The Mage (4 HP) is about to crawl a deadly dungeon.",
                dungeon = DungeonView(
                    "Player 1",
                    rooms = listOf(
                        room("room_stone_ball"),   // 5 — kills the 4-HP Mage immediately
                        room("room_ghoul_pit"),    // 5
                        room("room_will_o_wisps"), // 5
                        room("room_gladiator"),    // 4
                        room("room_goblins")       // 3
                    ),
                    boss = boss("boss_medusa")
                ),
                crawl = listOf(CrawlChip("Mage", 4, 4, dead = false))
            )
        ),
        // 7 — score a point per death; courage
        TutorialStep(
            "Each player scores a point for each hero that dies in their dungeon. When heroes die in a dungeon other heroes become more cautious and may avoid that dungeon depending upon their courage score. Barbarians and Clerics have a courage of 2, which means they will avoid dungeons that have 3 or more points. Rogues and Mages have a courage of 1, which means they will avoid dungeons that have 2 or more points.",
            TutorialBoard(
                caption = "Player 1's dungeon has 3 points, so timid heroes hang back.",
                town = listOf(
                    TownEntry(listOf(barbarian), timid = true),
                    TownEntry(listOf(rogue), timid = true),
                    TownEntry(listOf(cleric), timid = true),
                    TownEntry(listOf(mage), timid = true)
                ),
                dungeon = showcaseDungeon(points = 3)
            )
        ),
        // 8 — skipped heroes stay and form parties
        TutorialStep(
            "When heroes skip a dungeon, due to low courage or when a dungeon has tied bait, they stay in town and will form a party at the end of the round. Each hero, or party, that stayed in town will try to add one new member to their party. Each will add the left most unpartied hero preferring heroes of classes they don't have.",
            TutorialBoard(
                caption = "These heroes all stayed behind — at end of round they band together.",
                town = listOf(
                    TownEntry(listOf(barbarian), timid = true),
                    TownEntry(listOf(rogue), timid = true),
                    TownEntry(listOf(cleric), timid = true),
                    TownEntry(listOf(mage), timid = true)
                )
            )
        ),
        // 9 — parties: courage = sum, bait = all members'
        TutorialStep(
            "Parties have a courage score equal to the sum of each party member. Parties also consider all members' bait preferences. If a party has multiple heroes of the same class then that class's bait is added once for each hero.",
            TutorialBoard(
                caption = "The same heroes, now grouped into parties — bolder together.",
                town = listOf(
                    TownEntry(listOf(barbarian, rogue)),
                    TownEntry(listOf(cleric, mage))
                )
            )
        ),
        // 10 — party crawl; highest-HP hero takes the hit; some die
        TutorialStep(
            "When a party explores a dungeon the hero with the highest HP typically takes the damage for the next room, but some rooms have special abilities that target all heroes or target specific heroes.",
            TutorialBoard(
                caption = "A party crawls Player 1's dungeon — some fall, some survive.",
                dungeon = showcaseDungeon(),
                crawl = listOf(
                    CrawlChip("Barbarian", 3, 8, dead = false),
                    CrawlChip("Mage", 0, 4, dead = true),
                    CrawlChip("Rogue", 2, 6, dead = false)
                )
            )
        ),
        // 11 — hero special abilities reduce damage (here, to zero)
        TutorialStep(
            "Each hero has a special ability. A Barbarian reduces all damage that they take by half. Rogues reduce all damage the party takes from trap rooms by 2. Clerics reduce all damage rooms with the undead bait icon deal by 4. Mages reduce all damage rooms with the arcane bait icon by 4.",
            TutorialBoard(
                caption = "Cleric + Mage shrug off this undead/arcane dungeon entirely.",
                dungeon = DungeonView(
                    "Player 1",
                    rooms = listOf(
                        room("room_zombies"),   // undead · 3 — Cleric −4 → 0
                        room("room_mana_storm"),// power · 3 — Mage −4 → 0
                        room("plague_zombie")   // undead · 4 — Cleric −4 → 0
                    ),
                    boss = boss("boss_lich")    // undead + power
                ),
                crawl = listOf(
                    CrawlChip("Cleric", 5, 5, dead = false),
                    CrawlChip("Mage", 4, 4, dead = false)
                )
            )
        ),
        // 12 — ability cards
        TutorialStep(
            "Each player has a number of ability cards that they can play. These cards may make a party flee, increase the damage they take, or prevent a room from damaging them. There is also another card that draws additional room cards. Players start with two ability cards and gain another card each round that no one attacks them.",
            TutorialBoard(
                abilities = listOf(
                    ability("ability_return_to_town"),
                    ability("ability_extra_damage"),
                    ability("ability_no_damage"),
                    ability("ability_full_damage"),
                    ability("ability_draw_rooms")
                )
            )
        ),
        // 13 — wounds and the win condition
        TutorialStep(
            "Whenever a hero survives all of the rooms of a dungeon that player gains a wound. If they gain 5 wounds they lose. Whenever a player gains 10 points then the game ends. Whoever ends the game gains 5 points. After the game ends each player loses 2 points per wound, and whoever ends up with the most points wins.",
            TutorialBoard(
                caption = "Race to 10 points without collecting 5 wounds.",
                baitTotals = listOf(
                    BaitTotalsRow("Player 1", mapOf(Bait.GLORY to 2, Bait.RICHES to 1, Bait.UNDEAD to 2, Bait.POWER to 2))
                ),
                dungeon = showcaseDungeon(points = 8)
            )
        )
    )
}
