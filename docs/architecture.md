# Architecture — Classes and Responsibilities

Both clients implement the same conceptual classes. Each class has **one
responsibility**. Data holders (cards) hold no game-flow logic; flow classes
(phases, resolvers) hold no data definitions.

The names below are the canonical concept names. Each client uses idiomatic
naming for its language (Ruby `CardLibrary`, Kotlin `CardLibrary`, etc.) but
keeps the responsibilities identical.

## Data / card model (immutable value objects)

| Class          | Responsibility                                                        |
|----------------|-----------------------------------------------------------------------|
| `Bait`         | The set of valid bait types and validation of a bait name.            |
| `BaitIcons`    | An immutable count of icons per bait type; answers "how many of X?".  |
| `Tags`         | An immutable, lowercased set of classification tags; answers "has X?". |
| `Boss`         | One boss card's data: id, name, damage, bait, `effect`, tags.         |
| `Room`         | One room card's data: id, name, type, damage, bait, `effect`, tags, `advanced`. |
| `Upgrade`      | A room-card type that attaches to a room (bonus damage / bait / tags).  |
| `Hero`         | One hero card's **data-driven** stats: id, name, icon, preferred bait, starting HP, hp/level increment, self-damage multiplier, party reduction (+per-level), bait/room-type damage filters, tags. Derives max HP / courage / party reduction from its **level**. |
| `AbilityCard`  | One ability card's data: id, name, text, `effect`, tags.              |

Every card carries an optional `tags` set (see [cards.md](cards.md#tags)).
`ability_text` on bosses/rooms/heroes is flavor; the mechanical behaviour is
keyed by `effect` (bosses/rooms/ability cards) or, for heroes, by the hero's own
**data fields** (no per-id code — see `HeroAbility`).

## Loading

| Class         | Responsibility                                                         |
|---------------|------------------------------------------------------------------------|
| `CardLibrary` | Loads the `data/cards/` files and exposes the full pools of bosses, rooms, |
|               | upgrades, advanced rooms, heroes, and ability cards as model objects.  |
|               | Expands `copies` into distinct instances and validates ids.            |

## Game state

| Class      | Responsibility                                                            |
|------------|---------------------------------------------------------------------------|
| `Deck`     | A drawable, discardable, shuffleable pile of cards. Generic over type.    |
|            | Reshuffles its discard pile back in when drawn from empty.                |
| `Dungeon`  | One player's boss plus **5 ordered room slots** (some may be empty;       |
|            | entrance = leftmost; max 5). Places a room into any slot (empty fills,    |
|            | occupied replaces); yields encounters in crawl order (occupied rooms      |
|            | left→right skipping empties, then boss).                                  |
| `Player`   | A participant: their dungeon, hand of room cards, points, and wounds.     |
| `Party`    | One or more heroes who bait, crawl, and score together (a lone hero is a  |
|            | party of one). Holds combined courage, board order, and a generated name. |
| `PlacedRoom` | A room as it sits in a dungeon: a base `Room` plus at most one          |
|            | `Upgrade`, a grow bonus, and a `level` (raised by spending room cards to  |
|            | upgrade it — grants bait + level); exposes effective damage and bait.     |
| `Scoreboard` | Decides whether the game is over and who won (10 points / 5 wounds /     |
|            | points − 2×wounds).                                                       |
| `Decision` | A choice the game is waiting on a player to make (which boss to keep,     |
|            | which room to place, or whether to build). Describes options; no logic.   |
| `Game`     | Owns the players and the decks, and drives the turn as a sequence of      |
|            | player `Decision`s. Runs the automatic phases (Arrival, Bait+Crawl) on    |
|            | its own. Orchestration only.                                              |

## Logic helpers (no state of their own)

| Class           | Responsibility                                                       |
|-----------------|----------------------------------------------------------------------|
| `BaitCounter`   | Given a dungeon and a bait type, total the matching icons across the |
|                 | boss and all rooms (a dungeon's enticement for a preferred bait).    |
| `CrawlResolver` | *(Legacy)* Run a single hero through a dungeon — superseded by        |
|                 | `PartyCrawlResolver`; kept for reference/tests.                       |
| `PartyNamer`    | Generate a flavorful name for a newly-merged party.                  |
| `PartyCrawlResolver` | Run a whole party through a dungeon: each encounter hits the     |
|                 | highest-health member (ties: max health, then board order), reducing    |
|                 | damage by living party auras + the target's self ability; returns the   |
|                 | per-step log, deaths, and survivors.                                 |
| `HeroAbility`   | Per-hero damage modifiers read from the hero's **data fields** (no       |
|                 | per-id code): a `:party` aura (`party_damage_reduction`, gated by the    |
|                 | bait/room-type filters) and a `:self` `self_damage_multiplier` (rounded  |
|                 | up). `damage_taken` applies party auras first, then the self multiplier. |
| `Effects`       | Shared building blocks for declarative effects: `Selector` (match a    |
|                 | room/boss/hero by tag/type/bait/preferred-bait) and `Aura` (flat /     |
|                 | per-point damage to matches). Used by all three effect interpreters.   |
| `BossEffect`    | Interprets a boss's **declarative** `effect` data: the per-point self   |
|                 | bonus and any room auras.                                              |
| `RoomEffect`    | Interprets a room's **declarative** `effect` data: room auras, party    |
|                 | hits, poison-on-hit, grow-on-death, unreducible, next-room damage,     |
|                 | draw-on-death, and discard-to-boost.                                  |
| `AbilityEffect` | Interprets an ability card's **declarative** `effect` data: add-damage, |
|                 | unreducible, zero, cancel-crawl, draw-rooms (and whether it needs a     |
|                 | room target).                                                          |
| `CrawlModifiers`| Per-crawl effects from ability cards / discard-to-boost (by room index):|
|                 | +damage, zero, set-to-N, unreducible, or retreat-at-room (Retreat).    |
| `DungeonSummary`| Read-only totals for a dungeon: total damage and bait icons by type. |
| `RandomAgent`   | An automated player: given a `Decision`, pick a random option (and    |
|                 | randomly skip when building is optional). Used to control opponents.  |
| `CrawlLog`      | *(web app only)* Appends a readable record of every crawl — dungeon,   |
|                 | party, per-room hits, deaths, grow/draw results — to a log file for   |
|                 | debugging. `Game` calls it when a logger is supplied; silent otherwise.|

## Phases (each orchestrates one step, nothing else)

Setup and Build are **player-driven**: the phase deals/draws the cards and then
the game waits for the player's choice (a `Decision`) before applying it. The
phase never picks for the player. Arrival, Draw, and Recharge run automatically.
The Crawl is automatic per party, but during each party's **Ability** step the
player may play ability cards or discard-to-boost a room (`CrawlModifiers`); the
Discard-phase discards can also be **undone** until the player finishes building.
On a quiet round (no party enters) players may play Blueprints, then continue.

A player may instead be controlled by an **agent** (e.g. `RandomAgent`). `Game`
is given a map of player → agent; decisions for an agent-controlled player are
resolved by the agent automatically and never surface to the human. The web app
uses this to make Player 2 a random computer opponent.

| Class          | Responsibility                                                        |
|----------------|-----------------------------------------------------------------------|
| `SetupPhase`   | Boss Selection + First Room Selection: deal opening hands and boss    |
|                | candidates; apply the boss choice (discarding the rest) and the       |
|                | first-room placement into **any of the 5 slots**.                     |
| `ArrivalPhase` | Draw one hero per living player into town, each at level `floor(round/4)`. (Automatic.) |
| `DiscardPhase` | Apply each player's choice to discard **0–2** room cards.             |
| `DrawPhase`    | Draw **1 + (cards discarded)** room cards for each player (cap 6). (Automatic.) |
| `BuildPhase`   | Apply the player's choice: place a room into any of the 5 slots       |
|                | (empty fills / occupied replaces), upgrade a room with a basic/       |
|                | advanced room card (bait + level), attach a dedicated upgrade, or skip. |
| `EnticePhase`  | *(was `BaitPhase`)* `target_for(party)`: the dungeon a party enters   |
|                | **right now** (most enticing by combined bait, and combined courage ≥ |
|                | that owner's current points), or nil to wait. Evaluated per party as  |
|                | points change, not all up front.                                      |
| `AbilityPhase` | **TODO** — the turn-based priority loop (play one ability or pass;    |
|                | any play re-opens to all players). Not built yet; the existing        |
|                | pre-Gauntlet ability/boost interaction stands in for now.             |
| `GauntletPhase`| *(was `CrawlPhase`)* Resolve a party crawl via `PartyCrawlResolver`;  |
|                | award a point per death and one wound per surviving party. (One party |
|                | at a time.)                                                           |
| `RechargePhase`| *(was `RecruitmentPhase`)* Each un-attacked player draws an ability   |
|                | card; consolidate **every waiting party** (each multi-hero party      |
|                | recruits a lone hero; remaining lones pair off, different bait        |
|                | preferred, odd one waits); **each survivor of a crawl gains +1 level**. |

## Encounter protocol

`PartyCrawlResolver` treats the dungeon as an ordered list of **encounters**. A
`Room` (via `PlacedRoom`) and a `Boss` both expose `damage`, `bait`, and `tags`,
so the resolver can apply them uniformly without knowing which is which.
`Dungeon#encounters` returns the occupied rooms in left→right order (skipping
empty slots) followed by the boss. (`CrawlResolver` is a legacy single-hero
resolver kept for reference; the game uses `PartyCrawlResolver`.)

## Data flow per round

```
SetupPhase       → deal hands + boss candidates → player Decisions (boss, first room into any slot)
ArrivalPhase     → town heroes (one per player), each at level floor(round/4)
DiscardPhase     → player Decisions (discard 0–2 rooms)
DrawPhase        → draw 1 + (#discarded) rooms each (cap 6)
BuildPhase       → player Decisions (place into any slot / upgrade a room / attach upgrade / skip)
Crawl            → for each party (town order): EnticePhase.target_for (current
                   points) → if it enters, Ability step (abilities/boost) →
                   GauntletPhase (PartyCrawlResolver) → points per death, one
                   wound per survivor → re-evaluate the next party
RechargePhase    → each un-attacked player draws an ability card; consolidate the
                   waiting parties; each crawl survivor gains +1 level
                   (a quiet round — nobody entered — first lets players play
                   Blueprints, then continues)
```

## Client parity (reference vs Android)

> **Rules overhaul (this branch).** The spec, `data/cards/`, and the **Android**
> client are being moved to the new rule set (Arcane bait, data-driven levelling
> heroes, the Arrival → Discard → Draw → Build → Crawl → Recharge sequence, the
> 5-slot dungeon, and room-card upgrades). The **web app is intentionally not
> being updated** for this overhaul, so the parity column below reflects the
> *previous* rule set for the systems that pre-date it.

The **Android client (`android/`)** carries a small interpreter for each
declarative effect (`Effects` `Selector`/`Aura`, `BossEffect`, `RoomEffect`,
`AbilityEffect`), the per-party Crawl loop, the pre-Gauntlet interaction layer
(ability cards, discard-to-boost, undo), advanced rooms, upgrades, quiet rounds,
and 2–4 players.

| Rule / system | Reference (webapp) | Android | Spec |
|---------------|--------------------|---------|------|
| Boss `effect`: per-point self bonus (+ Goblin Chieftain etc.) | ✅ | ✅ | [cards.md](cards.md) |
| `RoomEffect` (advanced-room crawl effects, declarative) | ✅ | ✅ | poison gas, antimagic, zealots, grow-on-death, trap/creature auras |
| `AbilityEffect` (ability cards, declarative) + pre-Gauntlet play | ✅ | ✅ | [phases.md](phases.md#7b-ability) |
| Advanced rooms: data + placement + `advanced_rooms` pool | ✅ | ✅ | [phases.md](phases.md#6-build) |
| Upgrades attaching to rooms | ✅ | ✅ | [cards.md](cards.md) |
| `CrawlModifiers` (discard-to-boost, ability effects) | ✅ | ✅ | — |
| Recharge consolidates **all** waiting parties (not just afraid) | ✅ | ✅ | [phases.md](phases.md#8-recharge) |
| Per-party Entice+Crawl (courage re-checked per party as points rise) | ✅ | ✅ | [phases.md](phases.md#7-crawl) |
| Quiet round lets players play Blueprints before continuing | ✅ | ✅ | [phases.md](phases.md#quiet-round-no-party-crawled) |
| Undo the discard | ✅ | ✅ | — |
| `tags` on every card (+ Goblin Chieftain uses them) | ✅ | ✅ | [cards.md](cards.md#tags) |
| 2–4 players (1 human + up to 3 computers) | ✅ | ✅ | [phases.md](phases.md) |
| **Arcane** bait (renamed from Power) | — | pass 2 | [cards.md](cards.md#bait) |
| **Data-driven levelling heroes** (HP/courage/reduction by level) | — | pass 2 | [cards.md](cards.md#hero-card) |
| **5-slot dungeon** + room-card upgrades (bait + level) | — | pass 2 | [phases.md](phases.md#6-build) |
| **Discard 0–2 / Draw 1+discards** | — | pass 2 | [phases.md](phases.md#4-discard) |

**Shared data, two copies.** Both clients read the card files in `data/cards/`
(`bosses.yaml`, `rooms.yaml`, `heroes.yaml`, `abilities.yaml`), but the Android
build keeps its own copy under `android/app/src/main/assets/cards.yaml` — a
single document that merges the same top-level keys. These must be kept **in
sync**; the canonical source is the split set of files under `data/`.

## Why this split

- A card never changes once loaded, so cards are immutable value objects.
- All "what happens" logic lives in phases and resolvers, so the rules can be
  read in one place and tested without a UI.
- The web app and Android app differ only in presentation and input; both drive
  the same phase sequence over the same model.
