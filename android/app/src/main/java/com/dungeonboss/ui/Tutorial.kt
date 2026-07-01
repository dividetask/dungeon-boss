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
 * the same composables the live game uses ([GameBody] / [DungeonBoard] /
 * [PlayerStatsStrip]), so every screen looks identical to the actual game — only
 * the narration line and the Continue button are tutorial-specific. The board
 * cannot be touched; Back / Continue / Exit drive the flow.
 *
 * Rewritten for the current rules: 5 slots, the Discard→Draw→Build flow, room
 * upgrades, the lead/all/rear damage channels, resist and poison, advanced rooms,
 * draw-on-death and discard-to-boost, levelling heroes, and the end-game bonus.
 */

/** What, if anything, animates on a step to draw the eye. */
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

    // A pulsing opacity (0..1) driving every bait glow / timid blink on the board.
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

    // The spotlight cycles through a step's heroes.
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
    val baitHighlight: Set<Bait> = when (step.highlight) {
        Highlight.FLASH_ALL_BAIT -> Bait.entries.toSet()
        Highlight.CYCLE -> activeHero?.let { setOf(it.preferredBait) } ?: emptySet()
        else -> emptySet()
    }
    val totalsBait: Bait? = if (step.highlight == Highlight.CYCLE_TOTALS) activeHero?.preferredBait else null
    val highlightHeroId = activeHero?.id
    val timidBlink = step.highlight == Highlight.TIMID_POINTS
    val timidGlow = if (timidBlink) glow else 1f
    val human = step.game.players.first { it.name == HUMAN }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Page)
            // Swallow taps on empty areas so nothing reaches the game underneath.
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
                        slotPlace = null,
                        canPlaceInSlot = null,
                        slotUpgrade = null,
                        encounterClick = null,
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
                        discardSelection = emptyList(),
                        onToggleDiscard = {},
                        pendingAbility = null,
                        onPickAbility = {},
                        onTargetRoom = {},
                        pendingBoostRoom = null,
                        onChooseBoostRoom = {},
                        onBoostWithCard = {},
                        onPlayBlueprints = {},
                        onShowDetail = {},
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
// Content: narrated steps over real Game snapshots. The narration is the only
// tutorial text; everything else is the live game's own rendering.
// ---------------------------------------------------------------------------

private fun buildTutorial(lib: CardLibrary): List<TutorialStep> {
    // Rooms live in two pools (basic + advanced); look up across both.
    val allRooms = lib.rooms + lib.advancedRooms
    fun room(id: String): Room = allRooms.first { it.id == id }
    fun boss(id: String): Boss = lib.bosses.first { it.id == id }
    fun hero(id: String): Hero = lib.heroes.first { it.id == id }
    fun ability(id: String): AbilityCard = lib.abilityCards.first { it.id == id }

    fun newGame() = Game(lib, listOf("Player 1", "Player 2"))

    // Build a dungeon: rooms fill slots left→right (first id is the entrance).
    fun dungeon(bossId: String, roomIds: List<String>): Dungeon {
        val d = Dungeon(boss(bossId))
        roomIds.forEachIndexed { slot, id -> d.placeRoom(slot, room(id)) }
        return d
    }

    // A varied dungeon touching every bait (used across several steps).
    val showcaseRooms = listOf(
        "room_champion",  // glory · 4
        "room_mimic",     // riches · 4
        "room_zombies",   // undead · 4
        "room_succubus",  // arcane · 4
        "room_goblins"    // glory · 3
    )
    fun showcase() = dungeon("boss_lich", showcaseRooms) // boss_lich: undead + arcane

    fun lone(vararg ids: String) = ids.map { Party(listOf(hero(it))) }

    // Player 1's showcase dungeon; Player 2 gets a different one so the totals
    // strip shows a real comparison.
    fun gameWithDungeons(points: Int = 0): Game {
        val g = newGame()
        g.players[0].dungeon = showcase()
        g.players[0].points = points
        g.players[1].dungeon = dungeon("boss_oni", listOf("room_goblins", "room_skeletons", "room_fireball"))
        return g
    }

    // Player 1 gets the showcase; Player 2 a strictly weaker dungeon, so town
    // heroes are unambiguously lured to Player 1 (and thus timid) for the
    // courage / party steps.
    fun soloGame(points: Int): Game {
        val g = newGame()
        g.players[0].dungeon = showcase()
        g.players[0].points = points
        g.players[1].dungeon = dungeon("boss_medusa", listOf("room_goblins")) // glory only
        return g
    }

    val cycleHeroes = listOf(hero("hero_barbarian"), hero("hero_rogue"), hero("hero_cleric"), hero("hero_mage"))
    fun handCards() = listOf(room("room_goblins"), room("room_fireball"), room("room_mimic"))

    val steps = mutableListOf<TutorialStep>()

    // 1 — intro
    steps += TutorialStep(
        "In Dungeon Boss you play the villain. You build a dungeon of up to 5 rooms plus a boss chamber, and lure wandering heroes in to their doom.",
        gameWithDungeons()
    )

    // 2 — choose a boss
    val g2 = newGame()
    g2.players[0].roomHand.addAll(listOf(room("room_champion"), room("room_goblins"), room("room_skeletons"), room("room_mimic")))
    steps += TutorialStep(
        "Each game starts by choosing your boss from two cards. The boss guards the right end of your dungeon and hits the lead hero.",
        g2,
        decision = Decision(DecisionKind.CHOOSE_BOSS, g2.players[0], listOf(boss("boss_medusa"), boss("boss_oni")))
    )

    // 3 — Discard → Draw
    val g3 = gameWithDungeons()
    g3.players[0].roomHand.addAll(handCards())
    steps += TutorialStep(
        "Each turn you may discard up to two room cards, then draw 1 card plus 1 for every card you discarded — so discarding digs deeper for the rooms you want.",
        g3,
        decision = Decision(DecisionKind.DISCARD_ROOMS, g3.players[0], g3.players[0].roomHand, allowSkip = true)
    )

    // 4 — Build: place into a slot
    val g4 = gameWithDungeons()
    g4.players[0].roomHand.addAll(handCards())
    steps += TutorialStep(
        "Then you build: place a room into any of the 5 slots. Placing onto an occupied slot replaces that room. A hero enters from the left and crawls right toward the boss.",
        g4,
        decision = Decision(DecisionKind.BUILD_ROOM, g4.players[0], g4.players[0].roomHand, allowSkip = true)
    )

    // 5 — Upgrading a room
    val g5 = gameWithDungeons()
    g5.players[0].roomHand.addAll(handCards())
    steps += TutorialStep(
        "Instead of placing a card, you can spend it to UPGRADE a placed room: the room gains that card's bait icons and rises a level, making it hit harder. Advanced rooms upgrade it twice.",
        g5,
        decision = Decision(DecisionKind.BUILD_ROOM, g5.players[0], g5.players[0].roomHand, allowSkip = true)
    )

    // 6 — bait icons (all flashing)
    val g6 = gameWithDungeons()
    g6.town.addAll(lone("hero_barbarian", "hero_rogue", "hero_cleric", "hero_mage"))
    steps += TutorialStep(
        "Every room — and the boss — shows bait icons at the bottom right. Heroes are lured by the bait they crave.",
        g6, highlight = Highlight.FLASH_ALL_BAIT
    )

    // 7 — each hero prefers a bait (cycle)
    val g7 = gameWithDungeons()
    g7.town.addAll(lone("hero_barbarian", "hero_rogue", "hero_cleric", "hero_mage"))
    steps += TutorialStep(
        "Each hero prefers one bait: Barbarians seek glory, Rogues seek riches, Clerics hunt the undead, and Mages crave arcane power.",
        g7, highlight = Highlight.CYCLE, cycleHeroes = cycleHeroes
    )

    // 8 — luring (totals top-right)
    val g8 = gameWithDungeons()
    g8.town.addAll(lone("hero_barbarian", "hero_rogue", "hero_cleric", "hero_mage"))
    steps += TutorialStep(
        "Heroes take turns, left first. A hero enters the dungeon with the most of its preferred bait; on a tie it stays in town. The totals strip (top right) compares each player's bait.",
        g8, highlight = Highlight.CYCLE_TOTALS, cycleHeroes = cycleHeroes
    )

    // 9 — crawl and die
    val deadly = dungeon("boss_medusa", listOf("room_champion", "room_zombies", "room_succubus", "adv_gladiator", "room_goblins"))
    val g9 = newGame().also { it.players[0].dungeon = deadly }
    val loneMage = Party(listOf(hero("hero_mage")))
    steps += TutorialStep(
        "A hero crawls the rooms left to right. Each room deals its damage until the hero dies or clears the boss.",
        g9, crawl = CrawlView(loneMage, PartyCrawlResolver.resolve(loneMage, deadly, dryRun = true))
    )

    // 10 — damage channels (lead / all / weakest)
    val channels = dungeon("boss_lich", listOf("room_fireball", "adv_black_tentacles", "room_champion"))
    val g10 = newGame().also { it.players[0].dungeon = channels }
    val trio = Party(listOf(hero("hero_barbarian"), hero("hero_mage"), hero("hero_rogue")))
    steps += TutorialStep(
        "Most rooms hit the lead hero (the one with the most HP). But some rooms hit ALL heroes, and others strike the weakest hero first — read each room's card for what it does.",
        g10, crawl = CrawlView(trio, PartyCrawlResolver.resolve(trio, channels, dryRun = true))
    )

    // 11 — points + courage (timid blink)
    val g11 = soloGame(points = 3)
    g11.town.addAll(lone("hero_barbarian", "hero_rogue", "hero_cleric", "hero_mage"))
    steps += TutorialStep(
        "You score a point per hero that dies in your dungeon. As your points rise, heroes grow cautious: a hero (or party) enters only if its courage is at least your points, otherwise it stays away.",
        g11, highlight = Highlight.TIMID_POINTS
    )

    // 12 — stay and form parties
    val g12 = soloGame(points = 3)
    g12.town.addAll(lone("hero_barbarian", "hero_rogue", "hero_cleric", "hero_mage"))
    steps += TutorialStep(
        "Heroes that skip a dungeon (too timid, or a bait tie) stay in town and band into parties at the end of the round, each recruiting the leftmost hero of a class it lacks.",
        g12
    )

    // 13 — party crawl + overkill
    val g13 = gameWithDungeons()
    val party = Party(listOf(hero("hero_barbarian"), hero("hero_mage"), hero("hero_rogue")))
    steps += TutorialStep(
        "A party crawls together. A lead hit strikes the highest-HP member, and any leftover (overkill) spills to the next — a party's combined courage and bait are the sum of its members.",
        g13, crawl = CrawlView(party, PartyCrawlResolver.resolve(party, g13.players[0].dungeon!!, dryRun = true))
    )

    // 14 — hero abilities
    val warded = dungeon("boss_lich", listOf("room_zombies", "room_fireball", "room_succubus"))
    val g14 = newGame().also { it.players[0].dungeon = warded }
    val clericMage = Party(listOf(hero("hero_cleric"), hero("hero_mage")))
    steps += TutorialStep(
        "Each hero has a defence: a Barbarian halves the damage he takes, a Rogue cuts trap damage by 2 for the party, a Cleric cuts undead damage by 4, and a Mage cuts arcane damage by 4. These grow as heroes level up.",
        g14, crawl = CrawlView(clericMage, PartyCrawlResolver.resolve(clericMage, warded, dryRun = true))
    )

    // 15 — resist and poison
    val nasty = dungeon("boss_medusa", listOf("adv_gladiator", "room_floor_spike", "adv_cursed_ring"))
    val g15 = newGame().also { it.players[0].dungeon = nasty }
    val tough = Party(listOf(hero("hero_barbarian"), hero("hero_cleric")))
    steps += TutorialStep(
        "Some rooms bypass those defences: their damage cannot be reduced, or cannot be halved. Others poison a hero, dealing extra damage in later rooms. Tap a room's ℹ to read exactly what it does.",
        g15, crawl = CrawlView(tough, PartyCrawlResolver.resolve(tough, nasty, dryRun = true))
    )

    // 16 — advanced rooms, draw-on-death, discard-to-boost
    val g16 = newGame()
    g16.players[0].dungeon = dungeon("boss_necromancer", listOf("room_sacrifice_pit", "adv_troll", "room_power_word"))
    g16.players[0].roomHand.addAll(listOf(room("room_goblins"), room("room_mimic")))
    steps += TutorialStep(
        "Advanced rooms are powerful; they replace a room that shares a bait icon. Some rooms let you draw cards when a hero dies in them, and a few let you discard a card mid-crawl to boost their damage for that crawl.",
        g16
    )

    // 17 — ability cards
    val g17 = gameWithDungeons()
    g17.players[0].abilityHand.addAll(
        listOf(
            ability("ability_return_to_town"),
            ability("ability_extra_damage"),
            ability("ability_no_damage"),
            ability("ability_full_damage"),
            ability("ability_draw_rooms")
        )
    )
    steps += TutorialStep(
        "You also hold ability cards, played before a crawl: make a party flee, add or negate a room's damage, expose a room so it can't be reduced, or draw extra rooms. You start with two and gain one each round no hero attacks you.",
        g17
    )

    // 18 — wounds and winning
    steps += TutorialStep(
        "If a hero survives your whole dungeon you take a wound; 5 wounds knocks you out. The game ends when someone reaches 10 points — the player who ends it gains 5 bonus points. Final score is points minus 2 per wound; highest wins.",
        gameWithDungeons(points = 8)
    )

    return steps
}
