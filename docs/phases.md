# Phases

Dungeon Boss begins with a one-time **Setup** (Boss Selection → First Room
Selection), then repeats this sequence each round:

**Arrival → Discard → Draw → Build → Crawl → Recharge**

where **Crawl** itself runs, per hero/party, as **Entice → Ability → Gauntlet**.

Each phase is implemented as its own class with a single responsibility — see
[architecture.md](architecture.md).

---

## Setup (once, at the start of the game)

### 1. Boss Selection

For each player: draw **two boss cards**, place **one** face up as that player's
dungeon boss, and **discard the other**.

### 2. First Room Selection

Each player is dealt a starting hand of room cards (and ability cards; the
ability deck is used from Recharge onward). If the hand contains **no room** the
player **mulligans** (redraws) until it does, since the first placement must be a
room. The player then chooses **one room card** from hand and places it in **any
of the dungeon's 5 slots**.

After setup, every player has a boss and exactly one room in their dungeon.

> **Dungeon layout.** A dungeon has **5 ordered room slots**, some of which may be
> empty, with the **boss on the right**. A hero **enters from the left** and
> crawls rightward (skipping empty slots) toward the boss. Slot 0 (leftmost) is
> the entrance.

---

## 3. Arrival

Draw **one hero card per (living) player** and add them **face up** to a shared
"town" area. An arriving hero's **starting level is `floor(round / 4)`** (see
[cards.md](cards.md#levelling-and-derived-stats)).

Town is **not cleared** between rounds: heroes accumulate there and a hero only
leaves town by **dying** in a dungeon. Heroes that arrived this turn may enter a
dungeon this same turn.

---

## 4. Discard

Each player **may discard 0, 1, or 2 room cards** from hand (entirely optional —
discarding nothing is allowed).

---

## 5. Draw

Each player draws **1 room card plus 1 additional card per card discarded** in
the Discard phase. So a player who discarded nothing draws 1; one who discarded
two draws 3. (Discard + Draw always nets **+1** card to the hand.)

A hand holds **at most 6 room cards**; rooms drawn beyond that are discarded.

---

## 6. Build

Each player **may** take one of these build actions (at most one per Build phase
**(interpretation)**):

- **Place a room** into **any of the 5 slots**. Placing into an **empty** slot
  fills it; placing onto an **occupied** slot **replaces** that room (the
  replaced room — and any upgrade on it — is discarded).
- **Upgrade a room with a room card** — instead of placing it, spend a **basic or
  advanced room card** from hand to upgrade an existing placed room: the target
  room gains **every bait icon** of the spent card and its **level** rises by
  **1** (basic) or **2** (advanced). The spent card is discarded. (The gameplay
  effect of room level is being built on another branch; see
  [cards.md](cards.md#room-level-upgrading-a-room-with-a-room-card).)
- **Attach a dedicated Upgrade card** to a room (Glory Banner, Arcane Sigil, …).
  Each room holds at most one such upgrade; attaching another replaces it.

A dungeon holds **at most 5 rooms** (the boss does not count); when all 5 slots
are occupied a new room can only be placed by **replacing** one.

> **(interpretation)** Advanced rooms keep their placement requirement when
> **replacing** an occupied slot — the replaced room must share **≥1 bait icon**
> with the advanced room — but may be placed freely into an **empty** slot.

---

## 7. Crawl

The unit of play is a **party** — one or more heroes who move together; a lone
hero is a party of one. Heroes that arrived this turn are eligible this turn.

Crawl is evaluated **one party at a time, in town order**, and is interleaved:
each party is fully resolved (Entice → Ability → Gauntlet) before the next is
considered. This matters because a Gauntlet changes the owner's points, which
feeds back into the next party's Entice/courage check.

For the next party:

### 7a. Entice

1. Compute its **enticement** for each dungeon: sum, over every member, the count
   of that member's preferred bait across the dungeon's boss and rooms. A bait
   shared by two members counts twice.
2. The party is drawn to the **single dungeon with the strictly highest
   enticement**. A tie (including a tie at zero) leaves it **unenticed** — it
   stays in town (consolidated in Recharge).
3. **Courage check** (against the target owner's **current** points). The party
   enters only if its **combined courage** is **at least** that owner's points
   right now. A party's combined courage is the sum of its members' courage, and
   each member's courage is **`1 + level`**. Because an earlier party's death
   raises the owner's points, a later party can find itself **too timid** and
   stay back.

Any party that is **unenticed** or **too timid** is left **waiting** and is
consolidated in the Recharge phase.

### 7b. Ability

> **TODO (not yet implemented).** The intended Ability step is a turn-based
> **priority loop**: players take turns either **playing one ability or
> passing**, and **whenever an ability is played every player is given another
> opportunity** to respond, until all players pass consecutively. This is
> documented here for the target design but is **not built yet**.
>
> For now, the existing pre-Gauntlet interaction is retained: before a party
> crawls, players may play ability cards and the dungeon owner may
> discard-to-boost a room (effects last only that crawl):
>
> - **Play ability cards** — any player, on any dungeon's crawl, one-shot
>   (discarded after use): Reinforcements (+2 to a room), Expose Weakness (a room
>   becomes unreducible), Sabotage (a room deals 0), Retreat (**target a room**:
>   the party turns back there — rooms before it still resolve and score, the
>   rest and the boss are skipped, and the owner takes no wound), Blueprints (the
>   player draws 2 room cards).
> - **Discard-to-boost** — the dungeon owner may, once per room, discard a room
>   card to add **+4 damage** to a Collapsing Tunnel / Golem / Necrotic Fog (on
>   top of any upgrade), or make a Troll unreducible.

### 7c. Gauntlet

The entering party crawls its dungeon as a unit, **left to right (skipping empty
slots), then the boss**. Parties are sent in **one at a time** (in a client the
player advances each with the bottom button). The **boss deals extra damage equal
to its owner's points** (before any reductions).

For each encounter in order:

1. **Poison tick** — each poisoned hero takes its poison stacks as unreducible
   damage (from a Poison Gas room passed earlier).
2. **Party-wide effects** — the room's effect may hit specific members with
   unreducible damage: **Antimagic** (each Mage −4), **Zealots** (each Cleric
   −4).
3. **Single-target damage** — the room's damage (base + its grow bonus + dungeon
   auras from **Trap Makers**/**Beast Tamer** + the boss's points bonus) hits the
   highest-current-health member, reduced by living party auras
   (`party_damage_reduction`) and the target's own `self_damage_multiplier` (ties:
   most max health, then board order). A hero that takes damage from a **Poison
   Gas** room gains a poison stack (so it loses 1 more unreducible health at every
   later room).
4. A hero at **0 or below** dies and is removed from the crawl; a
   **grow-on-death** room (Succubus, Plague Zombie) gains +1 damage permanently
   for each death there.

Scoring per Gauntlet:

- **Each** hero that dies grants the dungeon owner **a point** (even if other
  members survive).
- If **any** member survives, the owner gains **exactly one wound** (one per
  party, regardless of how many survive).

Surviving members **stay together as a party** and remain in town (they do not
disband). A party only leaves town when **all** of its members have died.

### Quiet round (no party crawled)

If **no party enters any dungeon** this turn, there is no Gauntlet to act on.
Players may still play **no-attack abilities** — specifically **Blueprints**
(draw 2 room cards) — then continue to Recharge.

---

## 8. Recharge

After the crawl, Recharge does three things:

1. **Ability cards** — **each player who was not attacked** this round (no party
   entered their dungeon) **draws one ability card**.
2. **Party consolidation** — every hero that did **not** attack this round (its
   party was unenticed or too timid) bands together for later, using the existing
   recruitment rules:
   - Each waiting **multi-hero party** recruits one waiting **lone hero**.
   - The remaining waiting **lone heroes pair off** with each other.
   Both steps go in **board (town) order** and prefer a partner with a
   **different** preferred bait. If an odd number of lone heroes are waiting, the
   leftover one is the **odd man out** and waits alone. Merged parties persist
   into later turns.
3. **Levelling** — **each hero that survived a dungeon run** this round gains
   **+1 level** (raising its max HP, courage, and party reduction; see
   [cards.md](cards.md#levelling-and-derived-stats)).

So six lone heroes that all stay behind become **three parties of two**, and a
waiting party of four plus three waiting lone heroes become **a party of five and
a party of two**.

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
