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
    /** A pre-selected hand card, so a Build step shows placement + upgrade cues. */
    val selection: Selection? = null,
    val crawl: CrawlView? = null,
    val highlight: Highlight = Highlight.NONE,
    /** Heroes the spotlight cycles through (for [Highlight.CYCLE]). */
    val cycleHeroes: List<Hero> = emptyList(),
    /** When set, the step alternates between [game] and [altGame] every ~1.5s. */
    val altGame: Game? = null
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

    // A card whose full details are open (from tapping its ℹ), or null.
    var detailCard by remember { mutableStateOf<Any?>(null) }

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

    // Steps that alternate between two snapshots (e.g. lone heroes ↔ a party).
    var alt by remember(index) { mutableStateOf(false) }
    LaunchedEffect(index) {
        if (step.altGame != null) {
            while (true) {
                delay(1500)
                alt = !alt
            }
        }
    }
    val shownGame = if (alt && step.altGame != null) step.altGame else step.game

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
    val human = shownGame.players.first { it.name == HUMAN }

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
                PlayerStatsStrip(shownGame, human, highlightBait = totalsBait, highlightPoints = timidBlink, glow = glow)
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
                    val owner = shownGame.players.first { it.name == step.viewed }
                    DungeonBoard(
                        tick = 0,
                        game = shownGame,
                        player = owner,
                        decision = null,
                        onChooseBoss = {},
                        slotPlace = null,
                        canPlaceInSlot = null,
                        slotUpgrade = null,
                        encounterClick = null,
                        boostRooms = emptySet(),
                        onBoost = {},
                        onShowDetail = { detailCard = it },
                        activeIndex = null,
                        incoming = crawl.party,
                        prediction = crawl.prediction
                    )
                } else {
                    GameBody(
                        tick = 0,
                        game = shownGame,
                        humanName = HUMAN,
                        viewed = step.viewed,
                        decision = step.decision,
                        selection = step.selection,
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
                        onShowDetail = { detailCard = it },
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
        // Tapping a card's ℹ opens its full details, even in the tutorial.
        detailCard?.let { CardDetailDialog(it) { detailCard = null } }
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

    // A DISTINCT hero copy at a chosen level for the leveling demo — using the
    // last pool copy (never the hero(id) first copy) so its mutated level doesn't
    // leak into other steps.
    fun leveled(id: String, level: Int): Hero =
        lib.heroes.last { it.id == id }.also { it.level = level }

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
        g.players[1].dungeon = dungeon("boss_medusa", listOf("room_goblins")) // low bait, strictly weaker
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
        "First, choose your boss. Each boss has different abilities — tap a card's ℹ to read them. Pick one; the other is discarded.",
        g2,
        decision = Decision(DecisionKind.CHOOSE_BOSS, g2.players[0], listOf(boss("boss_medusa"), boss("boss_oni")))
    )

    // Phase 1 — Arrival
    val gArrive = gameWithDungeons()
    gArrive.town.addAll(lone("hero_barbarian", "hero_rogue", "hero_cleric"))
    steps += TutorialStep(
        "Phase 1: Arrival. A new hero arrives for each player.",
        gArrive
    )

    // 3 — Discard → Draw
    val g3 = gameWithDungeons()
    g3.players[0].roomHand.addAll(handCards())
    steps += TutorialStep(
        "Phase 2: Draw Phase. Discard up to two room cards and draw one card plus another per discard.",
        g3,
        decision = Decision(DecisionKind.DISCARD_ROOMS, g3.players[0], g3.players[0].roomHand, allowSkip = true)
    )

    // 4 — Build: place into a slot (an incomplete dungeon so empty slots + the
    // Upgrade cue under placed rooms are both visible)
    val g4 = gameWithDungeons()
    g4.players[0].dungeon = dungeon("boss_lich", listOf("room_champion", "room_mimic", "room_zombies"))
    g4.players[0].roomHand.addAll(handCards())
    steps += TutorialStep(
        "Phase 3: Build Phase. Place a room into any of the 5 slots — an occupied slot is replaced. Heroes crawl from the left toward the boss.",
        g4,
        decision = Decision(DecisionKind.BUILD_ROOM, g4.players[0], g4.players[0].roomHand, allowSkip = true),
        selection = Selection("room_goblins")
    )

    // 5 — Upgrading a room (same incomplete dungeon + a selected card, so the
    // Upgrade button shows under each placed room)
    val g5 = gameWithDungeons()
    g5.players[0].dungeon = dungeon("boss_lich", listOf("room_champion", "room_mimic", "room_zombies"))
    g5.players[0].roomHand.addAll(handCards())
    steps += TutorialStep(
        "Instead of placing a card, you can spend it to upgrade a placed room: the room gains that card's bait icons and rises a level, making it hit harder. Advanced rooms upgrade it twice.",
        g5,
        decision = Decision(DecisionKind.BUILD_ROOM, g5.players[0], g5.players[0].roomHand, allowSkip = true),
        selection = Selection("room_goblins")
    )

    // 6 — bait icons (all flashing)
    val g6 = gameWithDungeons()
    g6.town.addAll(lone("hero_barbarian", "hero_rogue", "hero_cleric", "hero_mage"))
    steps += TutorialStep(
        "Every room — and the boss — shows bait icons at the bottom right.",
        g6, highlight = Highlight.FLASH_ALL_BAIT
    )

    // 7 — each hero prefers a bait (cycle)
    val g7 = gameWithDungeons()
    g7.town.addAll(lone("hero_barbarian", "hero_rogue", "hero_cleric", "hero_mage"))
    steps += TutorialStep(
        "Each type of bait lures a different type of hero. Barbarians seek glory, Rogues seek riches, Clerics hunt the undead, and Mages crave arcane power.",
        g7, highlight = Highlight.CYCLE, cycleHeroes = cycleHeroes
    )

    // 8 — luring (totals top-right)
    val g8 = gameWithDungeons()
    g8.town.addAll(lone("hero_barbarian", "hero_rogue", "hero_cleric", "hero_mage"))
    steps += TutorialStep(
        "Each hero, from left to right, takes turns entering dungeons. They enter the dungeon with the most bait of their type, or stay in town if there is a tie. See each dungeon's totals at the top right.",
        g8, highlight = Highlight.CYCLE_TOTALS, cycleHeroes = cycleHeroes
    )

    // 9 — crawl and die
    val deadly = dungeon("boss_medusa", listOf("room_champion", "room_zombies", "room_succubus", "adv_gladiator", "room_goblins"))
    val g9 = newGame().also { it.players[0].dungeon = deadly }
    val loneMage = Party(listOf(hero("hero_mage")))
    steps += TutorialStep(
        "A hero enters the rooms from left to right, until he dies or completes each room.",
        g9, crawl = CrawlView(loneMage, PartyCrawlResolver.resolve(loneMage, deadly, dryRun = true))
    )

    // 10 — damage channels (lead / all / weakest)
    val channels = dungeon("boss_lich", listOf("room_fireball", "adv_black_tentacles", "room_champion"))
    val g10 = newGame().also { it.players[0].dungeon = channels }
    val trio = Party(listOf(hero("hero_barbarian"), hero("hero_mage"), hero("hero_rogue")))
    steps += TutorialStep(
        "For parties, a room typically only damages one hero, targeting the one with the most hit points. Any remaining damage is then applied to the next highest hit points, until there are no more heroes or damage to apply. Some rooms start at the weakest, some damage all, and some only damage a specific type of hero.",
        g10, crawl = CrawlView(trio, PartyCrawlResolver.resolve(trio, channels, dryRun = true))
    )

    // 11 — points + courage (timid blink)
    val g11 = soloGame(points = 3)
    g11.town.addAll(lone("hero_barbarian", "hero_rogue", "hero_cleric", "hero_mage"))
    steps += TutorialStep(
        "You score a point per hero that dies in your dungeon. As your points rise, heroes grow cautious: a hero (or party) enters only if its courage is at least your point total, otherwise it stays away. Parties have courage equal to the sum of their heroes' courage.",
        g11, highlight = Highlight.TIMID_POINTS
    )

    // 12 — stay and form parties (alternates two lone heroes <-> them as a party)
    val g12a = soloGame(points = 3)
    g12a.town.addAll(lone("hero_barbarian", "hero_rogue"))
    val g12b = soloGame(points = 3)
    g12b.town.add(Party(listOf(hero("hero_barbarian"), hero("hero_rogue"))))
    steps += TutorialStep(
        "Heroes that stay in town band together into a party at the end of the round.",
        g12a, altGame = g12b
    )

    // 13 — hero abilities
    val warded = dungeon("boss_lich", listOf("room_zombies", "room_fireball", "room_succubus"))
    val g14 = newGame().also { it.players[0].dungeon = warded }
    val clericMage = Party(listOf(hero("hero_cleric"), hero("hero_mage")))
    steps += TutorialStep(
        "Each hero has an ability: a Barbarian halves the damage he takes, a Rogue reduces trap damage by 2 for the party, a Cleric reduces undead damage by 4, and a Mage reduces arcane damage by 4. These increase as heroes level up.",
        g14, crawl = CrawlView(clericMage, PartyCrawlResolver.resolve(clericMage, warded, dryRun = true))
    )

    // 15 — hero leveling
    val gLevel = gameWithDungeons()
    gLevel.town.add(Party(listOf(leveled("hero_barbarian", 4))))
    gLevel.town.add(Party(listOf(hero("hero_cleric"))))
    steps += TutorialStep(
        "Every hero starts at level 1 and increases in level every time they survive a dungeon. Every 4 rounds heroes start off a level higher, starting at level 3 on round 8 or 9. Leveling can increase hit points, or increase the effects of their abilities depending on their class.",
        gLevel
    )

    // 16 — advanced rooms
    val g16 = newGame()
    g16.players[0].dungeon = dungeon("boss_necromancer", listOf("room_sacrifice_pit", "adv_troll", "room_power_word"))
    g16.players[0].roomHand.addAll(listOf(room("room_goblins"), room("room_mimic")))
    steps += TutorialStep(
        "Advanced rooms are colored light red and can only be placed on rooms that share a bait icon. This replaces the room's previous effects and removes any upgrades the room had. Their bonuses are usually worth it.",
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
        "You start off with 2 ability cards which can be played during the crawl phase. These cards can be used to sabotage your opponent's rooms, cause parties to flee, or make your dungeon temporarily more deadly. You draw an ability card whenever a crawl phase ends without a hero or party entering your dungeon. Click the ℹ to read the ability card description.",
        g17
    )

    // 18 — wounds and winning
    steps += TutorialStep(
        "If a hero or party survives your dungeon you gain a wound. If you gain 5 wounds then you immediately lose. Each wound also counts as -2 points for the final score. The game ends when one player gains 10 points and grants that player an additional 5 points for ending the game.",
        gameWithDungeons(points = 8)
    )

    return steps
}
