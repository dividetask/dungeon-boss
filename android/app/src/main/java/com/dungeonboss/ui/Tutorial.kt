package com.dungeonboss.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.dungeonboss.game.Decision
import com.dungeonboss.game.DecisionKind
import com.dungeonboss.game.Dungeon
import com.dungeonboss.game.Game
import com.dungeonboss.game.Party
import com.dungeonboss.game.PartyCrawlResolver
import com.dungeonboss.model.AbilityCard
import com.dungeonboss.model.Bait
import com.dungeonboss.model.Boss
import com.dungeonboss.model.Hero
import com.dungeonboss.model.Room
import kotlinx.coroutines.delay

/**
 * The non-interactive tutorial. Each step renders a real [Game] snapshot through
 * the same composables the live game uses ([GameBody] / [DungeonBoard] / the
 * [PlayerStatsStrip]), so every screen looks identical to the actual game — only
 * the narration line and the Continue button are tutorial-specific. The board
 * cannot be touched; Back / Continue / Exit drive the flow. Finishing or exiting
 * returns to the screen underneath (the Load screen when launched there).
 */

/**
 * What, if anything, animates on a step to draw the eye.
 *  - CYCLE        spotlights each hero and its preferred bait in the rooms
 *  - CYCLE_TOTALS spotlights each hero and its bait in the top-right totals strip
 *  - TIMID_POINTS blinks the town's timid markers and the points (🪙) total
 */
private enum class Highlight { NONE, FLASH_ALL_BAIT, CYCLE, CYCLE_TOTALS, TIMID_POINTS }

/** A party about to crawl a dungeon, with its (dry-run) predicted outcome. */
private class CrawlView(val party: Party, val prediction: PartyCrawlResolver.Result)

private class TutorialStep(
    val text: String,
    val game: Game,
    val viewed: String = HUMAN,
    val decision: Decision? = null,
    val crawl: CrawlView? = null,
    val highlight: Highlight = Highlight.NONE,
    /** Heroes the spotlight cycles through (for [Highlight.CYCLE]). */
    val cycleHeroes: List<Hero> = emptyList()
)

private const val HUMAN = "Player 1"

@Composable
fun TutorialScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val steps = remember {
        val lib = context.assets.open(GameViewModel.CARDS_ASSET).use { CardLibrary.load(it) }
        buildTutorial(lib)
    }

    var index by remember { mutableStateOf(0) }
    val step = steps[index]

    // A pulsing opacity (0..1) driving every bait glow on the board.
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

    // The spotlight cycles through a step's heroes (steps 4–5).
    val cycling = step.highlight == Highlight.CYCLE || step.highlight == Highlight.CYCLE_TOTALS
    val cycleCount = step.cycleHeroes.size.coerceAtLeast(1)
    var cycle by remember(index) { mutableStateOf(0) }
    LaunchedEffect(index, cycleCount) {
        if (cycling) {
            while (true) {
                delay(1500)
                cycle = (cycle + 1) % cycleCount
            }
        }
    }

    val activeHero = if (cycling) step.cycleHeroes.getOrNull(cycle) else null
    // CYCLE highlights the bait in the rooms; CYCLE_TOTALS highlights it in the
    // top-right totals strip instead (rooms left alone).
    val baitHighlight: Set<Bait> = when (step.highlight) {
        Highlight.FLASH_ALL_BAIT -> Bait.entries.toSet()
        Highlight.CYCLE -> activeHero?.let { setOf(it.preferredBait) } ?: emptySet()
        else -> emptySet()
    }
    val totalsBait: Bait? = if (step.highlight == Highlight.CYCLE_TOTALS) activeHero?.preferredBait else null
    val highlightHeroId = activeHero?.id
    // Step 7: blink the timid markers and the points total.
    val timidBlink = step.highlight == Highlight.TIMID_POINTS
    val timidGlow = if (timidBlink) glow else 1f
    val human = step.game.players.first { it.name == HUMAN }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Page)
            // Swallow taps on empty areas so nothing reaches a game underneath.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .widthIn(max = 920.dp)
                .background(Palette.AppBg)
        ) {
            // Top bar: mirrors the game's — title, the per-player totals strip,
            // and an Exit where the ☰ menu would be.
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Dungeon Boss", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("· Tutorial ${index + 1}/${steps.size}", fontSize = 12.sp, color = Palette.SubText)
                Spacer(Modifier.weight(1f))
                PlayerStatsStrip(step.game, human, highlightBait = totalsBait, highlightPoints = timidBlink, glow = glow)
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

            // Board — the real game body, or a pre-crawl dungeon preview.
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val crawl = step.crawl
                if (crawl != null) {
                    val owner = step.game.players.first { it.name == step.viewed }
                    DungeonBoard(
                        tick = 0,
                        game = step.game,
                        player = owner,
                        decision = null,
                        onChooseBoss = {},
                        roomClick = null,
                        newSlotClick = null,
                        boostRooms = emptySet(),
                        onBoost = {},
                        onShowDetail = {},
                        activeIndex = null,
                        incoming = crawl.party,
                        prediction = crawl.prediction
                    )
                } else {
                    GameBody(
                        tick = 0,
                        game = step.game,
                        humanName = HUMAN,
                        viewed = step.viewed,
                        decision = step.decision,
                        selection = null,
                        onSelect = {},
                        onDecide = { _, _ -> },
                        pendingAbility = null,
                        onPickAbility = {},
                        onTargetRoom = {},
                        pendingBoostRoom = null,
                        onChooseBoostRoom = {},
                        onBoostWithCard = {},
                        onPlayBlueprints = {},
                        onShowDetail = {},
                        onNewGame = {},
                        activeIndex = null,
                        heroHp = emptyMap(),
                        deadSet = emptyList(),
                        baitHighlight = baitHighlight,
                        baitGlow = glow,
                        highlightHeroId = highlightHeroId,
                        timidGlow = timidGlow
                    )
                }
            }

            // Narration + controls, where the game's advance bar sits.
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Palette.HighlightFill)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(step.text, fontSize = 14.sp, color = Color(0xFF222222), modifier = Modifier.weight(1f))
                if (index > 0) {
                    OutlinedButton(onClick = { index -= 1 }) { Text("Back", fontSize = 13.sp) }
                }
                Button(
                    onClick = { if (index < steps.lastIndex) index += 1 else onExit() },
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent)
                ) {
                    Text(if (index < steps.lastIndex) "Continue" else "Finish", color = Color.White)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Content: 13 narrated steps over real Game snapshots. The narration is the only
// text shown; everything else is the live game's own rendering.
// ---------------------------------------------------------------------------

private fun buildTutorial(lib: CardLibrary): List<TutorialStep> {
    fun room(id: String): Room = lib.rooms.first { it.id == id }
    fun boss(id: String): Boss = lib.bosses.first { it.id == id }
    fun hero(id: String): Hero = lib.heroes.first { it.id == id }
    fun ability(id: String): AbilityCard = lib.abilityCards.first { it.id == id }

    fun newGame() = Game(lib, listOf("Player 1", "Player 2"))

    // Build a dungeon: rooms left→right (first id is the entrance), then the boss.
    fun dungeon(bossId: String, roomIds: List<String>): Dungeon {
        val d = Dungeon(boss(bossId))
        roomIds.reversed().forEach { d.addRoomToLeft(room(it)) } // addRoomToLeft prepends
        return d
    }

    // A varied dungeon with one room of each bait (used across several steps).
    val showcaseRooms = listOf(
        "room_stone_ball",   // glory · 5
        "room_golden_idol",  // riches · 3
        "room_ghoul_pit",    // undead · 5
        "room_will_o_wisps", // power · 5
        "room_gladiator"     // glory · 4
    )
    fun showcase() = dungeon("boss_lich", showcaseRooms) // boss: undead + power

    fun lone(vararg ids: String) = ids.map { Party(listOf(hero(it))) }

    // Player 1's showcase dungeon; Player 2 gets a different one so the totals
    // strip shows a real comparison.
    fun gameWithDungeons(points: Int = 0): Game {
        val g = newGame()
        g.players[0].dungeon = showcase()
        g.players[0].points = points
        g.players[1].dungeon = dungeon("boss_oni", listOf("room_goblins", "room_suspicious_treasure", "room_mana_storm"))
        return g
    }

    // Player 1 holds the showcase dungeon; Player 2 gets a deliberately weak one
    // (strictly less of every bait), so town heroes are unambiguously lured to
    // Player 1 — and thus timid — for the courage / party steps. Player 2 still
    // needs *a* dungeon: the bait math force-unwraps every living player's.
    fun soloGame(points: Int): Game {
        val g = newGame()
        g.players[0].dungeon = showcase() // glory 2, riches 1, undead 2, power 2
        g.players[0].points = points
        g.players[1].dungeon = dungeon("boss_lich", listOf("room_goblins")) // glory 1, undead 1, power 1
        return g
    }

    val cycleHeroes = listOf(hero("hero_barbarian"), hero("hero_rogue"), hero("hero_cleric"), hero("hero_mage"))

    // A small representative hand for the bait-teaching steps.
    fun handCards() = listOf(room("room_goblins"), room("room_mana_storm"), room("room_golden_idol"))

    // 1 — intro
    val s1 = TutorialStep(
        "In Dungeon Boss you play the villain. You have a dungeon of 5 rooms plus the boss chamber where heroes come in and try to defeat you.",
        gameWithDungeons()
    )

    // 2 — choose boss + place rooms (real CHOOSE_BOSS decision; dungeon not yet built)
    val g2 = newGame()
    g2.players[0].roomHand.addAll(
        listOf(room("room_stone_ball"), room("room_goblins"), room("room_ghoul_pit"), room("room_golden_idol"))
    )
    val s2 = TutorialStep(
        "The game is played by first choosing a boss from two cards, then placing rooms in one of 5 slots.",
        g2,
        decision = Decision(DecisionKind.CHOOSE_BOSS, g2.players[0], listOf(boss("boss_medusa"), boss("boss_oni")))
    )

    // 3 — rooms have bait icons (all flashing)
    val g3 = gameWithDungeons()
    g3.town.addAll(lone("hero_barbarian", "hero_rogue", "hero_cleric", "hero_mage"))
    g3.players[0].roomHand.addAll(handCards())
    val s3 = TutorialStep(
        "Each room, including the boss room, has icons at the bottom right indicating which heroes will be attracted to them.",
        g3, highlight = Highlight.FLASH_ALL_BAIT
    )

    // 4 — each hero prefers a bait (cycle hero + its bait in the rooms)
    val g4 = gameWithDungeons()
    g4.town.addAll(lone("hero_barbarian", "hero_rogue", "hero_cleric", "hero_mage"))
    g4.players[0].roomHand.addAll(handCards())
    val s4 = TutorialStep(
        "Each hero prefers a specific type of bait. Barbarians seek glory, Rogues seek riches, Clerics seek to destroy the undead, and Mages seek Arcane Power.",
        g4, highlight = Highlight.CYCLE, cycleHeroes = cycleHeroes
    )

    // 5 — heroes go to the dungeon with the most preferred bait; totals top-right
    val g5 = gameWithDungeons()
    g5.town.addAll(lone("hero_barbarian", "hero_rogue", "hero_cleric", "hero_mage"))
    g5.players[0].roomHand.addAll(handCards())
    val s5 = TutorialStep(
        "Each hero will take turns, starting with the left most hero, crawling through a dungeon. The hero will only go to a dungeon if that dungeon has more of the hero's preferred bait than the other dungeons. If there is a tie then the hero will stay in town. At the top right of the screen we can see each player's bait totals.",
        g5, highlight = Highlight.CYCLE_TOTALS, cycleHeroes = cycleHeroes
    )

    // 6 — crawl room by room; the hero dies
    val g6 = newGame()
    val deadly = dungeon("boss_medusa", listOf("room_stone_ball", "room_ghoul_pit", "room_will_o_wisps", "room_gladiator", "room_goblins"))
    g6.players[0].dungeon = deadly
    val mage = Party(listOf(hero("hero_mage")))
    val s6 = TutorialStep(
        "When a hero crawls a dungeon they will go through each room from left to right. Each room will deal the listed damage to the hero until that hero dies or each room is completed.",
        g6, crawl = CrawlView(mage, PartyCrawlResolver.resolve(mage, deadly, dryRun = true))
    )

    // 7 — score a point per death; courage
    val g7 = soloGame(points = 3)
    g7.town.addAll(lone("hero_barbarian", "hero_rogue", "hero_cleric", "hero_mage"))
    val s7 = TutorialStep(
        "Each player scores a point for each hero that dies in their dungeon. When heroes die in a dungeon other heroes become more cautious and may avoid that dungeon depending upon their courage score. Barbarians and Clerics have a courage of 2, which means they will avoid dungeons that have 3 or more points. Rogues and Mages have a courage of 1, which means they will avoid dungeons that have 2 or more points.",
        g7, highlight = Highlight.TIMID_POINTS
    )

    // 8 — skipped heroes stay and form parties
    val g8 = soloGame(points = 3)
    g8.town.addAll(lone("hero_barbarian", "hero_rogue", "hero_cleric", "hero_mage"))
    val s8 = TutorialStep(
        "When heroes skip a dungeon, due to low courage or when a dungeon has tied bait, they stay in town and will form a party at the end of the round. Each hero, or party, that stayed in town will try to add one new member to their party. Each will add the left most unpartied hero preferring heroes of classes they don't have.",
        g8
    )

    // 9 — parties: courage = sum, bait = all members'
    val g9 = soloGame(points = 2)
    g9.town.add(Party(listOf(hero("hero_barbarian"), hero("hero_rogue"))))
    g9.town.add(Party(listOf(hero("hero_cleric"), hero("hero_mage"))))
    val s9 = TutorialStep(
        "Parties have a courage score equal to the sum of each party member. Parties also consider all members' bait preferences. If a party has multiple heroes of the same class then that class's bait is added once for each hero.",
        g9
    )

    // 10 — party crawl; highest-HP hero takes the hit; some die
    val g10 = gameWithDungeons()
    val mixedParty = Party(listOf(hero("hero_barbarian"), hero("hero_mage"), hero("hero_rogue")))
    val s10 = TutorialStep(
        "When a party explores a dungeon the hero with the highest HP typically takes the damage for the next room, but some rooms have special abilities that target all heroes or target specific heroes.",
        g10, crawl = CrawlView(mixedParty, PartyCrawlResolver.resolve(mixedParty, g10.players[0].dungeon!!, dryRun = true))
    )

    // 11 — hero special abilities reduce damage (here, to nothing)
    val g11 = newGame()
    val warded = dungeon("boss_lich", listOf("room_zombies", "room_mana_storm", "plague_zombie"))
    g11.players[0].dungeon = warded
    val clericMage = Party(listOf(hero("hero_cleric"), hero("hero_mage")))
    val s11 = TutorialStep(
        "Each hero has a special ability. A Barbarian reduces all damage that they take by half. Rogues reduce all damage the party takes from trap rooms by 2. Clerics reduce all damage rooms with the undead bait icon deal by 4. Mages reduce all damage rooms with the arcane bait icon by 4.",
        g11, crawl = CrawlView(clericMage, PartyCrawlResolver.resolve(clericMage, warded, dryRun = true))
    )

    // 12 — ability cards (shown in hand)
    val g12 = gameWithDungeons()
    g12.players[0].abilityHand.addAll(
        listOf(
            ability("ability_return_to_town"),
            ability("ability_extra_damage"),
            ability("ability_no_damage"),
            ability("ability_full_damage"),
            ability("ability_draw_rooms")
        )
    )
    val s12 = TutorialStep(
        "Each player has a number of ability cards that they can play. These cards may make a party flee, increase the damage they take, or prevent a room from damaging them. There is also another card that draws additional room cards. Players start with two ability cards and gain another card each round that no one attacks them.",
        g12
    )

    // 13 — wounds and the win condition
    val s13 = TutorialStep(
        "Whenever a hero survives all of the rooms of a dungeon that player gains a wound. If they gain 5 wounds they lose. Whenever a player gains 10 points then the game ends. Whoever ends the game gains 5 points. After the game ends each player loses 2 points per wound, and whoever ends up with the most points wins.",
        gameWithDungeons(points = 8)
    )

    return listOf(s1, s2, s3, s4, s5, s6, s7, s8, s9, s10, s11, s12, s13)
}
