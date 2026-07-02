# Cards

All cards are defined in the [`data/cards/`](../data/cards) directory — one file
per category: `bosses.yaml`, `rooms.yaml` (rooms + advanced rooms),
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
`goblin` (or `hobgoblin`) in its dungeon — so tagging a new room `goblin`
automatically makes the Chieftain buff it, with no code change. Tags also make a
boss's synergy pool a **fixed, curated set** rather than a bait query: because a
room card spent to upgrade another room grants its **bait icons** but not its
**tags**, a tag-matched aura (Lich's `arcane`, Necromancer's `undead`, Oni's
`monstrous_humanoid`) can't be widened by upgrading an unrelated room to carry
the bait. The current classification tags:

| Tag | On | Boss that keys off it |
|-----|----|-----------------------|
| `arcane` | the six arcane **trap** rooms (Fireball, Power Word, Soul Leach, Antimagic, Black Tentacles, Maze) | **Lich** (`type: trap` + `tag: arcane`) |
| `undead` | the six undead **creatures** (Skeletons, Zombies, Shade, Zealots, Shadow, Wright) — also on the Undead Hands trap | **Necromancer** (`type: creature` + `tag: undead`) |
| `monstrous_humanoid` | the humanoid glory creatures (Goblins, Hobgoblin Champion, Gladiator, Troll, Hobgoblin Beastmaster) | **Oni** (+4 flat) |
| `goblin` / `hobgoblin` | Goblins / the two Hobgoblin rooms | **Goblin Chieftain** (+1/point) |

```yaml
tags: [goblin, monster]
```

## Card types and fields

### Boss card

| Field         | Type            | v1 | Notes                                  |
|---------------|-----------------|----|----------------------------------------|
| `id`          | string          | ✅ | Unique identifier                      |
| `name`        | string          | ✅ | Display name                           |
| `lead_damage` | integer ≥ 0     | ✅ | Damage dealt to the lead hero as the final encounter |
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
  unreducible: true             # the boss's final gaze ignores all hero/party
                                #   reductions (optional; Medusa)
  room_bonuses:                 # extra damage granted to matching rooms
    - match: { tag: goblin }    #   a room matches when it satisfies EVERY
      per_point: 1              #   dimension given under `match`
    - match: { type: trap }
      flat: 2
```

- **`self_damage_per_point`** — the boss's final-encounter damage rises by this
  much per point its owner has scored. Every boss has it; **default 1**.
- **`unreducible`** — when `true`, the boss's own final-encounter damage cannot
  be reduced by any hero self-multiplier or party aura (the same flag rooms use).
  Optional; absent means the attack reduces normally.
- **`room_bonuses`** — a list of auras. Each grants `flat` damage and/or
  `per_point × points` damage to every room in the dungeon that matches.
- **`match`** dimensions (a room must satisfy all that are present):
  - `tag` — the room carries this tag.
  - `type` — `trap` (the room's type names a trap) or `creature`/`monster`
    (its type names a monster/creature).
  - `bait` — the room has at least one icon of this bait type.

The eight bosses: **Lich** (+1/point to `type: trap` + `tag: arcane` rooms),
**Oni** (+4 flat to `monstrous_humanoid`-tagged rooms — the humanoid glory
creatures), **Vampire**
(`self_damage_per_point: 2`), **Medusa** (`unreducible: true` — her final gaze
ignores all hero/party reductions), **Goblin Chieftain** (+1/point to `goblin`-
and `hobgoblin`-tagged rooms), **Malevolent Spirit** (`self_damage_per_point:
3`), **Kobold Chieftain** (+2 flat to trap rooms), and **Necromancer** (+2 flat
**plus +1/point** to undead creatures, i.e. `type: creature` + `tag: undead`).
Lich, Necromancer
and Oni match on a **tag** (not a bait query) so upgrading an unrelated room to
carry the bait cannot pull it into their aura.

### Room card (flat field schema)

Rooms use a **flat field schema** — every behaviour is its own field, read
directly by both clients (there is no nested `effect` block for rooms). There are
no Upgrade cards. A room deals up to three **damage channels**, each scaled by the
room's level: `value = base + floor(increment × level)`.

| Field                    | Type          | Req | Notes                                                          |
|--------------------------|---------------|-----|----------------------------------------------------------------|
| `name`                   | string        | ✅  | Display name                                                   |
| `type`                   | string        | ✅  | `trap` or `monster`                                            |
| `advanced`               | boolean       | ✅  | `false` (basic) or `true` (advanced room)                     |
| `bait`                   | map<bait,int> | ✅  | Bait icons by type                                            |
| `lead_damage`            | integer ≥ 0   | ✅  | Damage to the highest-HP hero; overkill cascades to the next  |
| `lead_damage_increment`  | float         | ✅  | Extra lead damage per room level                              |
| `damage_all`             | integer ≥ 0   | ❌  | Damage dealt to every hero                                    |
| `damage_all_increment`   | float         | ❌  | Extra all-damage per level                                    |
| `damage_rear`            | integer ≥ 0   | ❌  | Damage to the lowest-HP (most injured) hero; cascades upward  |
| `damage_rear_increment`  | float         | ❌  | Extra rear damage per level                                   |
| `damage_filter`          | string        | ❌  | `mage`/`cleric`/`rogue`/`barbarian` — gates `damage_all` to that class |
| `room_resist`            | bool/null     | ❌  | `null` normal, `false` cannot be halved (Barbarian), `true` cannot be reduced |
| `discard_lead_damage`    | integer       | ❌  | Per card discarded during the crawl: +N lead damage (stacks; temporary) |
| `discard_all_damage`     | integer       | ❌  | Same, but +N all-damage                                       |
| `poison_damage`          | integer       | ❌  | Damage to every hero this room damaged, in a later room (unreducible) |
| `poison_persists`        | boolean       | ❌  | `false` = next room only; `true` = every later room          |
| `poison_ticks`           | integer       | ❌  | Times poison resolves AT this room (default 1; Maze = 3)      |
| `grows_on_death`         | boolean       | ❌  | Each hero death here raises this room's level by 1            |
| `draw_on_death`          | boolean       | ❌  | Owner draws one room + one ability card per hero that dies here |
| `room_aura`              | map           | ❌  | `{ match: {...}, amount: N }` — +N to every other matching room |
| `tags`                   | array<string> | ❌  | Classification tags                                          |
| `advanced`/`copies`      | bool/int      | ❌  | `advanced` set from the `advanced_rooms` section; `copies` defaults to 1 |

Poison is always unreducible. `room_resist` applies to the room's own damage
channels (not poison). A room's **level** starts at 0 and rises by 1 when
`grows_on_death` triggers, and by 1 (basic) / 2 (advanced) when a room card is
spent to upgrade it during Build (granting its bait icons too).

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
| `starting_hp`    | integer > 0   | Health at level 1 (the base)                                 |
| `starting_courage` | integer ≥ 1 | Courage at level 1 (per-class base; default 1)             |
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

A hero carries a **level** that starts at `floor(round / 4) + 1` when it arrives
— so the **minimum level is 1, never 0** — gains **+1 every time it survives a
crawl**, and persists until the hero dies (each hero tracks its own level). The
three derived stats use **`(level - 1)`**, so a **level-1 hero has its base
stats** and each level beyond adds one increment:

```
max_hp           = starting_hp + floor((level - 1) * hp_level_increment)
courage          = starting_courage + (level - 1)      # base at L1, +1 per level after
party_reduction  = party_damage_reduction + floor((level - 1) * party_damage_reduction_level_increment)
```

So e.g. the Mage's party reduction (base `4`, `+2`/level) is `4` at L1, `6` at
L2, `8` at L3 — i.e. `level * 2 + 2`. Because `hp_level_increment` is a float
**floored after multiplying by `(level - 1)`**, classes with a small increment
(e.g. the Mage's `0.05`) gain HP only occasionally, while a Barbarian (`2`) gains
HP every level. HP itself is always an **integer**; only the increments are
floats. **Courage** is the per-class `starting_courage` at L1 and rises by **1
every level** after. Heroes are restored to their current (levelled) **full HP
between crawls**.

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

| Hero | bait | start HP | courage | hp/lvl | self× | party− | −/lvl | bait filter | type filter |
|------|------|----------|---------|--------|-------|--------|-------|-------------|-------------|
| Barbarian | glory | 8 | 2 | 2 | 0.5 | 0 | 0 | — | — |
| Rogue | riches | 6 | 1 | 1 | 1 | 2 | 1 | — | trap |
| Cleric | undead | 5 | 2 | 0.75 | 1 | 4 | 1 | undead | — |
| Mage | arcane | 4 | 1 | 0.05 | 1 | 4 | 2 | arcane | — |

### Room level (upgrading a room with a room card)

There are **no Upgrade cards**. Instead, during the [Build phase](phases.md)
a player may **spend a basic or advanced room card from hand to upgrade a placed
room** instead of placing it. Doing so:

- adds **every bait icon** of the spent card to the target room, and
- raises the target room's **level** by **1** (basic) or **2** (advanced).

A placed room carries an integer **`level`** (starting at 0). The same level also
rises by 1 each time `grows_on_death` triggers. Every damage channel scales with
it: `value = base + floor(increment × level)`. The spent card is discarded.

Separately, a **crawl-time discard boost** (`discard_lead_damage` /
`discard_all_damage`) lets the owner discard a card *during* the crawl to add
that much to the room's damage for that crawl only — it stacks per card and does
**not** change the room's level or bait.

### Advanced room

A stronger room. It may be played by **replacing an existing room that shares at
least one of the advanced room's bait icons** — e.g. Antimagic *(arcane, arcane)*
may replace any room that has at least one arcane icon; the replaced room is
discarded. Advanced rooms use the **same flat field schema** as basic rooms (they
are loaded with `advanced: true`) and start the game seeded into the **discard
pile**, so they cannot appear in an opening hand — they enter circulation only
after the first reshuffle.

The twelve advanced rooms (×2 each): **Antimagic Room** (`damage_all` 4, filter
mage, can't reduce), **Zealots** (filter cleric), **False Trigger** (filter
rogue), **Gladiator** (lead 4, grows, can't reduce), **Troll** (lead 10 +4/level,
grows), **Shadow** / **Wright** (lead 6, grows, can't be halved), **Cursed Ring**
(lead 2, can't reduce, poison 2/room), **Black Tentacles** (`damage_all` 1 +
`damage_rear` 5), **Maze** (`damage_all` 4, `poison_ticks` 3), **Trap Makers
Workshop** / **Beast Tamer** (`room_aura` +2 to other traps / creatures).

A `room_aura` never affects the granting room or the boss; it applies to every
other room that matches. The boss `room_bonuses` and a room `room_aura` add to a
target room's **primary** damage channel (the first of lead / all / rear it uses).

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
`rooms` / `advanced_rooms` (rooms.yaml), `heroes` (heroes.yaml), and
`ability_cards` (abilities.yaml). The loader merges them. (Advanced rooms live
under their own key and are loaded with `advanced: true`; they share the build
deck with rooms but seed the discard pile so they can't be in an opening hand.)

```yaml
bosses:
  - id: boss_goblin_chieftain
    name: Goblin Chieftain
    lead_damage: 4
    bait: { glory: 1, riches: 1 }
    effect:                    # bosses keep a declarative effect block
      room_bonuses:
        - match: { tag: goblin }
          per_point: 1         # +1 damage per owner point to goblin-tagged rooms
    tags: [goblin]
    ability_text: "Goblin rooms deal +1 damage per point."

rooms:                         # flat field schema — no nested effect, no upgrades
  - id: room_goblins
    name: Goblins
    type: monster
    advanced: false
    bait: { glory: 1 }
    lead_damage: 3
    lead_damage_increment: 1
    tags: [goblin]             # the Goblin Chieftain boosts this room
    copies: 4

advanced_rooms:
  - id: adv_poison_gas_example
    name: Poison Gas
    type: trap
    advanced: true
    bait: { glory: 1 }
    damage_all: 2
    damage_all_increment: 1
    poison_damage: 1
    poison_persists: true
    copies: 2

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
