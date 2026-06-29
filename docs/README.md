# Dungeon Boss — Specification

This folder is the **single source of truth** for Dungeon Boss. Both the
Sinatra web app and the Android app implement the rules and class design
described here. When a rule changes, change it here first.

## Documents

- **[game-overview.md](game-overview.md)** — What the game is, its components,
  and how a player wins.
- **[phases.md](phases.md)** — The turn structure: Setup, Arrival, Build, Bait,
  Crawl, and Recruitment phases, with the exact resolution rules.
- **[cards.md](cards.md)** — Card types (boss, room, upgrade, advanced room,
  hero, ability), their fields, **tags**, the bait system, and the
  `data/cards/` schema (one file per category).
- **[architecture.md](architecture.md)** — The canonical list of classes and
  their single responsibilities, plus a **client-parity** table tracking what
  the Android client still needs to match the reference web app.
- **[pseudocode.md](pseudocode.md)** — Language-agnostic pseudocode for every
  class's behaviour (the implementation companion to architecture.md).
- **[screens.md](screens.md)** — Each screen/state of the UI, with layouts and
  the controls available in each.

## Scope

The game is currently resolved using:

- **Damage and health** — rooms and bosses deal damage; bosses deal **+1 per
  point** they have; heroes have health and die at 0.
- **Bait and preferred bait** — parties are lured to the most enticing dungeon.
- **Courage and parties** — heroes move as parties; timid/unenticed parties
  **wait** and are consolidated in **Recruitment**.
- **Hero abilities** — damage modifiers applied during the crawl (keyed by id).
- **Advanced rooms and upgrades** — `effect`-driven room behaviour; upgrades
  attach to rooms.
- **Boss effects** — `effect`-driven boss behaviour (e.g. Goblin Chieftain).
- **Ability cards** — played before a crawl to alter it.
- **Tags** — classify cards so effects can target groups (e.g. `goblin`).

**Still flavor-only:** the free-text `ability_text` fields on bosses/rooms/heroes
(the mechanical behaviour lives in structured `effect` keys / hero ids instead).

> **Reference vs Android.** The **web app is the reference implementation**; it
> implements everything above and is unit-tested. The Android client is at an
> earlier rules baseline — see the parity table in
> [architecture.md](architecture.md#client-parity-reference-vs-android).

## Conventions

- Bait types are exactly: **glory**, **riches**, **undead**, **power**.
- Dungeon layout: rooms extend **leftward**, the **boss** is on the right, and a
  hero **enters from the left** crawling toward the boss.
- Any place where the original design was ambiguous, the resolved choice is
  marked **(interpretation)** so it is easy to find and revisit.
