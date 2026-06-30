# Cards

All cards are defined in the [`data/cards/`](../data/cards) directory — one file
per category: `bosses.yaml`, `rooms.yaml` (rooms + upgrades + advanced rooms),
`heroes.yaml`, and `abilities.yaml`. A client loads every file in the directory
and merges their top-level keys. This document describes the card types, their
fields, the bait system, and the YAML schema.

## Bait

There are exactly four bait types:

| Bait     | Theme                          |
|----------|--------------------------------|
| `glory`  | Fame, honor, renown            |
| `riches` | Gold, treasure, loot           |
| `undead`  | The macabre, necromancy        |
| `arcane` | Arcane might, domination       |

- **Rooms** and **bosses** carry **bait icons** — a count per bait type.
- **Heroes** have a single **preferred bait**.
- During the [Entice step](phases.md#7a-entice), a hero is lured by the count
  of its *preferred* bait across a dungeon.

Bait icons are represented as a map from bait type to a non-negative integer
count. A bait type omitted from the map counts as `0`.

## Tags

**Every** card type may carry a `tags` field: an **array of strings** that
classify the card (e.g. `goblin`, `undead`). A card can have **any number** of
tags, and the field is optional (omitted means no tags). Tags are normalized to
lowercase and de-duplicated; matching is case-insensitive.

Tags let an effect target a whole group of cards at once instead of naming each
one. For example, the **Goblin Chieftain** boss boosts every card tagged
`goblin` in its dungeon — so tagging a new room `goblin` automatically makes the
Chieftain buff it, with no code change. When an upgrade is attached to a room,
the placed room's tags are the **union** of the room's and the upgrade's tags.

```yaml
tags: [goblin, monster]
```

## Card types and fields

### Boss card

| Field         | Type            | v1 | Notes                                  |
|---------------|-----------------|----|----------------------------------------|
| `id`          | string          | ✅ | Unique identifier                      |
| `name`        | string          | ✅ | Display name                           |
| `damage`      | integer ≥ 0     | ✅ | Damage dealt as the final encounter    |
| `bait`        | map<bait,int>   | ✅ | Bait icons by type                     |
| `effect`      | map             | ✅ | **Declarative** effect spec (see below); omit for none |
| `tags`        | array<string>   | ✅ | Classification tags (optional)         |
| `ability_text`| string          | ❌ | Human-readable flavor; the mechanics live in `effect` |

Setup deals **two boss candidates per player**, so the boss pool must hold at
least `2 × player count` cards. There are **8 bosses** (one card each), which
covers up to **4 players** (8 candidates).

#### Boss `effect` schema

A boss `effect` is **data, not a code key** — the bonus amounts and what they
target are written directly in the YAML so both clients read the same
definition. The shape:

```yaml
effect:
  self_damage_per_point: 3      # the boss's OWN bonus damage per owner point
                                #   (optional; default 1 — every boss has this)
  room_bonuses:                 # extra damage granted to matching rooms
    - match: { tag: goblin }    #   a room matches when it satisfies EVERY
      per_point: 1              #   dimension given under `match`
    - match: { type: trap }
      flat: 2
```

- **`self_damage_per_point`** — the boss's final-encounter damage rises by this
  much per point its owner has scored. Every boss has it; **default 1**.
- **`room_bonuses`** — a list of auras. Each grants `flat` damage and/or
  `per_point × points` damage to every room in the dungeon that matches.
- **`match`** dimensions (a room must satisfy all that are present):
  - `tag` — the room carries this tag.
  - `type` — `trap` (the room's type names a trap) or `creature`/`monster`
    (its type names a monster/creature).
  - `bait` — the room has at least one icon of this bait type.

The eight bosses: **Lich**, **Oni**, **Vampire**, **Medusa** (no effect),
**Goblin Chieftain** (+1/point to `goblin`-tagged rooms), **Malevolent Spirit**
(`self_damage_per_point: 3`), **Kobold Chieftain** (+2 flat to trap rooms), and
**Necromancer** (+2 flat to undead creatures, i.e. `type: creature` + `bait:
undead`).

### Room card

| Field         | Type            | v1 | Notes                                       |
|---------------|-----------------|----|---------------------------------------------|
| `id`          | string          | ✅ | Unique identifier                           |
| `name`        | string          | ✅ | Display name                                |
| `type`        | string          | ✅ | Room type (e.g. `Basic Trap`, `Basic Monster`) |
| `damage`      | integer ≥ 0     | ✅ | Damage dealt when the hero enters           |
| `bait`        | map<bait,int>   | ✅ | Bait icons by type                          |
| `description` | string          | ✅ | Flavor text shown on the card               |
| `effect`      | string          | ✅ | Crawl behaviour key (advanced rooms; blank for basic) |
| `tags`        | array<string>   | ✅ | Classification tags (optional)              |
| `advanced`    | boolean         | ✅ | Set on advanced rooms (loaded from the `advanced_rooms` section) |
| `copies`      | integer ≥ 1     | ✅ | How many of this card are in the deck (default 1) |
| `ability_text`| string          | ❌ | Flavor; mechanical behaviour lives in `effect` |

### Hero card

A hero's mechanical behaviour is **fully data-driven** — there is no per-hero
code. Every field below is read from the YAML, so a hero can be retuned (or a new
hero added) by editing data alone.

| Field            | Type          | Notes                                                         |
|------------------|---------------|---------------------------------------------------------------|
| `id`             | string        | Unique identifier                                             |
| `name`           | string        | Display name                                                  |
| `icon`           | string        | Display icon (emoji or asset key)                            |
| `preferred_bait` | bait          | One of the four bait types — the hero's lure ("Bait")        |
| `starting_hp`    | integer > 0   | Health at level 0                                            |
| `hp_level_increment` | float     | HP gained per level (floored — see formula); may be < 1      |
| `self_damage_multiplier` | float | Multiplies the damage **this hero** personally takes (self-scope) |
| `party_damage_reduction` | integer ≥ 0 | Flat party-wide damage reduction at level 0 (the aura)   |
| `party_damage_reduction_level_increment` | number | Added to the reduction per level (floored)      |
| `damage_bait_filter` | bait \| null | If set, the party reduction only applies to encounters carrying this bait |
| `damage_room_type_filter` | type \| null | If set, the party reduction only applies to rooms of this type (`trap`/`creature`) |
| `tags`           | array<string> | Classification tags (optional)                              |
| `copies`         | integer ≥ 1   | How many of this hero are in the deck (default 1)           |
| `ability_text`   | string        | Optional flavor only; the mechanics live in the fields above |

#### Levelling and derived stats

A hero carries a **level** that starts at `floor(round / 4)` when it arrives,
gains **+1 every time it survives a crawl**, and persists until the hero dies
(each hero tracks its own level). From the level, three stats are derived:

```
max_hp           = starting_hp + floor(level * hp_level_increment)
courage          = 1 + level                          # uniform base 1, +1 per level (interpretation)
party_reduction  = party_damage_reduction + floor(level * party_damage_reduction_level_increment)
```

Because `hp_level_increment` is a float that is **floored after multiplying by
level**, classes with a small increment (e.g. the Mage's `0.05`) gain HP only
occasionally, while a Barbarian (`2`) gains HP every level. HP itself is always
an **integer**; only the increments are floats. Heroes are restored to their
current (levelled) **full HP between crawls**.

> **(interpretation)** The new hero schema does not carry an explicit courage
> value, so courage uses a uniform base of `1` plus the hero's level. If a
> per-class base is wanted later, add a `base_courage` field.

#### How the damage fields combine

The two reduction fields play the same roles the hard-coded hero abilities used
to, only now as data:

- **`party_damage_reduction`** is a **party-wide aura**: it reduces the damage
  dealt to **whichever** member is hit, by `party_reduction` (the levelled
  value), but **only** for encounters that pass the filters:
  - `damage_bait_filter` — the encounter (room or boss) carries that bait icon.
  - `damage_room_type_filter` — the encounter is a room of that type (the boss is
    not a room).
  - When **both** filters are set, the reduction applies only when **both**
    match; when **neither** is set, it applies to **every** encounter. A null
    filter is simply ignored.
  The aura applies only while the granting hero is **alive** in the party, and
  party auras from multiple members **stack**.
- **`self_damage_multiplier`** is **self-scope**: it multiplies the damage **the
  hero itself** takes (rounded **up**), unconditionally — no filter. A value of
  `1` means no change; the Barbarian's `0.5` halves his incoming damage,
  rounded up.

Order of application matches the old rules: **party auras first, then the
target's own self-multiplier last**; damage never goes below 0.

The current four heroes expressed this way:

| Hero | bait | start HP | hp/lvl | self× | party− | −/lvl | bait filter | type filter |
|------|------|----------|--------|-------|--------|-------|-------------|-------------|
| Barbarian | glory | 8 | 2 | 0.5 | 0 | 0 | — | — |
| Rogue | riches | 6 | 1 | 1 | 2 | 1 | — | trap |
| Cleric | undead | 5 | 0.75 | 1 | 4 | 1 | undead | — |
| Mage | arcane | 4 | 0.05 | 1 | 4 | 2 | arcane | — |

### Upgrade card

A room-card type that shares the build deck with rooms but, instead of being
placed, **attaches to an existing room** to boost it. Each room holds **at most
one** upgrade; applying another replaces it, and replacing the room loses the
upgrade. Upgrades are rarer than rooms — about **1 upgrade per 3 room cards**.

| Field          | Type          | v1 | Notes                                  |
|----------------|---------------|----|----------------------------------------|
| `id`           | string        | ✅ | Unique identifier                      |
| `name`         | string        | ✅ | Display name                           |
| `bonus_damage` | integer ≥ 0   | ✅ | Added to the room's damage             |
| `bait`         | map<bait,int> | ✅ | Bait icons added to the room           |
| `description`  | string        | ✅ | Flavor / effect text                   |
| `tags`         | array<string> | ✅ | Tags merged onto the upgraded room (optional) |

Current upgrades, one per bait type plus a damage upgrade:

- **Glory Banner** — +1 glory bait
- **Treasure Pile** — +1 riches bait
- **Cursed Idol** — +1 undead bait **and +2 damage**
- **Arcane Sigil** — +1 arcane bait **and +2 damage**
- **Reinforced Walls** — +3 damage

### Room level (upgrading a room with a room card)

Separately from the dedicated Upgrade cards above, during the
[Build phase](phases.md#6-build) a player may **spend a basic or advanced room
card from hand to upgrade a placed room** instead of placing it. Doing so:

- adds **every bait icon** of the spent card to the target room, and
- raises the target room's **level** by **1** (basic room) or **2** (advanced
  room).

A placed room therefore carries an integer **`level`** (starting at 0) that
accumulates as it is upgraded. The spent card is discarded.

> **(out of scope here)** The *gameplay effect* of a room's level is being
> implemented on a separate branch. This change only records the level and the
> granted bait icons; nothing in the crawl reads `level` yet.

### Advanced room

A stronger room with a special **effect**. It may be played by **replacing an
existing room that shares at least one of the advanced room's bait icons** —
e.g. Antimagic *(arcane, arcane)* may replace any room that has at least one
arcane icon. The replaced room and any upgrade on it are discarded. Advanced rooms are
about as common as upgrades (4 basic : 1 upgrade : 1 advanced).

| Field        | Type          | Notes                                          |
|--------------|---------------|------------------------------------------------|
| `type`       | string        | `Creature` or `Trap`                           |
| `damage`     | integer ≥ 0   | single-target damage (some are 0; see effect)  |
| `bait`       | map<bait,int> | the room's bait **and** its placement requirement |
| `effect`     | map           | **declarative** effect spec (see below)        |
| `tags`       | array<string> | classification tags (optional)                 |

#### Room `effect` schema

Like boss effects, a room `effect` is **data, not a code key** — both clients
read the same definition. Each key is optional:

```yaml
effect:
  room_auras:                       # +damage to OTHER matching rooms
    - match: { type: trap }         #   (selector: tag / type / bait)
      flat: 2                        #   flat and/or per_point × owner points
  party_hits:                       # unreducible hits on matching members
    - match: { preferred_bait: arcane }  #   (selector: preferred_bait / tag)
      amount: 4
  poisons_on_hit: true              # the hero this room damages is poisoned
  grows_on_death: true              # +1 damage permanently per death here
  unreducible: true                 # this room's single-target damage can't be reduced
  next_room_damage: 8               # the hero it hits takes N more (unreducible) in the NEXT room
  draw_on_death: ability            # owner draws an "ability" / "room" card per death here
  discard_boost: { add_damage: 4 }  # owner may discard a card to add +N to this
  # discard_boost: { unreducible: true } #   room (on top of upgrades), or make
  # discard_boost: { set_damage: 6 }     #   it unreducible, or override its damage
```

`discard_boost` supports `add_damage` (stacks on the room's printed + upgraded
damage), `set_damage` (overrides it), and/or `unreducible`.

`unreducible: true` makes the room's own single-target hit ignore every hero
damage-reduction ability (the room equivalent of Expose Weakness). `next_room_damage`
marks the hero this room hits so it takes that many unreducible points as it enters
the very next room (a one-shot delayed hit that then clears). `draw_on_death`
(`ability` or `room`) gives the room's owner one card from that deck for each hero
that dies in the room — drawn after the crawl resolves.

Effects are not exclusive to advanced rooms: a few **basic** rooms now carry one
too — **Poisoned Spikes** (`next_room_damage: 8`), **Champion's Arena**
(`unreducible: true`), **Soul Siphon** (`draw_on_death: ability`), and
**Unhallowed Ground** (`draw_on_death: room`).

The eleven advanced rooms expressed with this schema:

- **Succubus**, **Plague Zombie** — `grows_on_death: true`.
- **Poison Gas** — `poisons_on_hit: true` (deals its single-target damage, then
  the hero it hit loses 1 unreducible health at every later room).
- **Antimagic Room** — `party_hits` matching `preferred_bait: arcane`, amount 4.
- **Zealots** — `party_hits` matching `preferred_bait: undead`, amount 4.
- **Trap Makers Workshop** — `room_auras` matching `type: trap`, flat 2.
- **Beast Tamer** — `room_auras` matching `type: creature`, flat 2.
- **Collapsing Tunnel**, **Golem**, **Necrotic Fog** — `discard_boost: { add_damage: 4 }`.
- **Troll** — `discard_boost: { unreducible: true }`.

Room auras never affect the granting room or the boss; they apply to every
other room that matches.

### Ability card

Held in hand and **played before a crawl** to alter it (see
[phases.md](phases.md)). Each is one-shot (discarded after use).

| Field    | Type          | v1 | Notes                                  |
|----------|---------------|----|----------------------------------------|
| `id`     | string        | ✅ | Unique identifier                      |
| `name`   | string        | ✅ | Display name                           |
| `text`   | string        | ✅ | Rules text shown to players            |
| `effect` | map           | ✅ | **declarative** effect spec (see below) |
| `tags`   | array<string> | ✅ | Classification tags (optional)         |

#### Ability `effect` schema

Also data. Keys (each optional); `add_damage`, `unreducible`, and `zero` act on
a chosen room, so those cards need a room target — the others do not:

```yaml
effect:
  add_damage: 2        # +N to the targeted room this crawl  (Reinforcements)
  unreducible: true    # the targeted room can't be reduced  (Expose Weakness)
  zero: true           # the targeted room deals 0           (Sabotage)
  retreat: true        # party turns back at the targeted room (Retreat)
  draw_rooms: 2        # the player draws N room cards        (Blueprints)
```

`add_damage`, `unreducible`, `zero`, and `retreat` need a **room target**;
`draw_rooms` does not. **Retreat** makes the party turn back **at** the chosen
room: every room **before** it still resolves (so deaths there still score the
owner points), but the chosen room and everything after — including the boss —
are skipped, and the owner takes **no wound** (the survivors escaped). Targeting
the entrance (room 0) is a full retreat with no crawl at all.

The five ability cards: **Reinforcements** (`add_damage: 2`), **Expose
Weakness** (`unreducible: true`), **Sabotage** (`zero: true`), **Retreat**
(`retreat: true`), **Blueprints** (`draw_rooms: 2`).

## YAML schema

The card files in `data/cards/` use these top-level keys: `bosses` (bosses.yaml),
`rooms` / `upgrades` / `advanced_rooms` (rooms.yaml), `heroes` (heroes.yaml), and
`ability_cards` (abilities.yaml). The loader merges them. (Advanced rooms live
under their own key and are loaded with `advanced: true`; they share the build
deck with rooms and upgrades.)

```yaml
bosses:
  - id: boss_goblin_chieftain
    name: Goblin Chieftain
    damage: 4
    bait: { glory: 1, riches: 1 }
    effect:                    # declarative — the bonus lives in the data
      room_bonuses:
        - match: { tag: goblin }
          per_point: 1         # +1 damage per owner point to goblin-tagged rooms
    tags: [goblin]
    ability_text: "Goblin rooms deal +1 damage per point."

rooms:
  - id: room_goblins
    name: Goblins
    type: Basic Monster
    damage: 1
    bait: { glory: 1 }
    tags: [goblin]             # the Goblin Chieftain boosts this room
    description: "Goblins ambush you as you wander into the room"
    copies: 24

advanced_rooms:
  - id: adv_poison_gas
    name: Poison Gas
    type: Trap
    damage: 2
    bait: { riches: 1 }
    effect:
      poisons_on_hit: true
    copies: 11

heroes:
  - id: hero_barbarian
    name: Barbarian
    health: 12
    preferred_bait: glory
    courage: 2
    ability_text: "Halves the damage he takes."

ability_cards:
  - id: ability_reinforcements
    name: Reinforcements
    text: "+2 damage to a room this crawl."
    effect:
      add_damage: 2
```

### Validation rules

- Every `id` is unique within its category.
- Every key under a `bait` map must be one of the four bait types.
- `preferred_bait` must be one of the four bait types.
- All `damage` values are integers ≥ 0; `health` is an integer > 0.
- Omitted `bait` map means no bait icons; omitted `tags` means no tags.
- `tags` is a list of strings; they are lowercased and de-duplicated on load.
- `copies` defaults to 1 when omitted; the loader puts that many identical
  cards of the definition into the deck.
