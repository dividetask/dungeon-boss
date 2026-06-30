# Screens

The web app is a **single page** that changes with the game's `stage` and the
current pending `Decision`. The Android client should present the same regions
and states. This document describes each screen; the rules behind them live in
[phases.md](phases.md).

A round flows through these states:

```
Load → Setup (choose boss → place first room) → READY
  └─ loop: Build (discard → place/skip) → Bait+Crawl (send parties / pre-crawl)
           or Quiet (no heroes attacked) → READY → …
  └─ Game over
```

## Persistent layout

Every in-game screen stacks these regions top-to-bottom (the **Top bar** and
**Advance bar** are always present; the rest appear when they have content):

```
┌─ Top bar ───────────────────────────────────────────────────────────────────┐
│ Dungeon Boss · Round <n> · <stage>      [per-player totals strip]  [Share log] [☰] │
│ ░ menu (revealed by ☰): UI build · [ New game ] · Players <2|3|4>             │
├──────────────────────────────────────────────────────────────────────────────┤
│ Round <n> · <stage>                                                           │
│ [ decision banner — the current prompt, when one is pending ]                 │
│ [ game-over banner — winner + standings, when the game is over ]              │
├─ Town ───────────────────────────┬─ Bosses (one quick-sheet per player) ──────┤
│ hero / party cards (scroll →)     │ [Boss name | you/computer | ⚔ total |     │
│                                   │  bait totals | 🪙 points · 🩸 wounds]      │
├──────────────────────────────────┴────────────────────────────────────────────┤
│ Your hand            room/upgrade/advanced cards (scroll →)                    │
│ Ability cards        ability cards (when you hold any)                         │
├────────────────────────────────────────────────────────────────────────────────┤
│ Player N — dungeon (entrance on the left)                                      │
│   [Room][Room]…[Room][BOSS]    (tap a boss summary above to view that dungeon) │
├────────────────────────────────────────────────────────────────────────────────┤
│ [ Pre-crawl panel  OR  Quiet panel ]   (state-specific, see below)             │
│ [ Crawl breakdown ]                    (after a crawl resolves)                │
├─ Advance bar (fixed, bottom) ──────────────────────────────────────────────────┤
│ [ context button: Build nothing / Send party / Continue / Next turn / hint ]  │
└────────────────────────────────────────────────────────────────────────────────┘
```

- **Menu (☰)** sits at the far right of the Top bar. During a game the menu is
  collapsed; tapping ☰ reveals it (UI build, **New game**, **Tutorial**,
  **Players** selector). On the **Load screen** (no game) the menu is always open.
- **Share log** sits in the Top bar's top-right corner, immediately to the left
  of the ☰ toggle. It is hidden on every screen **except** when the menu is
  revealed — i.e. on the Load screen, or once ☰ has been pushed on any other
  screen. (Share log exports the diagnostic log for bug reports.)
- **Boss quick-sheet** shows the dungeon's total damage (`⚔ total (base+bonus)`),
  bait totals, points and wounds; an eliminated player (5 wounds) is dimmed and
  marked `☠ eliminated`. Tapping one shows that player's dungeon below.
- **Card damage** is shown as the effective total with a small breakdown line
  (`5 + 2 + 2` = base + upgrade + aura). Points-based bonuses are hidden while a
  crawl is in progress.

---

## Load Screen

No game in progress — the screen the app opens on. The menu is open by default
here (no ☰ tap needed), so all of its controls are visible at once.

```
┌────────────────────────────────────────────────────────────────────────────────┐
│ Dungeon Boss                                                  [Share log]  [☰]  │
│ UI build <id>                                                                  │
│ [ New game ]  [ Tutorial ]                                                     │
│ Players <2|3|4>                                                                │
│ Tap New game to begin. You are Player 1; the others are computer opponents.    │
└────────────────────────────────────────────────────────────────────────────────┘
```

- **Players** selector: 2, 3, or 4. **New game** starts a fresh game (Player 1 is
  the human; Players 2..N are computer opponents).
- **Tutorial** opens the [Tutorial](#tutorial) — a guided, non-interactive
  walkthrough of the rules. It is available here and from the ☰ menu mid-game.
- **Share log** appears in the top-right next to the ☰ toggle (see the Top-bar
  notes above). This is the one screen where it is visible without opening the
  menu, because the menu is already open here.

---

## Setup — Choose Boss

`stage = setup`, decision `choose_boss`. The dungeon area shows the **two boss
candidates** as tappable cards.

```
│ Round 0 · setup                                                                │
│ Player 1: choose your boss (the other is discarded)                            │
│ …                                                                              │
│ Player 1 — dungeon (entrance on the left)                                      │
│   [ Boss A ]  [ Boss B ]      ← tap one to keep it                             │
│ ──────────────────────────────────────────────────────────────────────────── │
│ Advance bar: "Tap a card to choose"                                            │
```

## Setup — Place First Room

Decision `place_first_room`. **Your hand** shows the dealt cards; only basic
rooms are tappable (the first placement must be a room).

```
│ Player 1: choose a room to place beside your boss                              │
│ Your hand:  [Room]* [Room]* [Upgrade] [Advanced]   (* = tappable)              │
│ Advance bar: "Tap a card to choose"                                            │
```

---

## Build — Discard

`stage = building`, decision `discard_room`. Tap any hand card to discard it
(mandatory). After choosing, an **Undo discard** option appears during the build
step.

```
│ Player 1: discard a room card                                                  │
│ Your hand:  [card][card][card][card][card][card]  ← tap one to throw away      │
│ "Tap a card to throw it away."                                                 │
│ Advance bar: "Tap a card to discard"                                           │
```

## Build — Place a room (or build nothing)

Decision `build_room`. Tap a hand card, then tap a slot in your dungeon:
a basic room adds at the entrance or replaces a room; an upgrade attaches to a
room; an advanced room replaces a room sharing one of its bait icons.

```
│ Player 1: choose a room to add to your dungeon, or build nothing               │
│ Your hand:  [card]selectable…           [↶ Undo discard]                       │
│ Player 1 — dungeon:  [+ add new room here] [Room]…[BOSS]   ← tap a slot        │
│ Advance bar: [ Build nothing ]                                                 │
```

---

## Bait + Crawl — Pre-crawl

`stage = crawling`, a party is about to enter a dungeon. The **Pre-crawl panel**
shows the target dungeon's encounters and your ability cards.

```
│ ⚔ <Party name> → <Owner>'s dungeon                                             │
│ Tap an ability card then a room (Retreat: the party turns back at that room);  │
│ or boost a room by tapping "Boost" then a hand card. Then send them in.        │
│   [Room][Room]…[BOSS]            ← rooms show effective damage incl. the boost  │
│   [Boost (discard a card)] under each boostable room you own                   │
│ Your ability cards:  [Reinforcements][Sabotage][Blueprints]…                   │
│ Advance bar: [ Send <Party> into <Owner>'s dungeon ]                           │
```

- **Play an ability card**: tap it; room-targeting cards (Reinforcements, Expose
  Weakness, Sabotage) then want a room tap; others (Retreat, Blueprints) fire at
  once.
- **Discard-to-boost**: on a boostable room you own, tap **Boost**, then tap a
  hand card to discard — the room gains +4 (or becomes unreducible) this crawl.
- **Send** resolves the crawl, animating each hero room-by-room; then the next
  party is evaluated (its courage re-checked against the now-current points).

## Crawl breakdown

After a crawl resolves it appears below: exactly how much each encounter dealt
to each hero.

```
│ Crawl breakdown                                                                │
│ ⚔ <Party> → <Owner>'s dungeon                                                  │
│ ┌ Encounter        Hero       Damage   HP ───────────────────────────────────┐│
│ │ Stone Ball        Barbarian   −3      8 → 5                                  ││
│ │ Malevolent Spirit Barbarian   −11     5 → 0 💀                               ││
│ └──────────────────────────────────────────────────────────────────────────  ┘│
│ → <Owner> gains N points, 1 wound. Survivors: …                                │
```

---

## Quiet round (no heroes attacked)

`stage = quiet`. No party entered any dungeon. Players may play **Blueprints**
(draw rooms) before continuing; then each player draws an ability card.

```
│ No heroes attacked                                                             │
│ You may play Blueprints to draw room cards, then continue.                     │
│   [Blueprints] …    (tap to play; or "No Blueprints in hand.")                 │
│ Advance bar: [ Continue (no heroes attacked) ]                                 │
```

---

## Game over

`stage = over`. A winner is declared with final standings.

```
│ 🏆 <Winner> wins!                                                              │
│   Player 1: score 11 (8 pts − 1 wound + 5 end-game bonus)                      │
│   Player 2: eliminated (5 wounds)                                              │
│   …                                                                            │
│ Advance bar: "Game over — tap New game to play again."                         │
```

- The player who **ended the game** (reached 10 points, or eliminated the last
  rival) earns a **+5 end-game bonus**, shown on their standings line; final
  score is `points − 2 × wounds (+ 5 for the ender)`. See
  [phases.md](phases.md) "Match end / scoring".

---

## State → Advance-bar button

| State | Bottom button |
|-------|---------------|
| Build, discard step | hint: "Tap a card to discard" |
| Build, place step | **Build nothing** |
| Any other pending decision | hint: "Tap a card to choose" |
| Crawling (party ready) | **Send `<Party>` into `<Owner>`'s dungeon** |
| Quiet round | **Continue (no heroes attacked)** |
| Ready (turn done) | **Next turn** |
| Game over | hint: "Game over — tap New game…" |

---

## Tutorial

A guided, **non-interactive** walkthrough of the rules, opened from **Tutorial**
on the [Load screen](#load-screen) or the ☰ menu. Each step shows a **frozen
board** built from real cards (so it looks exactly like a game) with a narration
panel pinned at the bottom. The board cannot be tapped; only the panel's controls
advance it.

```
┌─ Dungeon Boss · Tutorial ─────────────────────────────────────────── [✕ Exit] ┐
│ [ frozen board for this step — town / dungeon / hand / crawl, as needed ]      │
│                                                                                │
├────────────────────────────────────────────────────────────────────────────────┤
│ <narration for this step>                                                      │
│ Step <n> / <total>                                   [ Back ]   [ Continue ]   │
└────────────────────────────────────────────────────────────────────────────────┘
```

- **Continue** advances; **Back** returns to the previous step; **Finish** (on
  the last step) and **✕ Exit** both close the tutorial and return to the screen
  underneath — the **Load screen** when launched there, where the player can
  start a real game or run the tutorial again.
- Some steps animate to direct the eye: a slow **pulsing glow** on bait icons,
  and a **spotlight that cycles** through each hero and the bait it prefers.

The steps, in order (each a single narrated panel):

1. **What you are** — you play the villain; a dungeon of 5 rooms + the boss
   chamber. *(Board: a full dungeon.)*
2. **Setup** — choose a boss from two cards, then place rooms in one of 5 slots.
   *(Board: two boss candidates + a starting hand.)*
3. **Bait icons** — every room and the boss show bait icons. *(Board: a full
   dungeon + one of each hero; all bait icons pulse.)*
4. **Preferred bait** — Barbarian→glory, Rogue→riches, Cleric→undead,
   Mage→power. *(Board: same; the spotlight cycles each hero with its bait lit
   in the rooms.)*
5. **Enticement & turns** — heroes take turns from the left; a hero enters only
   the dungeon with strictly the most of its preferred bait (a tie keeps it in
   town); bait totals sit top-right. *(Board: same, plus the bait-totals panel,
   cycling each hero's bait there too.)*
6. **Crawling** — a hero crosses rooms left→right, taking each room's damage
   until it dies or clears them. *(Board: a hero about to crawl a dungeon that
   kills it.)*
7. **Scoring & courage** — a death scores the owner a point; survivors grow
   cautious. Barbarians/Clerics have courage 2 (avoid dungeons with 3+ points);
   Rogues/Mages have courage 1 (avoid 2+). *(Board: timid lone heroes; a dungeon
   with points.)*
8. **Staying & recruiting** — heroes that skip (timid, or tied bait) wait in
   town and form parties at end of round, each recruiting the leftmost unpartied
   hero of a class it lacks. *(Board: timid lone heroes, no parties yet.)*
9. **Parties** — a party's courage is the sum of its members; its bait is the
   sum of every member's preference (repeats count each time). *(Board: the same
   heroes now grouped into parties.)*
10. **Party crawl** — the highest-HP member usually takes the next room, but some
    rooms hit all or specific heroes. *(Board: a party mid-crawl, some dead, some
    alive.)*
11. **Hero abilities** — Barbarian halves damage taken; Rogue −2 from traps;
    Cleric −4 from undead rooms; Mage −4 from arcane rooms. *(Board: a party that
    shrugs off an undead/arcane dungeon entirely.)*
12. **Ability cards** — cards can make a party flee, raise damage, negate a room,
    or draw rooms; start with two, gain one each round nobody is attacked.
    *(Board: a hand of ability cards.)*
13. **Wounds & winning** — a surviving hero gives the owner a wound (5 = loss);
    reaching 10 points ends the game; the ender gains **+5**; then each player
    loses 2 per wound and the most points wins. *(Board: a dungeon with points.)*
