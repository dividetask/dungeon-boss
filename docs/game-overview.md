# Game Overview

Dungeon Boss is a competitive, simultaneous card game. Each player is a dungeon
boss building a deadly dungeon to lure and kill wandering heroes. You score when
a hero dies in your dungeon; you suffer wounds when a hero survives.

## Components

Each card type is fully specified in [cards.md](cards.md). In brief:

- **Boss cards** — the back of your dungeon. Each has **bait icons**, a
  **damage amount**, and optionally an **effect** (e.g. the Goblin Chieftain).
  A boss deals **+1 damage per point** its owner has scored.
- **Room cards** — the body of your dungeon. Each has a **damage amount**,
  **bait icons**, and a **room type**. **Advanced rooms** add a special
  **effect**; **upgrades** attach to a room to boost it.
- **Hero cards** — the adventurers who crawl dungeons. Each has **health**, a
  **preferred bait**, **courage**, and (for some) a **special ability** that is
  applied during the crawl.
- **Ability cards** — held in hand and **played before a crawl** to alter it
  (e.g. +2 to a room, make a room deal 0, send the party home).
- **Tags** — any card may carry classification **tags** (e.g. `goblin`) so
  effects can target a whole group at once.

## The dungeon layout

```
   entrance                                          back
   (hero enters here)                                of dungeon
        │                                                 │
        ▼                                                 ▼
   [ Room ] [ Room ] [ Room ] ........ [ Room ] [  BOSS  ]
   leftmost                            rightmost
```

- Rooms are added **to the left** of the current leftmost room, so the dungeon
  grows toward the entrance over time.
- The **boss** is fixed on the right.
- A hero **enters from the left** and proceeds room by room toward the boss,
  taking damage at each step. The boss is the final encounter.

## Win condition

- When a hero **dies** in your dungeon, you **gain a point**.
- When a hero **survives** your dungeon, you **gain a wound**.

Points and wounds are tracked per player. A player with **5 wounds** is
eliminated. The game ends when someone reaches **10 points** or all but one
player is eliminated; the winner is the surviving player with the highest
**points − 2 × wounds** (ties go to whoever ended the game). See
[phases.md](phases.md#resolved-rules).

## Players and heroes

- The game supports **2 to 4 players**. Each player runs exactly one dungeon.
- During the Arrival phase, **one hero is drawn per player**, so the number of
  heroes that arrive each round **equals the number of players** (a 4-player
  game adds four heroes to town per round).
- Heroes move as **parties** (a lone hero is a party of one). Each party is
  drawn to the most enticing dungeon during the Bait phase, but only enters if
  its combined **courage** is at least the owner's points; otherwise it **waits**
  and is consolidated with other waiting parties in **Recruitment**.
