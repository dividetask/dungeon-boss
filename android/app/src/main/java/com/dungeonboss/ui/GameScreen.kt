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
import com.dungeonboss.game.phases.DiscardPhase
import com.dungeonboss.game.phases.EnticePhase
import com.dungeonboss.game.phases.GauntletPhase
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
import kotlinx.coroutines.delay

/** Bump this every UI change so the on-screen tag confirms which build is running. */
const val UI_BUILD = "49 (tutorial: Draw phase wraps discard; boss info hint)"

/** What the human has tapped in hand while building, awaiting a dungeon slot. */
internal data class Selection(val cardId: String)

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
    // Room ids the human has tapped to discard this Discard phase, as a multiset
    // (an id repeats once per copy marked), so multiple copies of a card can be
    // discarded. Total size is capped at DiscardPhase.MAX_DISCARDS.
    var discardSelection by remember(decision) { mutableStateOf<List<String>>(emptyList()) }

    // A card whose full details are being shown in a popup (ℹ button), or null.
    var detailCard by remember { mutableStateOf<Any?>(null) }
    // Whether the full all-players standings dialog is open.
    var showStandings by remember { mutableStateOf(false) }
    // When non-null, the player whose dungeon the user is peeking at (picked from
    // Standings). It overrides the auto-viewed dungeon and swaps the bottom bar
    // for a single Return button until the user returns to the live view.
    var viewingOther by remember { mutableStateOf<String?>(null) }
    // True right after the crawl phase opens, before the player presses Continue:
    // the board stays on your just-built dungeon so it doesn't jump straight to
    // another player's dungeon.
    var awaitingCrawlStart by remember { mutableStateOf(false) }
    // True while a resolved crawl is animating. The engine finishes the turn
    // synchronously on the last Send (stage leaves CRAWLING), so this keeps the
    // crawl-progress row on screen until the animation actually ends.
    var animating by remember { mutableStateOf(false) }
    // Number of players for the next new game (the New game button lives at the
    // bottom-right; the ☰ menu keeps the count selector).
    var playerCount by remember { mutableStateOf(2) }
    // Whether the non-interactive tutorial overlay is showing.
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

        animating = true
        activeIndex.value = null
        heroHp.clear(); deadSet.clear()
        val participants = outcome.result.participants
        participants.forEachIndexed { i, h -> heroHp[i] = h.maxHp }
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
        animating = false
        // Move to the next party's target dungeon (so the Send button and the
        // board agree), or back to your own when the turn is done.
        viewed.value = vm.game?.nextCrawl()?.first?.name ?: humanName
    }

    // When the crawl phase opens, pause on your own (just-built) dungeon and wait
    // for Continue rather than jumping straight to the first crawl target.
    LaunchedEffect(game?.crawling()) {
        if (game?.crawling() == true) {
            awaitingCrawlStart = true
            viewed.value = humanName
        } else {
            awaitingCrawlStart = false
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
                    playerCount = playerCount,
                    onPlayerCount = { playerCount = it },
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
                    val hasSave = vm.hasSavedGame()
                    if (hasSave) {
                        Button(
                            onClick = { vm.restoreIfSaved() },
                            colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent)
                        ) {
                            Text("Resume game", color = Color.White)
                        }
                    }
                    Button(
                        onClick = { vm.newGame(playerCount) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (hasSave) Palette.SubText else Palette.Accent
                        )
                    ) {
                        Text("New game", color = Color.White)
                    }
                    Text(
                        "You are Player 1; the others are computers. Set the player count in the ☰ menu.",
                        color = Palette.SubText, fontSize = 13.sp
                    )
                } else {
                    GameBody(
                        tick = tick,
                        game = game,
                        humanName = humanName,
                        // A peeked dungeon (from Standings) overrides the auto-viewed
                        // one; guard against a stale name after a new game.
                        viewed = viewingOther?.takeIf { n -> game.players.any { it.name == n } } ?: viewed.value,
                        decision = decision,
                        selection = selection,
                        onSelect = { sel -> selection = if (selection?.cardId == sel.cardId) null else sel },
                        onDecide = { choice, target -> vm.decide(choice, target) },
                        discardSelection = discardSelection,
                        onToggleDiscard = { id ->
                            // Tapping a card cycles how many copies of it are marked to
                            // discard: 0 → 1 → 2 … up to the copies held (and the shared
                            // MAX_DISCARDS budget), then back to 0 ("discard none").
                            val hand = game?.players?.firstOrNull { it.name == humanName }?.roomHand ?: emptyList()
                            val copiesInHand = hand.count { it.id == id }
                            val currentForId = discardSelection.count { it == id }
                            val otherSelected = discardSelection.size - currentForId
                            val cap = minOf(copiesInHand, DiscardPhase.MAX_DISCARDS - otherSelected)
                            val next = if (currentForId >= cap) 0 else currentForId + 1
                            discardSelection = discardSelection.filter { it != id } + List(next) { id }
                        },
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
                        activeIndex = activeIndex.value,
                        heroHp = heroHp,
                        deadSet = deadSet,
                        animating = animating
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
                        viewingOther = viewingOther != null,
                        onReturn = { viewingOther = null },
                        awaitingCrawlStart = awaitingCrawlStart,
                        onBeginCrawl = {
                            awaitingCrawlStart = false
                            game.nextCrawl()?.first?.name?.let { viewed.value = it }
                        },
                        onDecide = { c, t -> vm.decide(c, t) },
                        onNextTurn = { vm.nextTurn() },
                        onSend = { vm.sendNextParty() },
                        onNewGame = { vm.newGame(playerCount) },
                        onUndo = { vm.undoDiscard() },
                        onUndoBoss = { vm.undoBossChoice() },
                        onUndoPlacement = { vm.undoPlacement() },
                        onUndoAbility = { vm.undoAbility() },
                        onContinueQuiet = { vm.finishQuietRound() },
                        onCancelAbility = { pendingAbility = null },
                        pendingAbility = pendingAbility,
                        pendingBoostRoom = pendingBoostRoom,
                        discardSelection = discardSelection
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
                onView = { name -> viewingOther = name; showStandings = false },
                onDismiss = { showStandings = false }
            )
        }
        // The tutorial is a full-screen overlay drawn above the game.
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
    playerCount: Int,
    onPlayerCount: (Int) -> Unit,
    onShareLog: () -> Unit,
    onShowStandings: () -> Unit,
    onStartTutorial: () -> Unit
) {
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
            // Push the per-player stats strip to the right, against the ☰ toggle.
            Spacer(Modifier.weight(1f))
            if (game != null && human != null) {
                // key(tick): these read off the stable Player objects, which
                // Compose would otherwise skip.
                key(tick) {
                    PlayerStatsStrip(game, human, onClick = onShowStandings)
                }
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
            // Tutorial + Share log (New game lives at the bottom-right).
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onStartTutorial) { Text("Tutorial", fontSize = 13.sp) }
                OutlinedButton(onClick = onShareLog) { Text("Share log", fontSize = 13.sp) }
            }
            // Player count selector (applies to the next New game).
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Players:", fontSize = 12.sp, color = Palette.SubText)
                (2..4).forEach { n ->
                    val active = playerCount == n
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (active) Palette.Accent else Color.White)
                            .border(1.dp, if (active) Palette.Accent else Color(0xFFCCCCCC), RoundedCornerShape(6.dp))
                            .clickable { onPlayerCount(n) }
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
    discardSelection: List<String>,
    onToggleDiscard: (String) -> Unit,
    pendingAbility: AbilityCard?,
    onPickAbility: (AbilityCard) -> Unit,
    onTargetRoom: (Int) -> Unit,
    pendingBoostRoom: Int?,
    onChooseBoostRoom: (Int) -> Unit,
    onBoostWithCard: (String) -> Unit,
    onPlayBlueprints: (AbilityCard) -> Unit,
    onShowDetail: (Any) -> Unit,
    activeIndex: Int?,
    heroHp: Map<Int, Int>,
    deadSet: List<Int>,
    animating: Boolean = false,
    // Tutorial-only highlight hooks; the live game leaves these off.
    baitHighlight: Set<Bait> = emptySet(),
    baitGlow: Float = 1f,
    highlightHeroId: String? = null,
    timidGlow: Float = 1f
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

    if (game.over()) GameOverBanner(tick, game)

    val choosingFirst = decision?.kind == DecisionKind.PLACE_FIRST_ROOM && decision.player == human
    val choosingDiscard = decision?.kind == DecisionKind.DISCARD_ROOMS && decision.player == human
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
            key(tick) { TownSection(game, onInspect = onShowDetail, highlightHeroId = highlightHeroId, timidGlow = timidGlow) }
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
                            // First room and Build both pick a card, then tap a slot.
                            val selectableForBuild = choosingBuild || (choosingFirst && card is Room && !card.advanced)
                            val modifier = when {
                                boostingHere -> Modifier.clickable { onBoostWithCard(card.id) }
                                choosingDiscard -> Modifier.clickable { onToggleDiscard(card.id) }
                                selectableForBuild -> Modifier.clickable { onSelect(Selection(card.id)) }
                                else -> Modifier
                            }
                            val discardCount = if (choosingDiscard) discardSelection.count { it == card.id } else 0
                            val highlighted = selection?.cardId == card.id || discardCount > 0
                            WithCount(group.size) {
                                Box {
                                    HandCardView(card, modifier, highlighted = highlighted,
                                        onInfo = { onShowDetail(card) })
                                    if (discardCount > 0) {
                                        Box(
                                            Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(3.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Palette.Damage)
                                                .padding(horizontal = 5.dp, vertical = 1.dp)
                                        ) {
                                            Text("🗑×$discardCount", color = Color.White,
                                                fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
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
    // First-room placement and Build both place into a slot on your own dungeon.
    val placingHere = (choosingBuild || choosingFirst) && viewedPlayer == human

    // The selected card (if any), used to gate which slot it may go in:
    //   basic room    → any slot (empty fills, occupied replaces)
    //   advanced room → an empty slot, or replace a room sharing a bait icon
    val selectedCard = if (placingHere && selection != null) {
        human.roomHand.firstOrNull { it.id == selection.cardId }
    } else null
    val selectedRoom = selectedCard as? Room

    // Whether the selected card may be PLACED into slot [slot] (empty or occupied).
    fun canPlaceInSlot(slot: Int): Boolean {
        val card = selectedCard ?: return false
        val occupant = viewedPlayer.dungeon?.slots?.getOrNull(slot)
        return when {
            card is Room && card.advanced ->
                occupant == null || occupant.bait.shares(card.bait)   // empty, or bait-share replace
            card is Room -> true                                       // basic: any slot
            else -> false
        }
    }

    // Tapping a slot during build/first places (or replaces / attaches) there.
    val slotPlace: ((Int) -> Unit)? =
        if (placingHere && selection != null) {
            { slot -> if (canPlaceInSlot(slot)) onDecide(selection.cardId, slot) }
        } else null
    // A room card (not an upgrade card) may instead be SPENT to upgrade an
    // occupied room: grants its bait + a room level ("upgrade:<slot>").
    val slotUpgrade: ((Int) -> Unit)? =
        if (placingHere && selectedRoom != null) {
            { slot -> onDecide(selection!!.cardId, "upgrade:$slot") }
        } else null
    // Ability targeting taps an ENCOUNTER (occupied-room) index, not a slot.
    val targetingAbilityHere = pendingAbility != null && crawlOwner == viewedPlayer
    val encounterClick: ((Int) -> Unit)? = if (targetingAbilityHere) { i -> onTargetRoom(i) } else null

    // Boostable rooms (owner only, before any per-room boost) — encounter indices.
    val boostRooms: Set<Int> =
        if (preCrawl && crawlOwner == human && viewedPlayer == human && pendingAbility == null && pendingBoostRoom == null) {
            human.dungeon?.rooms?.indices?.filter {
                RoomEffect.boostable(human.dungeon!!.rooms[it])
            }?.toSet() ?: emptySet()
        } else emptySet()

    // No board title — whose dungeon it is shows as (P1)/(P2) on the boss card.
    // The party in the pre-crawl window enters this dungeon; preview its fate.
    val incoming = if (preCrawl && crawlOwner == viewedPlayer) crawl?.second else null
    val prediction = if (incoming != null) game.predictCurrentCrawl() else null
    // Crawl-progress row (between the hand row and the dungeon): each party this
    // turn as a compact coloured box — grey = done, green = about to crawl,
    // blue = still waiting. Empty outside the Crawl phase.
    key(tick) { CrawlPartyRow(game, animating, onShowDetail) }
    key(tick) {
        DungeonBoard(
            tick = tick,
            game = game,
            player = viewedPlayer,
            decision = decision,
            onChooseBoss = { bossId -> onDecide(bossId, null) },
            slotPlace = slotPlace,
            canPlaceInSlot = if (placingHere && selection != null) ::canPlaceInSlot else null,
            slotUpgrade = slotUpgrade,
            encounterClick = encounterClick,
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
        // The just-crawled party's hero HP bars, directly beneath the dungeon
        // (the per-party summary lives in the crawl-progress row above the board).
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            outcome.result.participants.forEachIndexed { i, hero ->
                val dead = deadSet.contains(i)
                HeroChip(hero.name, heroHp[i] ?: hero.maxHp, hero.maxHp, dead, fled = outcome.retreated && !dead)
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
private fun CrawlBreakdownDialog(outcomes: List<GauntletPhase.Outcome>, onDismiss: () -> Unit) {
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
private fun CrawlBreakdownBlock(outcome: GauntletPhase.Outcome) {
    val result = outcome.result
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "⚔ ${outcome.party.displayName()} → ${outcome.player.name}'s dungeon",
                fontWeight = FontWeight.Bold, fontSize = 13.sp
            )
            if (outcome.retreated) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Palette.FledBg)
                        .border(1.dp, Palette.FledBorder, RoundedCornerShape(6.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text("↩ FLED", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Palette.FledText)
                }
            }
        }
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
        val leaverLabel = if (outcome.retreated) "Fled" else "Survivors"
        val leavers = if (result.survivors.isEmpty()) "none" else result.survivors.joinToString(", ") { it.name }
        Text(
            "→ ${outcome.player.name} gains $deaths point${if (deaths == 1) "" else "s"}" +
                (if (wounded) ", 1 wound" else if (outcome.retreated) ", no wound" else "") +
                ". $leaverLabel: $leavers.",
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
private fun TownSection(
    game: Game,
    onInspect: (Hero) -> Unit,
    highlightHeroId: String? = null,
    timidGlow: Float = 1f
) {
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
                onInspect = { onInspect(hero) }, highlighted = hero.id == highlightHeroId, timidGlow = timidGlow)
        }
        // Multi-hero parties: members shown as icon ×count on the target line.
        game.town.filterNot { it.lone() }.forEach { party -> PartyBox(party, lureTarget(game, party), onInspect, timidGlow) }
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
    val player = EnticePhase.mostEnticingPlayer(game, party) ?: return Lure(null, false)
    val name = player.dungeon?.boss?.name ?: player.name
    return Lure(name, party.courage() < player.points)
}

/**
 * The destination line under a town party. A clear lure shows 🎯 (red 😨 timid
 * when too scared to enter yet); an unenticed party shows it stays in town. A
 * null [lure] (an inner party-member chip) shows no line at all.
 */
@Composable
private fun TargetLine(lure: Lure?, normalColor: Color, timidGlow: Float = 1f) {
    if (lure == null) return
    if (lure.dungeon == null) {
        Text("🏠 stays in town", fontSize = 9.sp, color = Palette.SubText, maxLines = 1)
        return
    }
    Text(
        "🎯 ${lure.dungeon}" + if (lure.timid) " 😨 timid" else "",
        fontSize = 9.sp,
        // The tutorial pulses [timidGlow] to make the timid marker blink.
        color = if (lure.timid) Palette.Damage.copy(alpha = timidGlow) else normalColor,
        maxLines = 1
    )
}

@Composable
private fun PartyBox(party: Party, lure: Lure, onInspect: (Hero) -> Unit, timidGlow: Float = 1f) {
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
            TargetLine(lure, Palette.PartyHead, timidGlow)
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
    highlighted: Boolean = false,
    timidGlow: Float = 1f
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
            LevelBadge(hero.level)
        }
        TargetLine(lure, Palette.SubText, timidGlow)
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

/**
 * The per-player totals strip (bait icons, then ⚔ damage, 🪙 points, 🩸 wounds),
 * each shown as you/opponent… in seating order. Used in the Top bar and reused
 * unchanged by the tutorial so both read identically. [onClick] opens standings.
 * The tutorial-only [highlightBait] / [highlightPoints] / [glow] ring a stat.
 */
@Composable
internal fun PlayerStatsStrip(
    game: Game,
    human: Player,
    onClick: (() -> Unit)? = null,
    highlightBait: Bait? = null,
    highlightPoints: Boolean = false,
    glow: Float = 1f
) {
    fun Modifier.ring(lit: Boolean) =
        if (lit) this
            .clip(RoundedCornerShape(6.dp))
            .border(2.dp, Palette.Highlight.copy(alpha = glow), RoundedCornerShape(6.dp))
            .padding(horizontal = 3.dp)
        else this

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
            Box(Modifier.ring(b == highlightBait)) {
                PlayerStat(game, human, CardArt.baitEmoji[b].orEmpty()) { baitTotal(it, b) }
            }
        }
        PlayerStat(game, human, "⚔") { playerDamage(game, it) }
        Box(Modifier.ring(highlightPoints)) {
            PlayerStat(game, human, "🪙") { it.points }
        }
        PlayerStat(game, human, "🩸", higherBetter = false) { it.wounds }
    }
}

/** A player's effective dungeon damage (rooms + boss), or 0 if no dungeon yet. */
private fun playerDamage(game: Game, player: Player): Int =
    player.dungeon?.let { EncounterDamage.dungeonTotal(it, applicablePoints(game, player)) } ?: 0

/** A player's total icons of one bait type across their dungeon, or 0. */
private fun baitTotal(player: Player, bait: Bait): Int =
    player.dungeon?.let { BaitCounter.enticement(it, bait) } ?: 0

/**
 * "<icon> you/p2/p3/p4" — every player's [value] in seating order, you first.
 * Coloured by how you compare with the living opponents: green when you alone
 * lead, grey on a tie, red when an opponent is ahead. For lower-is-better stats
 * (wounds) pass [higherBetter] = false so fewer counts as leading.
 */
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
    val standings = game.standings()
    // Three or four players: two columns so nothing has to scroll. Two players
    // stay in a single narrow column.
    val twoCol = game.players.size >= 3
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(12.dp), color = Color.White) {
            Column(
                Modifier.widthIn(min = 260.dp, max = if (twoCol) 660.dp else 340.dp).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Standings", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (twoCol) {
                    val half = (standings.size + 1) / 2   // rank order fills the left column first
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            standings.take(half).forEach { StandingCard(game, it, onView, onDismiss) }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            standings.drop(half).forEach { StandingCard(game, it, onView, onDismiss) }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        standings.forEach { StandingCard(game, it, onView, onDismiss) }
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

/**
 * One player's standings card: the boss icon + name + the boss's own damage on
 * the top line, then role/elimination, bait totals, and points/wounds/score.
 * Tapping it views that player's dungeon.
 */
@Composable
private fun StandingCard(
    game: Game,
    s: Scoreboard.Standing,
    onView: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val boss = s.player.dungeon?.boss
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (s.eliminated) Color(0xFFF1F1F1) else Palette.HighlightFill)
            .clickable { onView(s.player.name); onDismiss() }
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (boss != null) CardArt.bossArt(boss.id) else "🏰", fontSize = 15.sp)
            Text(boss?.name ?: s.player.name, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                modifier = Modifier.weight(1f), maxLines = 1)
            if (boss != null) {
                Text("⚔${boss.damage}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.Damage)
            }
        }
        Text(
            (if (game.automated(s.player)) "computer" else "you") + (if (s.eliminated) " · eliminated" else ""),
            fontSize = 11.sp, color = Palette.SubText
        )
        Text(
            Bait.entries.joinToString("  ") { "${CardArt.baitEmoji[it]}${baitTotal(s.player, it)}" },
            fontSize = 12.sp
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("🪙 ${s.player.points} · 🩸 ${s.player.wounds}", fontSize = 12.sp)
            Text("score ${s.score} · ⚔ ${playerDamage(game, s.player)}", fontSize = 11.sp, color = Palette.SubText)
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
    slotPlace: ((Int) -> Unit)?,         // build/first: place into a SLOT (0..4)
    canPlaceInSlot: ((Int) -> Boolean)?, // build/first: is the selected card valid for this slot?
    slotUpgrade: ((Int) -> Unit)?,       // build: spend a room card to upgrade the SLOT's room
    encounterClick: ((Int) -> Unit)?,    // ability targeting: tap an ENCOUNTER (occupied) index
    boostRooms: Set<Int>,                // encounter indices
    onBoost: (Int) -> Unit,
    onShowDetail: (Any) -> Unit,
    activeIndex: Int?,
    incoming: Party? = null,
    prediction: PartyCrawlResolver.Result? = null,
    // Tutorial-only bait spotlight, forwarded to the room/boss cards.
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
        if (dungeon != null && incoming != null) IncomingParty(incoming, onShowDetail)
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
                // Render all 5 slots in order; empties show as gaps (tappable while
                // building). An occupied slot's ENCOUNTER index is its position among
                // the occupied rooms (what the crawl/boost/damage code uses).
                var encounterIndex = 0
                dungeon.slots.forEachIndexed { slot, placed ->
                    if (placed == null) {
                        val placeable = slotPlace != null && canPlaceInSlot?.invoke(slot) == true
                        val mod = if (placeable) Modifier.clickable { slotPlace!!(slot) } else Modifier
                        EmptyRoomSlot(slot, active = placeable, modifier = mod)
                    } else {
                        val ei = encounterIndex
                        encounterIndex += 1
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            val mod = when {
                                slotPlace != null && canPlaceInSlot?.invoke(slot) == true ->
                                    Modifier.clickable { slotPlace(slot) } // place/replace/attach
                                encounterClick != null -> Modifier.clickable { encounterClick(ei) }
                                else -> Modifier
                            }
                            RoomCardView(placed, mod, highlighted = activeIndex == ei,
                                parts = EncounterDamage.parts(dungeon, placed, points, mods, ei),
                                onInfo = { onShowDetail(placed) },
                                baitHighlight = baitHighlight, baitGlow = baitGlow)
                            if (slotUpgrade != null) {
                                OutlinedButton(onClick = { slotUpgrade(slot) }) {
                                    Text("Upgrade", fontSize = 11.sp)
                                }
                            }
                            if (boostRooms.contains(ei)) {
                                OutlinedButton(onClick = { onBoost(ei) }) {
                                    Text("Boost", fontSize = 11.sp)
                                }
                            }
                            DeathMarkers(prediction, ei)
                            FledMarkers(prediction, mods?.retreatIndex(), ei)
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    // Label only opponents (P2/P3/…); your own boss needs no marker.
                    val ownerLabel = if (game.automated(player)) "P${game.players.indexOf(player) + 1}" else null
                    val bossIndex = dungeon.rooms.size
                    BossCardView(dungeon.boss, Modifier, highlighted = activeIndex == bossIndex,
                        parts = EncounterDamage.parts(dungeon, dungeon.boss, points, mods, bossIndex),
                        ownerLabel = ownerLabel,
                        onInfo = { onShowDetail(dungeon.boss) },
                        baitHighlight = baitHighlight, baitGlow = baitGlow)
                    DeathMarkers(prediction, bossIndex)
                    SurvivorMarkers(prediction, retreated = mods?.retreating() == true)
                }
            }
        }
    }
}

/**
 * The party about to crawl, shown to the left of the dungeon (heroes enter from
 * the left). Each hero chip is tappable to open its stats. Where each dies is
 * shown by a red marker in the room itself (see [DeathMarkers]).
 */
@Composable
private fun IncomingParty(party: Party, onShowDetail: (Any) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = Alignment.Start) {
        Text("entering →", fontSize = 9.sp, color = Palette.SubText, fontWeight = FontWeight.Bold)
        party.heroes.groupBy { it.id }.values.forEach { group ->
            val hero = group.first()
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Palette.HeroBg)
                    .border(1.dp, Palette.HeroBorder, RoundedCornerShape(8.dp))
                    .clickable { onShowDetail(hero) } // tap to see this hero's stats
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(CardArt.heroArt(hero.id), fontSize = 15.sp)
                Text(hero.name + if (group.size > 1) " ×${group.size}" else "",
                    fontWeight = FontWeight.Bold, fontSize = 11.sp)
                LevelBadge(hero.level)
            }
        }
    }
}

/** Colour scheme for a party box in the crawl-progress row, by its crawl state. */
private enum class CrawlPartyState(val bg: Color, val border: Color) {
    WENT(Color(0xFFECECEC), Color(0xFFB8B8B8)),     // already dealt with (grey)
    NOW(Color(0xFFDDF4E0), Color(0xFF3FA34D)),      // about to crawl (green)
    WAITING(Color(0xFFE1EEFB), Color(0xFF4C8FD6))   // still waiting (blue)
}

/** A crawled hero's fate marker for the progress row: died / fled / survived. */
private fun heroFate(outcome: GauntletPhase.Outcome, hero: Hero): String = when {
    outcome.result.deadHeroes.any { it === hero } -> "💀"
    outcome.retreated -> "↩"   // any member that lived through a retreat fled
    else -> "✓"
}

/** Height reserved for the crawl-progress row so the dungeon never shifts up. */
private val CrawlRowHeight = 26.dp

/**
 * The crawl-progress row: every party this turn as a compact box, in town order,
 * coloured by state (grey done / green about-to-go / blue waiting). "Party i of
 * N" is pinned on the left and never scrolls; the boxes scroll horizontally when
 * there are many parties. Outside the Crawl phase it stays present but empty,
 * holding its height so the dungeon below keeps a steady position.
 */
@Composable
private fun CrawlPartyRow(game: Game, animating: Boolean, onShowDetail: (Any) -> Unit) {
    val parties = game.crawlOrder()
    // Stay visible through the resolve animation: the engine may have already
    // left the Crawl phase (last Send finishes the turn synchronously), so fall
    // back to the party being animated when there is no pre-crawl party.
    val current = game.nextCrawl()?.second ?: game.lastOutcomes.firstOrNull()?.party
    if ((!game.crawling() && !animating) || parties.isEmpty() || current == null) {
        Spacer(Modifier.height(CrawlRowHeight))   // reserve space between phases
        return
    }
    val currentIdx = parties.indexOfFirst { it === current }.coerceAtLeast(0)

    Row(
        Modifier.fillMaxWidth().heightIn(min = CrawlRowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Pinned position label — always visible, outside the scrolling region.
        Text(
            "Party ${currentIdx + 1} of ${parties.size}",
            fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Palette.SubText, maxLines = 1
        )
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            parties.forEachIndexed { i, party ->
                val state = when {
                    i < currentIdx -> CrawlPartyState.WENT
                    i == currentIdx -> CrawlPartyState.NOW
                    else -> CrawlPartyState.WAITING
                }
                CrawlPartyChip(game, party, isCurrent = party === current, state = state, onShowDetail = onShowDetail)
            }
        }
    }
}

/**
 * One party in the crawl-progress row: its members (grouped by class + level)
 * with the boss icon of the dungeon it is headed into. Once the party has
 * crawled, each member also carries its fate — 💀 died, ↩ fled, ✓ survived —
 * and members are grouped by class + level + fate so mixed outcomes stay clear.
 */
@Composable
private fun CrawlPartyChip(
    game: Game,
    party: Party,
    isCurrent: Boolean,
    state: CrawlPartyState,
    onShowDetail: (Any) -> Unit
) {
    val target = if (isCurrent) game.nextCrawl()?.first else EnticePhase.mostEnticingPlayer(game, party)
    val bossId = target?.dungeon?.boss?.id
    val outcome = game.crawlOutcomeFor(party)
    // After a crawl the party loses its dead members, so read the full roster and
    // each member's fate from the retained outcome instead of party.heroes.
    val roster = outcome?.result?.participants ?: party.heroes
    Row(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(state.bg)
            .border(1.5.dp, state.border, RoundedCornerShape(7.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Group identical members: by class + level, plus fate once they've gone.
        roster.groupBy { Triple(it.id, it.level, outcome?.let { o -> heroFate(o, it) }) }.values.forEach { grp ->
            val hero = grp.first()
            val fate = outcome?.let { heroFate(it, hero) }
            Row(
                Modifier.clickable { onShowDetail(hero) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(CardArt.heroArt(hero.id), fontSize = 13.sp)
                LevelBadge(hero.level)
                if (grp.size > 1) Text("×${grp.size}", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                if (fate != null) Text(fate, fontSize = 11.sp)
            }
        }
        if (bossId != null) Text(CardArt.bossArt(bossId), fontSize = 13.sp)
    }
}

/**
 * Under an encounter: WHICH hero(es) die there — each dying hero's class icon
 * with a 💀 (×N if several of the same class fall in that room). Reads the
 * per-step log so the marker names the victim, not just a count.
 */
@Composable
private fun DeathMarkers(prediction: PartyCrawlResolver.Result?, encounterIndex: Int) {
    val dyers = prediction?.log?.filter { it.died && it.roomIndex == encounterIndex }?.map { it.hero } ?: emptyList()
    if (dyers.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        dyers.groupBy { it.id }.values.forEach { group ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Palette.DyingBg)
                    .border(1.dp, Palette.Damage, RoundedCornerShape(6.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    "${CardArt.heroArt(group.first().id)}💀" + if (group.size > 1) "×${group.size}" else "",
                    fontSize = 12.sp, color = Palette.Damage
                )
            }
        }
    }
}

/**
 * Under the boss: only heroes who make it through the WHOLE crawl alive (green
 * ✓ — the owner takes a wound). Fleeing via a Retreat turns the party back
 * *before* the boss, so those heroes never reach it — they're shown at the
 * retreat room by [FledMarkers], not here.
 */
@Composable
private fun SurvivorMarkers(prediction: PartyCrawlResolver.Result?, retreated: Boolean = false) {
    if (retreated) return // the party turned back before the boss; nobody reaches it
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

/**
 * Under the retreat room: the heroes who turn back here (amber ↩). A Retreat skips
 * this room and everything after (the boss included), so the escapees are shown
 * at the point they fled, not at a boss they never reached.
 */
@Composable
private fun FledMarkers(prediction: PartyCrawlResolver.Result?, retreatIndex: Int?, encounterIndex: Int) {
    if (retreatIndex == null || encounterIndex != retreatIndex) return
    val fled = prediction?.survivors ?: emptyList()
    if (fled.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        fled.groupBy { it.id }.values.forEach { group ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Palette.FledBg)
                    .border(1.dp, Palette.FledBorder, RoundedCornerShape(6.dp))
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            ) {
                Text("↩${CardArt.heroArt(group.first().id)}" + if (group.size > 1) "×${group.size}" else "",
                    fontSize = 12.sp, color = Palette.FledText)
            }
        }
    }
}

@Composable
private fun GameOverBanner(@Suppress("UNUSED_PARAMETER") tick: Int, game: Game) {
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
            val line = if (s.eliminated) {
                "${s.player.name}: eliminated (5 wounds)"
            } else {
                "${s.player.name}: score ${s.score} (${s.player.points} pts − ${s.player.wounds} wounds)"
            }
            Text(line, fontSize = 12.sp, color = Palette.SubText)
        }
        Text("Tap New game (bottom-right) to play again.", fontSize = 12.sp, color = Palette.SubText)
    }
}

@Composable
private fun AdvanceBar(
    @Suppress("UNUSED_PARAMETER") tick: Int,
    game: Game,
    humanName: String,
    viewingOther: Boolean = false,
    onReturn: () -> Unit = {},
    awaitingCrawlStart: Boolean = false,
    onBeginCrawl: () -> Unit = {},
    onDecide: (String?, Any?) -> Unit,
    onNextTurn: () -> Unit,
    onSend: () -> Unit,
    onNewGame: () -> Unit,
    onUndo: () -> Unit,
    onUndoBoss: () -> Unit,
    onUndoPlacement: () -> Unit,
    onUndoAbility: () -> Unit,
    onContinueQuiet: () -> Unit,
    onCancelAbility: () -> Unit,
    pendingAbility: AbilityCard?,
    pendingBoostRoom: Int?,
    discardSelection: List<String> = emptyList()
) {
    // While peeking at another player's dungeon (from Standings), the whole bar
    // becomes a single Return button that snaps back to the live view.
    if (viewingOther) {
        Surface(shadowElevation = 8.dp, color = Color.White) {
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Button(
                    onClick = onReturn,
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent)
                ) {
                    Text("Return", color = Color.White, fontSize = 15.sp)
                }
            }
        }
        return
    }

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
        mineKind == DecisionKind.PLACE_FIRST_ROOM -> Triple("Tap a room, then a slot", false, noop)
        mineKind == DecisionKind.DISCARD_ROOMS -> {
            val n = discardSelection.size
            val label = if (n == 0) "Discard nothing ▶" else "Discard $n ▶"
            // 0–2 cards; confirm sends the comma-joined ids (null = discard nothing).
            Triple(label, true, { onDecide(discardSelection.joinToString(",").ifEmpty { null }, null) })
        }
        mineKind == DecisionKind.BUILD_ROOM -> Triple("Build nothing", true, { onDecide(null, null) })
        game.quiet() -> Triple("Continue ▶", true, onContinueQuiet)
        // After building, pause on your dungeon; Continue begins the crawl.
        awaitingCrawlStart && game.crawling() -> Triple("Continue ▶", true, onBeginCrawl)
        game.crawling() && game.nextCrawl() != null -> Triple("Send ▶", true, onSend)
        game.over() -> Triple("", false, noop)
        setupDone -> Triple("Start ▶", true, onNextTurn)
        game.ready() -> Triple("Next turn ▶", true, onNextTurn)
        else -> Triple("", false, noop)
    }

    Surface(shadowElevation = 8.dp, color = Color.White) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), contentAlignment = Alignment.CenterEnd) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // New game appears at the bottom-right only once the game is over.
                if (game.over()) {
                    Button(
                        onClick = onNewGame,
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent)
                    ) {
                        Text("New game", color = Color.White)
                    }
                }
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
                // canUndoPlacement() already gates on stage (CRAWLING or QUIET), so
                // don't add a crawling-only check — that hid the button on a quiet
                // round, where you can still take back the room you just placed.
                if (game.canUndoPlacement()) {
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
internal fun CardDetailDialog(card: Any, onDismiss: () -> Unit) {
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
                        if (card.level > 0) DetailStat("Level", card.level.toString())
                        DetailBait(card.bait)
                        DetailBody(describeRoom(card))
                    }
                    is Boss -> {
                        DetailHeader(CardArt.bossArt(card.id), card.name, "Boss")
                        DetailStat("Damage", card.displayDamage.toString())
                        DetailBait(card.bait)
                        DetailBody(card.abilityText)
                    }
                    is Room -> {
                        DetailHeader(CardArt.roomArt(card.type), card.name,
                            if (card.advanced) "Advanced · ${card.type}" else card.type)
                        DetailBait(card.bait)
                        DetailBody(describeRoom(card))
                    }
                    is AbilityCard -> {
                        DetailHeader("✨", card.name, "Ability")
                        DetailBody(card.text)
                    }
                    is Hero -> {
                        DetailHeader(card.icon.ifEmpty { CardArt.heroArt(card.id) }, card.name, "Hero")
                        DetailStat("Level", card.level.toString())
                        DetailStat("HP", card.maxHp.toString())
                        DetailStat("Courage", card.courage.toString())
                        DetailStat("Preferred bait", CardArt.baitEmoji[card.preferredBait].orEmpty())
                        if (card.partyDamageReduction > 0 || card.partyDamageReductionLevelIncrement > 0) {
                            DetailStat("Party reduction", card.partyReduction.toString())
                        }
                        if (card.selfDamageMultiplier != 1.0) {
                            DetailStat("Self damage ×", card.selfDamageMultiplier.toString())
                        }
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

/**
 * A concise description of what a room does, built from its fields. Assumes the
 * player knows the rules: no leveling notes, no explanation of how damage works.
 * Shown in the card detail popup.
 */
private fun describeRoom(e: Encounter): String {
    val parts = mutableListOf<String>()

    if (e.leadDamage > 0) parts.add("Deals ${e.leadDamage} damage.")
    if (e.damageAll > 0) {
        val who = e.damageFilter?.let { " to all ${it.lowercase()}s" } ?: " to all"
        parts.add("Deals ${e.damageAll} damage$who.")
    }
    if (e.damageRear > 0) parts.add("Deals ${e.damageRear} damage to weakest first.")
    // Call out only the fast growers — a +1-per-level room grows too slowly to be
    // worth noting, so the note appears only when an increment exceeds 1.
    val growInc = maxOf(e.leadIncrement, e.allIncrement, e.rearIncrement)
    if (growInc > 1.0) {
        val label = if (growInc % 1.0 == 0.0) growInc.toInt().toString() else growInc.toString()
        parts.add("Increases by $label each level.")
    }
    when (e.roomResist) {
        true -> parts.add("Cannot be reduced.")
        false -> parts.add("Cannot be halved.")
        else -> {}
    }
    if (e.poisonDamage > 0) {
        parts.add(
            if (e.poisonPersists) "Poisons: ${e.poisonDamage} damage each later room."
            else "Then ${e.poisonDamage} damage next room."
        )
    }
    if (e.poisonTicks > 1) parts.add("Poison triggers ${e.poisonTicks}× here.")
    if (e.growsOnDeath) parts.add("Grows on death.")
    if (e.drawOnDeath) parts.add("Draw a room and ability card per death.")
    if (e.discardLeadDamage > 0 || e.discardAllDamage > 0) {
        val amt = if (e.discardAllDamage > 0) e.discardAllDamage else e.discardLeadDamage
        parts.add("Discard a card to add $amt damage.")
    }
    e.roomAura?.let { aura ->
        val amount = (aura["amount"] as? Number)?.toInt() ?: 0
        @Suppress("UNCHECKED_CAST")
        val matchType = ((aura["match"] as? Map<String, Any?>)?.get("type"))?.toString()?.lowercase()
        val kind = when {
            matchType?.contains("trap") == true -> "traps"
            matchType?.contains("creature") == true || matchType?.contains("monster") == true -> "creatures"
            else -> "rooms"
        }
        if (amount > 0) parts.add("Other $kind deal +$amount damage.")
    }
    return parts.joinToString(" ")
}
