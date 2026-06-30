# Dungeon Boss — Specification

This folder is the **single source of truth** for Dungeon Boss. Both the
Sinatra web app and the Android app implement the rules and class design
described here. When a rule changes, change it here first.

## Documents

- **[game-overview.md](game-overview.md)** — What the game is, its components,
  and how a player wins.
- **[phases.md](phases.md)** — The turn structure: Setup, then Arrival, Discard,
  Draw, Build, Crawl (Entice → Ability → Gauntlet), and Recharge, with the exact
  resolution rules.
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

- **Damage and HP** — rooms and bosses deal damage; bosses deal **+1 per point**
  they have; heroes have HP and die at ≤ 0.
- **Bait and preferred bait** — parties are lured to the most enticing dungeon.
- **Courage and parties** — heroes move as parties; timid/unenticed parties
  **wait** and are consolidated in **Recharge**.
- **Data-driven levelling heroes** — HP, courage, and a party damage reduction
  scale with each hero's **level** (gained by surviving crawls); a self-damage
  multiplier protects the hero itself. All read from the hero YAML — no per-id code.
- **5-slot dungeon + room-card upgrades** — rooms occupy any of 5 slots; spending
  a room card upgrades a placed room (grants bait + a room **level**, whose effect
  is on a separate branch).
- **Advanced rooms and upgrades** — `effect`-driven room behaviour; upgrades
  attach to rooms.
- **Boss effects** — `effect`-driven boss behaviour (e.g. Goblin Chieftain).
- **Ability cards** — drawn in Recharge by un-attacked players and played during
  the crawl's Ability step to alter it.
- **Tags** — classify cards so effects can target groups (e.g. `goblin`).

**Documented but not built:** the **Ability** priority loop (play/pass) is a
**TODO**; the existing pre-Gauntlet ability interaction stands in for now.

**Still flavor-only:** the free-text `ability_text` fields on bosses/rooms/heroes
(the mechanical behaviour lives in structured `effect` keys / hero fields instead).

> **Reference vs Android.** This rules overhaul updates the spec, `data/cards/`,
> and the **Android** client; the **web app is intentionally not being updated**.
> See the parity table in
> [architecture.md](architecture.md#client-parity-reference-vs-android).

## Conventions

- Bait types are exactly: **glory**, **riches**, **undead**, **arcane**.
- Dungeon layout: **5 ordered room slots** (some may be empty) with the **boss**
  on the right; a hero **enters from the left** crawling (skipping empties) toward
  the boss.
- Any place where the original design was ambiguous, the resolved choice is
  marked **(interpretation)** so it is easy to find and revisit.
