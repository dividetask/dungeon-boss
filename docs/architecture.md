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
| `Hero`         | One hero card's data: id, name, health, preferred bait, courage, tags. |
| `AbilityCard`  | One ability card's data: id, name, text, `effect`, tags.              |

Every card carries an optional `tags` set (see [cards.md](cards.md#tags)).
`ability_text` on bosses/rooms/heroes is flavor; the mechanical behaviour is
keyed by `effect` (bosses/rooms/ability cards) or by hero `id` (`HeroAbility`).

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
| `Dungeon`  | One player's boss plus ordered rooms (entrance = leftmost; max 5). Adds   |
|            | rooms to the left or replaces an existing room; yields encounters in      |
|            | crawl order (rooms left→right, then boss).                                |
| `Player`   | A participant: their dungeon, hand of room cards, points, and wounds.     |
| `Party`    | One or more heroes who bait, crawl, and score together (a lone hero is a  |
|            | party of one). Holds combined courage, board order, and a generated name. |
| `PlacedRoom` | A room as it sits in a dungeon: a base `Room` plus at most one          |
|            | `Upgrade`; exposes effective damage and bait.                             |
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
| `HeroAbility`   | Per-hero damage modifiers — `:party` auras (protect any member) and     |
|                 | `:self` (protect only the owner). `damage_taken` combines them.         |
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
|                 | randomly skip when building is optional). A simple opponent / baseline. |
| `DungeonForecast` | Forecast how a set of parties would fare crawling a dungeon: heroes |
|                 | killed (points), parties that survive (wounds), and damage dealt. Runs  |
|                 | each crawl against a clone, so the real dungeon is never mutated.     |
| `LogicAgent`    | An automated player driven by the declarative heuristics in            |
|                 | `data/ai_logic.yaml`: scores a `Decision`'s candidates with the         |
|                 | configured tie-break chain (static card comparators + `DungeonForecast` |
|                 | simulations). The default web-app opponent. See [ai.md](ai.md).        |
| `CrawlLog`      | *(web app only)* Appends a readable record of every crawl — dungeon,   |
|                 | party, per-room hits, deaths, grow/draw results — to a log file for   |
|                 | debugging. `Game` calls it when a logger is supplied; silent otherwise.|

## Phases (each orchestrates one step, nothing else)

Setup and Build are **player-driven**: the phase deals/draws the cards and then
the game waits for the player's choice (a `Decision`) before applying it. The
phase never picks for the player. Arrival and Recruitment run automatically.
Bait+Crawl is automatic per party, but **before each crawl** the player may play
ability cards or discard-to-boost a room (`CrawlModifiers`); the build-phase
discard can also be **undone** until the player finishes building. On a quiet
round (no party enters) players may play Blueprints, then continue.

A player may instead be controlled by an **agent** (`RandomAgent` for a random
baseline, or `LogicAgent` for the heuristic opponent — see [ai.md](ai.md)).
`Game` is given a map of player → agent; decisions for an agent-controlled player
are resolved by the agent automatically and never surface to the human. The web
app uses this to make Players 2…N computer opponents.

| Class          | Responsibility                                                        |
|----------------|-----------------------------------------------------------------------|
| `SetupPhase`   | Deal opening hands and boss candidates; apply the player's boss       |
|                | choice (discarding the rest) and first-room placement.                |
| `ArrivalPhase` | Draw one hero per player into town. (Automatic.)                      |
| `BuildPhase`   | Draw a room for each player; apply the player's choice to place one   |
|                | room to the left, or to build nothing.                                |
| `BaitPhase`    | `target_for(party)`: the dungeon a party enters **right now** (most   |
|                | enticing by combined bait, and combined courage ≥ that owner's        |
|                | current points), or nil to wait. Bait is combined with Crawl — the    |
|                | game asks per party as points change, not all up front.              |
| `CrawlPhase`   | Resolve a party crawl via `PartyCrawlResolver`; award a point per     |
|                | death and one wound per surviving party. (One party at a time; the    |
|                | player may act before each crawl — see below.)                       |
| `RecruitmentPhase` | After the crawl, consolidate **every waiting party** (unenticed    |
|                | or afraid): each multi-hero party recruits a lone hero; remaining    |
|                | lone heroes pair off (different bait preferred). An odd one waits.   |

## Encounter protocol

`PartyCrawlResolver` treats the dungeon as an ordered list of **encounters**. A
`Room` (via `PlacedRoom`) and a `Boss` both expose `damage`, `bait`, and `tags`,
so the resolver can apply them uniformly without knowing which is which.
`Dungeon#encounters` returns the rooms in left→right order followed by the boss.
(`CrawlResolver` is a legacy single-hero resolver kept for reference; the game
uses `PartyCrawlResolver`.)

## Data flow per round

```
SetupPhase       → deal hands + boss candidates → player Decisions (boss, room)
ArrivalPhase     → town heroes (one per player)
BuildPhase       → draw two rooms each → player Decisions (discard one; place/skip)
Bait+Crawl       → for each party (town order): BaitPhase.target_for (current
                   points) → if it enters, (pre-crawl: abilities/boost) →
                   PartyCrawlResolver → points per death, one wound per survivor
                   → re-evaluate the next party
RecruitmentPhase → consolidate the waiting parties for next round
                   (a quiet round — nobody entered — first lets players play
                   Blueprints, then grants each player an ability card)
```

## Client parity (reference vs Android)

The **web app (`webapp/`) is the reference implementation** — every rule in
these docs is implemented and unit-tested there. The **Android client
(`android/`) now implements the same rule set**: it carries a small interpreter
for each declarative effect (`Effects` `Selector`/`Aura`, `BossEffect`,
`RoomEffect`, `AbilityEffect`), the combined Bait+Crawl loop, the pre-crawl
interaction layer (ability cards, discard-to-boost, undo), advanced rooms,
upgrades, quiet rounds, and 2–4 players. The list below tracks the systems that
were brought across; all are now implemented in both clients.

| Rule / system | Reference (webapp) | Android | Spec |
|---------------|--------------------|---------|------|
| Boss `effect`: per-point self bonus (+ Goblin Chieftain etc.) | ✅ | ✅ | [cards.md](cards.md) |
| `RoomEffect` (advanced-room crawl effects, declarative) | ✅ | ✅ | poison gas, antimagic, zealots, grow-on-death, trap/creature auras |
| `AbilityEffect` (ability cards, declarative) + pre-crawl play | ✅ | ✅ | [phases.md](phases.md#5-crawl-phase) |
| Advanced rooms: data + placement + `advanced_rooms` pool | ✅ | ✅ | [phases.md](phases.md#3-build-phase) |
| Upgrades attaching to rooms | ✅ | ✅ | [cards.md](cards.md) |
| `CrawlModifiers` (discard-to-boost, ability effects) | ✅ | ✅ | — |
| Recruitment consolidates **all** waiting parties (not just afraid) | ✅ | ✅ | [phases.md](phases.md#5-recruitment-phase) |
| Bait+Crawl combined (courage re-checked per party as points rise) | ✅ | ✅ | [phases.md](phases.md#4-bait--crawl-phase) |
| Quiet round lets players play Blueprints before continuing | ✅ | ✅ | [phases.md](phases.md#quiet-round-no-hero-attacks) |
| Undo the build-phase discard | ✅ | ✅ | — |
| `tags` on every card (+ Goblin Chieftain uses them) | ✅ | ✅ | [cards.md](cards.md#tags) |
| 2–4 players (1 human + up to 3 computers) | ✅ | ✅ | [phases.md](phases.md) |

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
