package com.dungeonboss.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dungeonboss.game.AbilityEffect
import com.dungeonboss.game.BaitCounter
import com.dungeonboss.game.Decision
import com.dungeonboss.game.DecisionKind
import com.dungeonboss.game.EncounterDamage
import com.dungeonboss.game.Game
import com.dungeonboss.game.phases.BaitPhase
import com.dungeonboss.game.phases.CrawlPhase
import com.dungeonboss.game.Party
import com.dungeonboss.game.PartyCrawlResolver
import com.dungeonboss.game.Player
import com.dungeonboss.game.RoomEffect
import com.dungeonboss.game.Scoreboard
import com.dungeonboss.model.AbilityCard
import com.dungeonboss.model.Bait
import com.dungeonboss.model.BaitIcons
import com.dungeonboss.model.Boss
import com.dungeonboss.model.Encounter
import com.dungeonboss.model.Hero
import com.dungeonboss.model.PlacedRoom
import com.dungeonboss.model.Room
import com.dungeonboss.model.Upgrade
import kotlinx.coroutines.delay

/** Bump this every UI change so the on-screen tag confirms which build is running. */
const val UI_BUILD = "47 (tutorial rebuilt on the real game board components)"

/** What the human has tapped in hand while building, awaiting a dungeon slot. */
// internal (not private) so the reusable internal GameBody can name it.
internal data class Selection(val cardId: String, val isUpgrade: Boolean)

@Composable
fun GameScreen(vm: GameViewModel = viewModel()) {
    // tick changes after every engine mutation; threading it into the child
    // composables defeats Compose's skipping of unchanged Game/Player params, so
    // the summaries and bottom bar stay in sync with the live game state.
    val tick = vm.tick
    val game = vm.game
    val humanName = GameViewModel.HUMAN_PLAYER

    val viewed = remember { mutableStateOf(humanName) }
    val decision = game?.currentDecision()
    var selection by remember(decision) { mutableStateOf<Selection?>(null) }

    // A card whose full details are being shown in a popup (ℹ button), or null.
    var detailCard by remember { mutableStateOf<Any?>(null) }
    // Whether the full all-players standings dialog is open.
    var showStandings by remember { mutableStateOf(false) }
    // Whether the (non-interactive) tutorial overlay is showing.
    var showTutorial by remember { mutableStateOf(false) }

    // Pre-crawl interaction state: an ability card awaiting a room target, and a
    // boostable room awaiting a hand card to discard.
    var pendingAbility by remember { mutableStateOf<AbilityCard?>(null) }
    var pendingBoostRoom by remember { mutableStateOf<Int?>(null) }

    // Crawl animation state (indices into the current outcome's participant list).
    val activeIndex = remember { mutableStateOf<Int?>(null) }
    val heroHp = remember { mutableStateMapOf<Int, Int>() }
    val deadSet = remember { mutableStateListOf<Int>() }

    // Clear pre-crawl selections whenever a party is sent or the stage changes.
    LaunchedEffect(vm.sendCounter, game?.stage) {
        pendingAbility = null
        pendingBoostRoom = null
    }

    LaunchedEffect(vm.sendCounter) {
        if (vm.sendCounter == 0) return@LaunchedEffect
        val g = vm.game ?: return@LaunchedEffect
        val outcome = g.lastOutcomes.firstOrNull() ?: return@LaunchedEffect

        activeIndex.value = null
        heroHp.clear(); deadSet.clear()
        val participants = outcome.result.participants
        participants.forEachIndexed { i, h -> heroHp[i] = h.health }
        viewed.value = outcome.player.name
        delay(600)

        outcome.result.log.forEach { step ->
            activeIndex.value = step.roomIndex
            val hi = participants.indexOfFirst { it === step.hero }
            heroHp[hi] = step.healthAfter
            if (step.died) deadSet.add(hi)
            delay(700)
        }
        activeIndex.value = null
        delay(500)
        // Move to the next party's target dungeon (so the Send button and the
        // board agree), or back to your own when the turn is done.
        viewed.value = vm.game?.nextCrawl()?.first?.name ?: humanName
    }

    // When the crawl phase opens, show the first party's target dungeon.
    LaunchedEffect(game?.crawling()) {
        if (game?.crawling() == true) {
            game.nextCrawl()?.first?.name?.let { viewed.value = it }
        }
    }

    Box(Modifier.fillMaxSize().background(Palette.Page)) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .widthIn(max = 920.dp)
                    .background(Palette.AppBg)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val context = LocalContext.current
                TopBar(
                    tick = tick,
                    game = game,
                    human = vm.human,
                    statusText = game?.let { "Round ${it.round} · ${it.stage.name.lowercase()}" },
                    onNewGame = { count -> vm.newGame(count) },
                    onShareLog = { shareLog(context) },
                    onShowStandings = { showStandings = true },
                    onStartTutorial = { showTutorial = true }
                )

                // Diagnostics: surface action failures and where the log lives.
                vm.lastError?.let { err ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFFDE7E9))
                            .border(2.dp, Palette.Damage, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text("⚠ $err", color = Palette.Damage, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (game == null) {
                    Text(
                        "Tap New game to begin. You are Player 1; the others are computers.",
                        color = Palette.SubText, fontSize = 13.sp
                    )
                } else {
                    GameBody(
                        tick = tick,
                        game = game,
                        humanName = humanName,
                        viewed = viewed.value,
                        decision = decision,
                        selection = selection,
                        onSelect = { sel -> selection = if (selection?.cardId == sel.cardId) null else sel },
                        onDecide = { choice, target -> vm.decide(choice, target) },
                        pendingAbility = pendingAbility,
                        onPickAbility = { card ->
                            when {
                                // Tap the already-selected card again to unselect it.
                                pendingAbility?.id == card.id -> pendingAbility = null
                                AbilityEffect.forCard(card).targetsRoom() -> pendingAbility = card
                                else -> vm.playAbility(card.id) // Blueprints etc.
                            }
                        },
                        onTargetRoom = { index ->
                            pendingAbility?.let { vm.playAbility(it.id, index); pendingAbility = null }
                        },
                        pendingBoostRoom = pendingBoostRoom,
                        onChooseBoostRoom = { index -> pendingBoostRoom = index },
                        onBoostWithCard = { cardId ->
                            pendingBoostRoom?.let { vm.boostRoom(cardId, it); pendingBoostRoom = null }
                        },
                        onPlayBlueprints = { card -> vm.playAbility(card.id) },
                        onShowDetail = { card -> detailCard = card },
                        onNewGame = { vm.newGame(game.players.size) },
                        activeIndex = activeIndex.value,
                        heroHp = heroHp,
                        deadSet = deadSet
                    )
                }
            }

            if (game != null) {
                // key(tick): the bottom bar's label is derived from the current
                // decision/stage read off the long-lived Game instance; without a
                // changing key Compose skips it and it freezes on the first frame
                // (e.g. stuck on "Tap one of the two boss cards").
                key(tick) {
                    AdvanceBar(
                        tick = tick, game = game, humanName = humanName,
                        onDecide = { c, t -> vm.decide(c, t) },
                        onNextTurn = { vm.nextTurn() },
                        onSend = { vm.sendNextParty() },
                        onUndo = { vm.undoDiscard() },
                        onUndoBoss = { vm.undoBossChoice() },
                        onUndoPlacement = { vm.undoPlacement() },
                        onUndoAbility = { vm.undoAbility() },
                        onContinueQuiet = { vm.finishQuietRound() },
                        onCancelAbility = { pendingAbility = null },
                        pendingAbility = pendingAbility,
                        pendingBoostRoom = pendingBoostRoom
                    )
                }
            }
        }

        detailCard?.let { card ->
            CardDetailDialog(card) { detailCard = null }
        }
        if (showStandings && game != null) {
            StandingsDialog(
                game = game,
                onView = { name -> viewed.value = name; showStandings = false },
                onDismiss = { showStandings = false }
            )
        }
        // The tutorial is a full-screen, non-interactive overlay. Finishing or
        // exiting it returns to whatever is underneath — the Load screen when
        // launched there, where the player can start a game or replay it.
        if (showTutorial) {
            TutorialScreen(onExit = { showTutorial = false })
        }
    }
}

@Composable
private fun TopBar(
    tick: Int,
    game: Game?,
    human: Player?,
    statusText: String?,
    onNewGame: (Int) -> Unit,
    onShareLog: () -> Unit,
    onShowStandings: () -> Unit,
    onStartTutorial: () -> Unit
) {
    var players by remember { mutableStateOf(2) }
    var menuOpen by remember { mutableStateOf(false) }
    // The menu is hidden by default during a game; it is always shown when no
    // game is in progress so the player can start one. The ☰ toggle reveals it.
    val showMenu = menuOpen || game == null

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // One line: title + status on the left, the per-player totals strip taking
        // the remaining width (scrolls if it ever overflows), then the ☰ toggle.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Dungeon Boss", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                statusText?.let { Text(it, fontSize = 12.sp, color = Palette.SubText, modifier = Modifier.padding(bottom = 3.dp)) }
            }
            // The stats strip takes the flexible middle and scrolls if it would
            // overflow (e.g. 4 players), so the Share log and ☰ controls always
            // stay on-screen. It is hidden while the menu is open to leave room
            // for those controls — the menu's own rows are what matter then.
            if (game != null && human != null && !showMenu) {
                // key(tick): these read off the stable Player objects, which
                // Compose would otherwise skip.
                key(tick) {
                    Box(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        PlayerStatsStrip(game, human, onClick = onShowStandings)
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            // Share log sits in the top-right, just left of the ☰ toggle. It is
            // only shown when the menu is revealed (showMenu): always on the Load
            // screen (no game in progress) and on any screen once ☰ is pushed.
            if (showMenu) {
                OutlinedButton(onClick = onShareLog) { Text("Share log", fontSize = 13.sp, maxLines = 1) }
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (showMenu) Palette.HighlightFill else Color.White)
                    .border(1.dp, if (showMenu) Palette.Highlight else Color(0xFFCCCCCC), RoundedCornerShape(6.dp))
                    .clickable { menuOpen = !menuOpen }
                    .padding(horizontal = 7.dp, vertical = 1.dp)
            ) {
                Text("☰", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (showMenu) {
            Text("UI build $UI_BUILD", fontSize = 10.sp, color = Palette.SubText)
            // New game + Tutorial on their own line underneath the title.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { onNewGame(players) },
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent)
                ) {
                    Text("New game", color = Color.White)
                }
                OutlinedButton(onClick = onStartTutorial) { Text("Tutorial", fontSize = 13.sp) }
            }
            // Player count selector.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Players:", fontSize = 12.sp, color = Palette.SubText)
                (2..4).forEach { n ->
                    val active = players == n
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (active) Palette.Accent else Color.White)
                            .border(1.dp, if (active) Palette.Accent else Color(0xFFCCCCCC), RoundedCornerShape(6.dp))
                            .clickable { players = n }
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Text("$n", color = if (active) Color.White else Palette.SubText, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
internal fun GameBody(
    tick: Int,
    game: Game,
    humanName: String,
    viewed: String,
    decision: Decision?,
    selection: Selection?,
    onSelect: (Selection) -> Unit,
    onDecide: (String?, Any?) -> Unit,
    pendingAbility: AbilityCard?,
    onPickAbility: (AbilityCard) -> Unit,
    onTargetRoom: (Int) -> Unit,
    pendingBoostRoom: Int?,
    onChooseBoostRoom: (Int) -> Unit,
    onBoostWithCard: (String) -> Unit,
    onPlayBlueprints: (AbilityCard) -> Unit,
    onShowDetail: (Any) -> Unit,
    onNewGame: () -> Unit,
    activeIndex: Int?,
    heroHp: Map<Int, Int>,
    deadSet: List<Int>,
    // Tutorial-only highlight hooks; the live game leaves these off.
    baitHighlight: Set<Bait> = emptySet(),
    baitGlow: Float = 1f,
    highlightHeroId: String? = null
) {
    val human = game.players.first { it.name == humanName }
    val crawl = game.nextCrawl()
    // The human may play ability cards on whatever crawl is current; boost only
    // their own rooms. While targeting an ability the board shows the target
    // (crawled) dungeon.
    val preCrawl = game.crawling() && crawl != null
    val crawlOwner = crawl?.first
    val roomHandFull = human.roomHand.size >= Player.MAX_ROOM_HAND
    val holdsDrawRooms = human.abilityHand.any { AbilityEffect.forCard(it).drawRooms != null }

    if (game.over()) GameOverBanner(tick, game, onNewGame)

    val choosingFirst = decision?.kind == DecisionKind.PLACE_FIRST_ROOM && decision.player == human
    val choosingDiscard = decision?.kind == DecisionKind.DISCARD_ROOM && decision.player == human
    val choosingBuild = decision?.kind == DecisionKind.BUILD_ROOM && decision.player == human
    val boostingHere = preCrawl && crawlOwner == human && pendingBoostRoom != null

    // ---- Town (left) and your hand (right) share one line ----
    // key(tick): the chips/cards read mutable fields off Player objects whose
    // identity never changes, so Compose would otherwise skip them.
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.weight(1f)) {
            key(tick) { TownSection(game, onInspect = onShowDetail, highlightHeroId = highlightHeroId) }
        }
        Box(Modifier.weight(2f)) {
            key(tick) {
                if (human.roomHand.isEmpty() && human.abilityHand.isEmpty()) {
                    Text("No cards in hand.", color = Palette.SubText, fontSize = 12.sp)
                } else {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        // Identical cards are shown once with a ×count badge.
                        human.roomHand.groupBy { it.id }.values.forEach { group ->
                            val card = group.first()
                            val isUpgrade = card is Upgrade
                            val modifier = when {
                                boostingHere -> Modifier.clickable { onBoostWithCard(card.id) }
                                choosingDiscard || (choosingFirst && card is Room && !card.advanced) ->
                                    Modifier.clickable { onDecide(card.id, null) }
                                choosingBuild -> Modifier.clickable { onSelect(Selection(card.id, isUpgrade)) }
                                else -> Modifier
                            }
                            WithCount(group.size) {
                                HandCardView(card, modifier, highlighted = selection?.cardId == card.id,
                                    onInfo = { onShowDetail(card) })
                            }
                        }
                        human.abilityHand.groupBy { it.id }.values.forEach { group ->
                            val card = group.first()
                            val targets = AbilityEffect.forCard(card).targetsRoom()
                            // A draw-rooms ability (Blueprints) does nothing once the
                            // room hand is full, so it isn't playable then.
                            val drawsRooms = AbilityEffect.forCard(card).drawRooms != null
                            val wasted = drawsRooms && roomHandFull
                            // Playable before a crawl (any ability) or on a quiet
                            // round (only non-targeting cards); else shown for reference.
                            val playable = (preCrawl || (game.quiet() && !targets)) && !wasted
                            val mod = if (playable) {
                                Modifier.clickable { if (game.quiet()) onPlayBlueprints(card) else onPickAbility(card) }
                            } else Modifier
                            WithCount(group.size) {
                                AbilityCardView(card, mod, highlighted = pendingAbility?.id == card.id,
                                    onInfo = { onShowDetail(card) })
                            }
                        }
                    }
                }
            }
        }
    }
    // Build/crawl prompts live at the top; only the full-hand warning (Blueprints
    // can't draw into a full room hand, max 6) stays near the hand.
    if (holdsDrawRooms && roomHandFull && (preCrawl || game.quiet())) {
        Hint("Room hand is full (${Player.MAX_ROOM_HAND}) — discard or build a room before Blueprints can draw.")
    }

    // ---- Viewed dungeon ----
    // When targeting an ability, show the crawled (target) dungeon so the player
    // taps the right rooms.
    val viewedName = if (pendingAbility != null && crawlOwner != null) crawlOwner.name else viewed
    val viewedPlayer = game.players.first { it.name == viewedName }
    val outcome = game.lastOutcomes.firstOrNull()
    val isCrawledHere = outcome != null && outcome.player.name == viewedName
    val buildingHere = choosingBuild && viewedPlayer == human

    // The selected build card (if any), used to gate where it may be placed:
    //   basic room    → a new entrance slot, or replace any room
    //   upgrade       → attach to any room (no new slot)
    //   advanced room → replace only a room that shares one of its bait icons
    val selectedCard = if (buildingHere && selection != null) {
        human.roomHand.firstOrNull { it.id == selection.cardId }
    } else null
    val selectedBasicRoom = selectedCard is Room && !selectedCard.advanced

    fun canPlaceOnRoom(i: Int): Boolean {
        val card = selectedCard ?: return false
        return if (card is Room && card.advanced) {
            viewedPlayer.dungeon?.rooms?.getOrNull(i)?.bait?.shares(card.bait) == true
        } else true // basic room replaces any; upgrade attaches to any
    }

    // Decide what tapping a room/slot does for the viewed dungeon.
    val targetingAbilityHere = pendingAbility != null && crawlOwner == viewedPlayer
    val roomClick: ((Int) -> Unit)? = when {
        targetingAbilityHere -> { i -> onTargetRoom(i) }
        buildingHere && selection != null -> { i -> if (canPlaceOnRoom(i)) onDecide(selection.cardId, i) }
        else -> null
    }
    // Only a basic room may use the "add new" entrance slot.
    val newSlotClick: (() -> Unit)? =
        if (buildingHere && selection != null && selectedBasicRoom && viewedPlayer.dungeon?.isFull() == false) {
            { onDecide(selection.cardId, "new") }
        } else null
    // Boostable rooms (owner only, before any per-room boost), shown when not
    // already targeting an ability or choosing a discard card.
    val boostRooms: Set<Int> =
        if (preCrawl && crawlOwner == human && viewedPlayer == human && pendingAbility == null && pendingBoostRoom == null) {
            human.dungeon?.rooms?.indices?.filter {
                RoomEffect.forEncounter(human.dungeon!!.rooms[it]).boostable() && !game.crawlMods().boosted(it)
            }?.toSet() ?: emptySet()
        } else emptySet()

    // No board title — whose dungeon it is shows as (P1)/(P2) on the boss card.
    // The party in the pre-crawl window enters this dungeon; preview its fate.
    val incoming = if (preCrawl && crawlOwner == viewedPlayer) crawl?.second else null
    val prediction = if (incoming != null) game.predictCurrentCrawl() else null
    key(tick) {
        DungeonBoard(
            tick = tick,
            game = game,
            player = viewedPlayer,
            decision = decision,
            onChooseBoss = { bossId -> onDecide(bossId, null) },
            roomClick = roomClick,
            newSlotClick = newSlotClick,
            boostRooms = boostRooms,
            onBoost = onChooseBoostRoom,
            onShowDetail = onShowDetail,
            activeIndex = if (isCrawledHere) activeIndex else null,
            incoming = incoming,
            prediction = prediction,
            baitHighlight = baitHighlight,
            baitGlow = baitGlow
        )
    }

    if (isCrawledHere && outcome != null) {
        Spacer(Modifier.height(2.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            outcome.result.participants.forEachIndexed { i, hero ->
                HeroChip(hero.name, heroHp[i] ?: hero.health, hero.health, deadSet.contains(i))
            }
        }
    }

}

/**
 * A popup showing every resolved crawl room-by-room: for each party, the damage
 * each encounter dealt to each hero and the resulting HP, then who scored.
 * Mirrors the web app's "Crawl breakdown" section.
 */
@Composable
private fun CrawlBreakdownDialog(outcomes: List<CrawlPhase.Outcome>, onDismiss: () -> Unit) {
    // Bound to the window so the table scrolls rather than clipping in landscape.
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(12.dp), color = Color.White) {
            Column(
                Modifier
                    .widthIn(min = 280.dp, max = 360.dp)
                    .heightIn(max = maxH)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Crawl breakdown", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    outcomes.forEach { CrawlBreakdownBlock(it) }
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent)
                ) {
                    Text("Close", color = Color.White)
                }
            }
        }
    }
}

/** One party's room-by-room breakdown table within the breakdown dialog. */
@Composable
private fun CrawlBreakdownBlock(outcome: CrawlPhase.Outcome) {
    val result = outcome.result
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            "⚔ ${outcome.party.displayName()} → ${outcome.player.name}'s dungeon" +
                if (outcome.retreated) " ↩ retreated" else "",
            fontWeight = FontWeight.Bold, fontSize = 13.sp
        )
        Row(Modifier.fillMaxWidth()) {
            Text("Encounter", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Palette.TypeText, modifier = Modifier.weight(2f))
            Text("Hero", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Palette.TypeText, modifier = Modifier.weight(1.4f))
            Text("Dmg", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Palette.TypeText, modifier = Modifier.weight(0.8f))
            Text("HP", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Palette.TypeText, modifier = Modifier.weight(1.2f))
        }
        if (result.log.isEmpty()) {
            Text(
                if (outcome.retreated) "Retreated before any room." else "No damage dealt.",
                fontSize = 11.sp, color = Palette.SubText, fontStyle = FontStyle.Italic
            )
        } else {
            result.log.forEach { step ->
                val before = step.healthAfter + step.damage
                Row(Modifier.fillMaxWidth()) {
                    Text(encounterName(step.encounter), fontSize = 11.sp, modifier = Modifier.weight(2f))
                    Text(step.hero.name, fontSize = 11.sp, modifier = Modifier.weight(1.4f))
                    Text("−${step.damage}", fontSize = 11.sp, color = Palette.Damage, modifier = Modifier.weight(0.8f))
                    Text(
                        "$before → ${maxOf(step.healthAfter, 0)}" + if (step.died) " 💀" else "",
                        fontSize = 11.sp,
                        color = if (step.died) Palette.Damage else Color(0xFF444444),
                        modifier = Modifier.weight(1.2f)
                    )
                }
            }
        }
        val deaths = result.deaths
        val wounded = !outcome.retreated && result.survivors.isNotEmpty()
        val survivors = if (result.survivors.isEmpty()) "none" else result.survivors.joinToString(", ") { it.name }
        Text(
            "→ ${outcome.player.name} gains $deaths point${if (deaths == 1) "" else "s"}" +
                (if (wounded) ", 1 wound" else "") + ". Survivors: $survivors.",
            fontSize = 11.sp, color = Palette.SubText
        )
    }
}

/**
 * Town: lone heroes shown as compact two-row chips grouped by class (with a
 * count), and multi-hero parties boxed. Kept to a single row that scrolls
 * sideways when there are more than fit.
 */
@Composable
internal fun TownSection(game: Game, onInspect: (Hero) -> Unit, highlightHeroId: String? = null) {
    if (game.town.isEmpty()) {
        Text("No heroes in town.", color = Palette.SubText, fontSize = 12.sp)
        return
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Lone heroes (each its own party), grouped by class across town. A
        // representative party gives the group's lure target (all identical
        // heroes are drawn to the same dungeon).
        game.town.filter { it.lone() }.groupBy { it.heroes.first().id }.values.forEach { parties ->
            val hero = parties.first().heroes.first()
            TownHeroChip(hero, parties.size, lure = lureTarget(game, parties.first()),
                onInspect = { onInspect(hero) }, highlighted = hero.id == highlightHeroId)
        }
        // Multi-hero parties: members shown as icon ×count on the target line.
        game.town.filterNot { it.lone() }.forEach { party -> PartyBox(party, lureTarget(game, party), onInspect) }
    }
}

/**
 * Where a town party is headed this turn: [dungeon] is the boss/owner it is most
 * lured toward (by bait), with [timid] true when its combined courage is below
 * that owner's points (it won't dare enter yet). [dungeon] is null when the party
 * is unenticed — a tie or no bait pull — so it simply stays in town.
 */
private data class Lure(val dungeon: String?, val timid: Boolean)

private fun lureTarget(game: Game, party: Party): Lure {
    val player = BaitPhase.mostEnticingPlayer(game, party) ?: return Lure(null, false)
    val name = player.dungeon?.boss?.name ?: player.name
    return Lure(name, party.courage() < player.points)
}

/**
 * The destination line under a town party. A clear lure shows 🎯 (red 😨 timid
 * when too scared to enter yet); an unenticed party shows it stays in town. A
 * null [lure] (an inner party-member chip) shows no line at all.
 */
@Composable
private fun TargetLine(lure: Lure?, normalColor: Color) {
    if (lure == null) return
    if (lure.dungeon == null) {
        Text("🏠 stays in town", fontSize = 9.sp, color = Palette.SubText, maxLines = 1)
        return
    }
    Text(
        "🎯 ${lure.dungeon}" + if (lure.timid) " 😨 timid" else "",
        fontSize = 9.sp,
        color = if (lure.timid) Palette.Damage else normalColor,
        maxLines = 1
    )
}

@Composable
private fun PartyBox(party: Party, lure: Lure, onInspect: (Hero) -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.PartyBg)
            .border(2.dp, Palette.PartyBorder, RoundedCornerShape(10.dp))
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text("${party.displayName()} · 🦁 ${party.courage()}", fontSize = 11.sp,
            fontWeight = FontWeight.Bold, color = Palette.PartyHead)
        // Members as icon ×count, on the same line as the lure target. Tap an
        // icon to inspect that hero.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TargetLine(lure, Palette.PartyHead)
            party.heroes.groupBy { it.id }.values.forEach { group ->
                val hero = group.first()
                Text(
                    CardArt.heroArt(hero.id) + if (group.size > 1) "×${group.size}" else "",
                    fontSize = 14.sp,
                    modifier = Modifier.clickable { onInspect(hero) }
                )
            }
        }
    }
}

/**
 * A compact lone-hero chip: art + name (×count) and the 🎯 line showing where it
 * is lured. Stats are hidden — tap the chip to inspect the hero.
 */
@Composable
private fun TownHeroChip(
    hero: Hero,
    count: Int,
    lure: Lure? = null,
    onInspect: (() -> Unit)? = null,
    highlighted: Boolean = false
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (highlighted) Palette.HighlightFill else Palette.HeroBg)
            .border(
                if (highlighted) 3.dp else 1.dp,
                if (highlighted) Palette.Highlight else Palette.HeroBorder,
                RoundedCornerShape(8.dp)
            )
            .then(if (onInspect != null) Modifier.clickable { onInspect() } else Modifier)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(CardArt.heroArt(hero.id), fontSize = 16.sp)
            Text(hero.name + if (count > 1) " ×$count" else "", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        TargetLine(lure, Palette.SubText)
    }
}

/** Overlays a "×N" badge (top-left) when a hand holds more than one of a card. */
@Composable
private fun WithCount(count: Int, content: @Composable () -> Unit) {
    if (count <= 1) {
        content()
        return
    }
    Box {
        content()
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(3.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Palette.Accent)
                .padding(horizontal = 5.dp, vertical = 1.dp)
        ) {
            Text("×$count", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** A player's effective dungeon damage (rooms + boss), or 0 if no dungeon yet. */
internal fun playerDamage(game: Game, player: Player): Int =
    player.dungeon?.let { EncounterDamage.dungeonTotal(it, applicablePoints(game, player)) } ?: 0

/** A player's total icons of one bait type across their dungeon, or 0. */
internal fun baitTotal(player: Player, bait: Bait): Int =
    player.dungeon?.let { BaitCounter.enticement(it, bait) } ?: 0

/**
 * "<icon> you/p2/p3/p4" — every player's [value] in seating order, you first.
 * Coloured by how you compare with the living opponents: green when you alone
 * lead, grey on a tie, red when an opponent is ahead. For lower-is-better stats
 * (wounds) pass [higherBetter] = false so fewer counts as leading.
 */
/**
 * The per-player totals strip (bait icons, then ⚔ damage, 🪙 points, 🩸 wounds),
 * each shown as you/opponent… in seating order. Used in the Top bar and reused
 * unchanged by the tutorial so both read identically. [onClick] opens standings.
 */
@Composable
internal fun PlayerStatsStrip(game: Game, human: Player, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFFCCCCCC), RoundedCornerShape(8.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Bait.entries.forEach { b ->
            PlayerStat(game, human, CardArt.baitEmoji[b].orEmpty()) { baitTotal(it, b) }
        }
        PlayerStat(game, human, "⚔") { playerDamage(game, it) }
        PlayerStat(game, human, "🪙") { it.points }
        PlayerStat(game, human, "🩸", higherBetter = false) { it.wounds }
    }
}

@Composable
private fun PlayerStat(
    game: Game,
    human: Player,
    icon: String,
    higherBetter: Boolean = true,
    value: (Player) -> Int
) {
    val ordered = listOf(human) + game.players.filter { it !== human }
    val text = ordered.joinToString("/") { value(it).toString() }
    val mine = value(human)
    val opponents = game.players.filter { it !== human && !Scoreboard.eliminated(it) }
    val color = if (opponents.isEmpty()) {
        Palette.SubText
    } else {
        val best = if (higherBetter) opponents.maxOf(value) else opponents.minOf(value)
        when {
            if (higherBetter) mine > best else mine < best -> Color(0xFF2E7D32) // you lead
            mine == best -> Palette.SubText                                      // tie
            else -> Palette.Damage                                              // an opponent leads
        }
    }
    Text("$icon $text", fontSize = 12.sp, color = color, fontWeight = FontWeight.Bold)
}

/** The full all-players standings (best first); tap a player to view its dungeon. */
@Composable
private fun StandingsDialog(game: Game, onView: (String) -> Unit, onDismiss: () -> Unit) {
    // Bound the dialog to most of the screen so the rows scroll instead of being
    // clipped — in landscape four players are taller than the (short) window.
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(12.dp), color = Color.White) {
            Column(
                Modifier.widthIn(min = 260.dp, max = 340.dp).heightIn(max = maxH).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Standings", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                game.standings().forEach { s ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (s.eliminated) Color(0xFFF1F1F1) else Palette.HighlightFill)
                            .clickable { onView(s.player.name); onDismiss() }
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.player.dungeon?.boss?.name ?: s.player.name,
                                fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(
                                (if (game.automated(s.player)) "computer" else "you") +
                                    (if (s.eliminated) " · eliminated" else ""),
                                fontSize = 11.sp, color = Palette.SubText
                            )
                            // Bait totals for this player's dungeon.
                            Text(
                                Bait.entries.joinToString("  ") { "${CardArt.baitEmoji[it]}${baitTotal(s.player, it)}" },
                                fontSize = 12.sp
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("🪙 ${s.player.points} · 🩸 ${s.player.wounds}", fontSize = 12.sp)
                            Text("score ${s.score} · ⚔ ${playerDamage(game, s.player)}",
                                fontSize = 11.sp, color = Palette.SubText)
                        }
                    }
                }
                }
                Text("Tap a player to view their dungeon.", fontSize = 11.sp, color = Palette.SubText)
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent)
                ) {
                    Text("Close", color = Color.White)
                }
            }
        }
    }
}

@Composable
internal fun DungeonBoard(
    @Suppress("UNUSED_PARAMETER") tick: Int,
    game: Game,
    player: Player,
    decision: Decision?,
    onChooseBoss: (String) -> Unit,
    roomClick: ((Int) -> Unit)?,
    newSlotClick: (() -> Unit)?,
    boostRooms: Set<Int>,
    onBoost: (Int) -> Unit,
    onShowDetail: (Any) -> Unit,
    activeIndex: Int?,
    incoming: Party? = null,
    prediction: PartyCrawlResolver.Result? = null,
    baitHighlight: Set<Bait> = emptySet(),
    baitGlow: Float = 1f
) {
    val dungeon = player.dungeon
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Heroes enter from the left, so the party about to crawl sits to the left
        // of the entrance, fixed; the dungeon scrolls within the remaining width so
        // the boss is always reachable.
        if (dungeon != null && incoming != null) IncomingParty(incoming)
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            if (dungeon == null) {
                val choosingBoss = decision?.kind == DecisionKind.CHOOSE_BOSS && decision.player == player
                if (choosingBoss) {
                    // Tap a boss card to choose it (instruction shown on the button).
                    decision!!.options.filterIsInstance<Boss>().forEach { boss ->
                        BossCardView(boss, Modifier.clickable { onChooseBoss(boss.id) },
                            onInfo = { onShowDetail(boss) })
                    }
                } else {
                    Text("No boss chosen yet.", color = Palette.SubText, fontSize = 12.sp)
                }
            } else {
                // The per-point bonus that applies to this dungeon's damage display.
                val points = applicablePoints(game, player)
                // Per-crawl ability/boost modifiers apply only to the dungeon whose
                // party is in the pre-crawl window — fold them into its damage display.
                val mods = if (game.nextCrawl()?.first === player) game.crawlMods() else null
                if (newSlotClick != null) {
                    NewRoomSlot(Modifier.clickable { newSlotClick() })
                }
                dungeon.rooms.forEachIndexed { i, placed ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val mod = if (roomClick != null) Modifier.clickable { roomClick(i) } else Modifier
                        RoomCardView(placed, mod, highlighted = activeIndex == i,
                            parts = EncounterDamage.parts(dungeon, placed, points, mods, i),
                            onInfo = { onShowDetail(placed) },
                            baitHighlight = baitHighlight, baitGlow = baitGlow)
                        if (boostRooms.contains(i)) {
                            OutlinedButton(onClick = { onBoost(i) }) {
                                Text("Boost", fontSize = 11.sp)
                            }
                        }
                        DeathMarkers(prediction, i)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    // Label only opponents (P2/P3/…); your own boss needs no marker.
                    val ownerLabel = if (game.automated(player)) "P${game.players.indexOf(player) + 1}" else null
                    BossCardView(dungeon.boss, Modifier, highlighted = activeIndex == dungeon.rooms.size,
                        parts = EncounterDamage.parts(dungeon, dungeon.boss, points, mods, dungeon.rooms.size),
                        ownerLabel = ownerLabel,
                        onInfo = { onShowDetail(dungeon.boss) },
                        baitHighlight = baitHighlight, baitGlow = baitGlow)
                    DeathMarkers(prediction, dungeon.rooms.size)
                    SurvivorMarkers(prediction)
                }
            }
        }
    }
}

/**
 * The party about to crawl, shown to the left of the dungeon (heroes enter from
 * the left). No stats — just each hero class with ×count. Where each dies is
 * shown by a red marker in the room itself (see [DeathMarkers]).
 */
@Composable
private fun IncomingParty(party: Party) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = Alignment.Start) {
        Text("entering →", fontSize = 9.sp, color = Palette.SubText, fontWeight = FontWeight.Bold)
        party.heroes.groupBy { it.id }.values.forEach { group ->
            val hero = group.first()
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Palette.HeroBg)
                    .border(1.dp, Palette.HeroBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(CardArt.heroArt(hero.id), fontSize = 15.sp)
                Text(hero.name + if (group.size > 1) " ×${group.size}" else "",
                    fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }
    }
}

/** A red 💀×N marker under an encounter showing how many heroes die there. */
@Composable
private fun DeathMarkers(prediction: PartyCrawlResolver.Result?, encounterIndex: Int) {
    val count = prediction?.log?.count { it.died && it.roomIndex == encounterIndex } ?: 0
    if (count == 0) return
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Palette.DyingBg)
            .border(1.dp, Palette.Damage, RoundedCornerShape(6.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text("💀" + if (count > 1) " ×$count" else "", fontSize = 12.sp, color = Palette.Damage)
    }
}

/** Green markers under the boss for the heroes who survive the whole crawl. */
@Composable
private fun SurvivorMarkers(prediction: PartyCrawlResolver.Result?) {
    val survivors = prediction?.survivors ?: emptyList()
    if (survivors.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        survivors.groupBy { it.id }.values.forEach { group ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Palette.PartyBg)
                    .border(1.dp, Palette.PartyBorder, RoundedCornerShape(6.dp))
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            ) {
                Text("✓${CardArt.heroArt(group.first().id)}" + if (group.size > 1) "×${group.size}" else "",
                    fontSize = 12.sp, color = Palette.PartyHead)
            }
        }
    }
}

@Composable
private fun GameOverBanner(@Suppress("UNUSED_PARAMETER") tick: Int, game: Game, onNewGame: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.GameOverBg)
            .border(2.dp, Palette.GameOverBorder, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("🏆 ${game.winner?.name} wins!", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        game.standings().forEach { s ->
            val endedHere = s.player === game.endedBy
            val line = if (s.eliminated) {
                "${s.player.name}: eliminated (5 wounds)"
            } else {
                val bonus = if (endedHere) " + 5 end-game bonus" else ""
                "${s.player.name}: score ${s.score} (${s.player.points} pts − ${s.player.wounds} wounds$bonus)"
            }
            Text(line, fontSize = 12.sp, color = Palette.SubText)
        }
        Button(
            onClick = onNewGame,
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent)
        ) {
            Text("New game", color = Color.White)
        }
    }
}

@Composable
private fun AdvanceBar(
    @Suppress("UNUSED_PARAMETER") tick: Int,
    game: Game,
    humanName: String,
    onDecide: (String?, Any?) -> Unit,
    onNextTurn: () -> Unit,
    onSend: () -> Unit,
    onUndo: () -> Unit,
    onUndoBoss: () -> Unit,
    onUndoPlacement: () -> Unit,
    onUndoAbility: () -> Unit,
    onContinueQuiet: () -> Unit,
    onCancelAbility: () -> Unit,
    pendingAbility: AbilityCard?,
    pendingBoostRoom: Int?
) {
    val decision = game.currentDecision()
    val human = game.players.first { it.name == humanName }
    val mineKind = decision?.takeIf { it.player == human }?.kind
    val setupDone = game.ready() && game.round == 0

    // The bottom-right button shows the next action. When the action is "tap a
    // card/room" rather than a press, the button shows that prompt but is greyed
    // out (disabled). Secondary undo/cancel buttons sit to its left.
    val noop: () -> Unit = {}
    val (label, enabled, action) = when {
        pendingAbility != null -> Triple("Tap a room to target ${pendingAbility.name}", false, noop)
        pendingBoostRoom != null -> Triple("Tap a hand card to boost the room", false, noop)
        mineKind == DecisionKind.CHOOSE_BOSS -> Triple("Tap a boss to choose it", false, noop)
        mineKind == DecisionKind.PLACE_FIRST_ROOM -> Triple("Tap a room to place by your boss", false, noop)
        mineKind == DecisionKind.DISCARD_ROOM -> Triple("Tap a room to discard", false, noop)
        mineKind == DecisionKind.BUILD_ROOM -> Triple("Build nothing", true, { onDecide(null, null) })
        game.quiet() -> Triple("Continue ▶", true, onContinueQuiet)
        game.crawling() && game.nextCrawl() != null -> Triple("Send ▶", true, onSend)
        game.over() -> Triple("", false, noop)
        setupDone -> Triple("Start ▶", true, onNextTurn)
        game.ready() -> Triple("Next turn ▶", true, onNextTurn)
        else -> Triple("", false, noop)
    }

    Surface(shadowElevation = 8.dp, color = Color.White) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), contentAlignment = Alignment.CenterEnd) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (game.lastOutcomes.isNotEmpty()) {
                    // A new crawl resets the dialog so it never lingers on stale outcomes.
                    var showBreakdown by remember(game.lastOutcomes) { mutableStateOf(false) }
                    OutlinedButton(onClick = { showBreakdown = true }) { Text("Breakdown", fontSize = 13.sp) }
                    if (showBreakdown) CrawlBreakdownDialog(game.lastOutcomes) { showBreakdown = false }
                }
                if (mineKind == DecisionKind.BUILD_ROOM && game.canUndoDiscard()) {
                    OutlinedButton(onClick = onUndo) { Text("Undo", fontSize = 13.sp) }
                }
                if (game.canUndoBossChoice()) {
                    OutlinedButton(onClick = onUndoBoss) { Text("↶ Undo boss", fontSize = 13.sp) }
                }
                if (pendingAbility != null) {
                    OutlinedButton(onClick = onCancelAbility) { Text("Cancel", fontSize = 13.sp) }
                }
                if (game.crawling() && game.canUndoPlacement()) {
                    OutlinedButton(onClick = onUndoPlacement) { Text("↶ Undo room", fontSize = 13.sp) }
                }
                if (game.canUndoAbility()) {
                    OutlinedButton(onClick = onUndoAbility) { Text("↶ Undo ability", fontSize = 13.sp) }
                }
                if (label.isNotEmpty()) {
                    Button(
                        onClick = action,
                        enabled = enabled,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Palette.Accent,
                            disabledContainerColor = Color(0xFFE6E6E6),
                            disabledContentColor = Palette.SubText
                        )
                    ) {
                        Text(label, color = if (enabled) Color.White else Palette.SubText, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Palette.SubText, fontSize = 12.sp)
}

/** Hand the debug log file to another app (email, Drive, messaging…) to upload. */
private fun shareLog(context: Context) {
    val file = DebugLog.file()
    if (file == null || !file.exists()) {
        Toast.makeText(context, "No log file yet — tap New game first.", Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share Dungeon Boss log"))
    }.onFailure { t ->
        DebugLog.error("share failed", t)
        Toast.makeText(context, "Couldn't share. Log is at: ${file.absolutePath}", Toast.LENGTH_LONG).show()
    }
}

/** A popup showing a card's full details (opened from the ℹ button on a card). */
@Composable
private fun CardDetailDialog(card: Any, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(12.dp), color = Color.White) {
            Column(
                Modifier.widthIn(min = 240.dp, max = 320.dp).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (card) {
                    is PlacedRoom -> {
                        DetailHeader(CardArt.roomArt(card.type), card.name,
                            if (card.baseRoom.advanced) "Advanced · ${card.type}" else card.type)
                        DetailStat("Damage", card.damage.toString())
                        card.upgrade?.let { DetailStat("Upgrade", it.name) }
                        if (card.grow > 0) DetailStat("Grown", "+${card.grow}")
                        DetailBait(card.bait)
                        DetailBody(card.description)
                    }
                    is Boss -> {
                        DetailHeader(CardArt.bossArt(card.id), card.name, "Boss")
                        DetailStat("Damage", card.damage.toString())
                        DetailBait(card.bait)
                        DetailBody(card.abilityText)
                    }
                    is Room -> {
                        DetailHeader(CardArt.roomArt(card.type), card.name,
                            if (card.advanced) "Advanced · ${card.type}" else card.type)
                        DetailStat("Damage", card.damage.toString())
                        DetailBait(card.bait)
                        DetailBody(card.description)
                    }
                    is Upgrade -> {
                        DetailHeader("⬆️", card.name, "Upgrade")
                        if (card.bonusDamage > 0) DetailStat("Bonus damage", "+${card.bonusDamage}")
                        DetailBait(card.bait)
                        DetailBody(card.description)
                    }
                    is AbilityCard -> {
                        DetailHeader("✨", card.name, "Ability")
                        DetailBody(card.text)
                    }
                    is Hero -> {
                        DetailHeader(CardArt.heroArt(card.id), card.name, "Hero")
                        DetailStat("Health", card.health.toString())
                        DetailStat("Courage", card.courage.toString())
                        DetailStat("Preferred bait", CardArt.baitEmoji[card.preferredBait].orEmpty())
                        DetailBody(card.abilityText)
                    }
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent)
                ) {
                    Text("Close", color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun DetailHeader(glyph: String, name: String, type: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(glyph, fontSize = 26.sp)
        Column {
            Text(name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(type.uppercase(), color = Palette.TypeText, fontSize = 10.sp, letterSpacing = 0.4.sp)
        }
    }
}

@Composable
private fun DetailStat(label: String, value: String) {
    Text("$label: $value", fontSize = 13.sp)
}

@Composable
private fun DetailBait(bait: BaitIcons) {
    if (bait.toMap().isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Bait:", fontSize = 13.sp)
            BaitPips(bait)
        }
    }
}

@Composable
private fun DetailBody(text: String) {
    if (text.isNotEmpty()) Text(text, fontSize = 13.sp, color = Palette.SubText)
}

/**
 * The per-point bonus that applies to a dungeon's damage display: the points
 * that will (or did) apply to the crawl in context. A crawl queued against this
 * dungeon uses its current points; a just-resolved crawl uses the snapshot that
 * applied; otherwise current points. Mirrors the web app's `applicable_points`.
 */
private fun applicablePoints(game: Game, player: Player): Int {
    val pending = game.nextCrawl()
    if (pending != null && pending.first === player) return player.points
    val finished = game.lastOutcomes.firstOrNull { it.player === player }
    return finished?.bossBonus ?: player.points
}

private fun encounterName(encounter: Encounter): String = when (encounter) {
    is PlacedRoom -> encounter.name
    is Boss -> encounter.name
    else -> "?"
}
