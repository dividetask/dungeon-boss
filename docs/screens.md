# Screens

The web app is a **single page** that changes with the game's `stage` and the
current pending `Decision`. The Android client should present the same regions
and states. This document describes each screen; the rules behind them live in
[phases.md](phases.md).

A round flows through these states:

```
Home → Setup (choose boss → place first room) → READY
  └─ loop: Discard (0–2) → Draw → Build (place/upgrade/skip) →
           Crawl (Entice → Ability → Gauntlet: send parties)
           or Quiet (no party crawled) → Recharge → READY → …
  └─ Game over
```

## Persistent layout

Every in-game screen stacks these regions top-to-bottom (the **Top bar** and
**Advance bar** are always present; the rest appear when they have content):

```
┌─ Top bar ───────────────────────────────────────────────────────────────────┐
│ Dungeon Boss                              Players <2|3|4 ▾>  [ New game ]     │
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

- **Boss quick-sheet** shows the dungeon's total damage (`⚔ total (base+bonus)`),
  bait totals, points and wounds; an eliminated player (5 wounds) is dimmed and
  marked `☠ eliminated`. Tapping one shows that player's dungeon below.
- **Card damage** is shown as the effective total with a small breakdown line
  (`5 + 2 + 2` = base + upgrade + aura). Points-based bonuses are hidden while a
  crawl is in progress.

---

## Home Screen

No game in progress.

```
┌────────────────────────────────────────────────────────────────────────────────┐
│ Dungeon Boss                              Players <2|3|4 ▾>  [ New game ]     │
│ Choose how many players (2–4), then tap New game. You are Player 1; the rest   │
│ are computer opponents.                                                        │
└────────────────────────────────────────────────────────────────────────────────┘
```

- **Players** dropdown: 2, 3, or 4. **New game** starts a fresh game (Player 1 is
  the human; Players 2..N are computer opponents).

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

## Discard

`stage = building`, decision `discard_rooms`. Tap **0, 1, or 2** hand cards to
discard (optional — you may discard nothing), then confirm. The Draw phase then
gives you **1 + (cards discarded)** new rooms. After confirming, an **Undo
discard** option appears during the build step.

```
│ Player 1: discard 0–2 room cards (optional)                                    │
│ Your hand:  [card][card][card][card][card][card]  ← tap up to two              │
│ "Discarding more draws more: you draw 1 + the number you discard."             │
│ Advance bar: [ Confirm discard (0) ]                                           │
```

## Build — Place or upgrade a room (or build nothing)

Decision `build_room`. Tap a hand card, then tap a slot in your dungeon:

- a **room** placed into an **empty** slot fills it; into an **occupied** slot
  replaces that room;
- an **advanced room** may fill an empty slot, or replace a room sharing one of
  its bait icons;
- a dedicated **upgrade** card attaches to a room;
- alternatively, tap **Upgrade** on a placed room then a **room card** to spend it
  (the room gains that card's bait icons and a level).

```
│ Player 1: place a room, upgrade a room, or build nothing                       │
│ Your hand:  [card]selectable…           [↶ Undo discard]                       │
│ Player 1 — dungeon:  [slot0][slot1][slot2][slot3][slot4][BOSS]  ← tap a slot   │
│ Advance bar: [ Build nothing ]                                                 │
```

---

## Crawl — Ability step (pre-Gauntlet)

`stage = crawling`, a party has been enticed and is about to enter a dungeon (the
**Ability** step before the Gauntlet). The panel shows the target dungeon's
encounters and your ability cards. *(The full turn-based priority loop is a
[TODO](phases.md#7b-ability); today this is the one pre-Gauntlet window.)*

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
│   Player 1: score 6 (8 pts − 1 wound)                                          │
│   Player 2: eliminated (5 wounds)                                              │
│   …                                                                            │
│ Advance bar: "Game over — tap New game to play again."                         │
```

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
