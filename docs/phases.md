# Phases

Dungeon Boss proceeds through these phases: **Setup** (once at the start of the
game), then **Arrival → Build → Bait+Crawl → Recruitment** repeating each
round.

Each phase is implemented as its own class with a single responsibility — see
[architecture.md](architecture.md).

---

## 1. Setup Phase

Performed once, at the start of the game. For each player:

1. Deal a starting hand of **four** cards from the build deck (rooms and
   upgrades) plus four ability cards. If the hand contains **no room** the player
   **mulligans** (redraws) until it does, since the first placement must be a
   room. (Ability cards are unused in v1.)
2. Draw **two boss cards**, place **one** face up in front of the player as
   their dungeon's boss, and **discard the other**.
3. The player chooses **one room card** from hand and places it **to the left**
   of their boss card. This is the dungeon's first room (and current entrance).

After setup, every player has a boss and exactly one room in their dungeon.

---

## 2. Arrival Phase

1. Draw **one hero card per player** and add them **face up** to a shared
   "town" area.

Town is **not cleared** between rounds: heroes accumulate there and a hero only
leaves town by **dying** in a dungeon. Heroes that arrived this turn may enter a
dungeon this same turn.

---

## 3. Build Phase

For each player:

1. Draw **two room cards** into hand.
2. **Discard one** room card from hand (mandatory; chosen from the whole hand).
3. The player **may** play one card from hand (at most one per Build phase
   **(interpretation)**):
   - a **room**, either added **to the left** (a new entrance) or placed **on
     top of an existing room** to replace it (the replaced room — and any
     upgrade on it — is discarded), or
   - an **upgrade**, attached to an existing room. Each room holds at most one
     upgrade; attaching another replaces it.
4. A dungeon holds **at most 5 rooms** (the boss does not count). When the
   dungeon already has 5 rooms, a new room can only be placed by replacing an
   existing one.

A hand holds **at most 6 room cards**; rooms drawn beyond that are discarded.

---

## 4. Bait + Crawl Phase

The unit of play is a **party** — one or more heroes who move together. A lone
hero is a party of one. Heroes that arrived this turn are eligible this turn.

Bait and Crawl are **a single, interleaved phase**: bait is recomputed for each
party **right before it would enter**, then it crawls, and only then is the next
party considered. This matters because a crawl changes the owner's points, which
feeds back into the next party's courage check.

Parties are considered in **town order**. For the next party:

1. Compute its **enticement** for each dungeon: sum, over every member, the count
   of that member's preferred bait across the dungeon's boss and rooms. A bait
   shared by two members counts twice.
2. The party is drawn to the **single dungeon with the strictly highest
   enticement**. A tie (including a tie at zero) leaves it **unenticed** — it
   stays in town (waits for Recruitment).
3. **Courage check** (against **current** points). The party enters only if its
   **combined courage** is **at least** the target owner's points *right now*.
   Because an earlier party's death raises that owner's points, a later party can
   find itself **too timid** and stay back. A party that doesn't enter waits.
4. An entering party then **crawls** (see below). Resolving it may raise the
   owner's points; the game then moves on to the next party.

Any party that never enters — **unenticed** or **too timid** — is left
**waiting** and is consolidated in the Recruitment phase.

### Crawling a dungeon

Each entering party crawls its dungeon as a unit, **left to right, then the
boss**. Parties are sent in **one at a time** (in the web app the player
advances each with the bottom button). The **boss deals extra damage equal to
its owner's points** (before any reductions).

**Before** a party crawls, players may act (effects last only that crawl):

- **Play ability cards** — any player, on any dungeon's crawl, one-shot
  (discarded after use): Reinforcements (+2 to a room), Expose Weakness (a room
  becomes unreducible), Sabotage (a room deals 0), Retreat (**target a room**:
  the party turns back there — rooms before it still resolve and score, the rest
  and the boss are skipped, and the owner takes no wound), Blueprints (the player
  draws 2 room cards).
- **Discard-to-boost** — the dungeon owner may, once per room, discard a room
  card to add **+4 damage** to a Collapsing Tunnel / Golem / Necrotic Fog (on
  top of any upgrade), or make a Troll unreducible.

For each encounter in order:

1. **Poison tick** — each poisoned hero takes its poison stacks as unreducible
   damage (from a Poison Gas room passed earlier).
2. **Party-wide effects** — the room's effect may hit specific members with
   unreducible damage: **Antimagic** (each Mage −4), **Zealots** (each Cleric
   −4).
3. **Single-target damage** — the room's damage (base + its grow bonus + dungeon
   auras from **Trap Makers**/**Beast Tamer** + the boss's points bonus) hits the
   highest-current-health member, reduced by living auras and the target's own
   ability (ties: most max health, then board order). A hero that takes damage
   from a **Poison Gas** room gains a poison stack (so it loses 1 more
   unreducible health at every later room).
4. A hero at **0 or below** dies and is removed from the crawl; a **grow-on-death**
   room (Succubus, Plague Zombie) gains +1 damage permanently for each death there.

Scoring per party crawl:

- **Each** hero that dies grants the dungeon owner **a point** (even if other
  members survive).
- If **any** member survives, the owner gains **exactly one wound** (one per
  party, regardless of how many survive).

Surviving members **stay together as a party** and remain in town (they do not
disband). A party only leaves town when **all** of its members have died.

### Quiet round (no hero attacks)

If **no party enters any dungeon** this turn, each player **draws an ability
card**. Before continuing, players may play **no-attack abilities** —
specifically **Blueprints** (draw 2 room cards) — since there is no crawl to
target. (In the web app a "No heroes attacked" panel offers this, then a
**Continue** button ends the round.)

---

## 5. Recruitment Phase

After the crawl, every party that did **not** enter a dungeon this turn
(unenticed or afraid) bands together for later:

1. Each waiting **multi-hero party** recruits one waiting **lone hero**.
2. The remaining waiting **lone heroes pair off** with each other.

Both steps go in **board (town) order** and prefer a partner with a **different**
preferred bait. If an odd number of lone heroes are waiting, the leftover one is
the **odd man out** and waits alone. Merged parties persist into later turns.

So six lone heroes that all stay behind become **three parties of two**, and a
waiting party of four plus three waiting lone heroes become **a party of five
and a party of two**.

---

## Resolved rules

- **Match end / scoring.** A player with **5 wounds** is eliminated. The game
  ends when a player reaches **10 points** or all but one player is eliminated.
  A surviving player's final score is **points − 2 × wounds**; highest wins, and
  a tie goes to whoever ended the game.
- **Deck exhaustion / reshuffling.** Whenever a card must be drawn but its draw
  pile is empty, that deck's **discard pile is shuffled back into the draw
  pile**, and the draw then proceeds. A deck only fails to produce a card when
  both its draw and discard piles are empty.
