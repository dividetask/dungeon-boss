# Computer Opponent (AI)

The computer opponents are played by **`LogicAgent`**, an automated player whose
strategy lives in **data** rather than code: the file
[`data/ai_logic.yaml`](../data/ai_logic.yaml) (the Android app carries its own
copy under `android/app/src/main/assets/ai_logic.yaml`, kept in sync). The agent
is the interpreter; the YAML is the logic. This mirrors how card `effect`s /
fields are declarative data — the AI follows the same split, so its behaviour can
be tuned without touching code.

> An older, strategy-free opponent (`RandomAgent`) still exists as a baseline and
> for tests; it picks a legal option at random.
>
> The **Android client** implements the AI. The webapp reference implementation
> is not updated for the current rules overhaul (see
> [architecture.md](architecture.md#client-parity-reference-vs-android)).

## What the AI decides

Like any player, the agent only ever answers the `Decision` kinds the game asks
for:

| Decision           | When   | What it picks                                              |
|--------------------|--------|-----------------------------------------------------------|
| `choose_boss`      | Setup  | one of two drawn bosses (the other is discarded)          |
| `place_first_room` | Setup  | a room from hand to place beside the boss                 |
| `discard_rooms`    | Build  | a room from hand to throw away (drawing 1 + discards)     |
| `build_room`       | Build  | a room placement / upgrade move, or build nothing         |

Beyond the `Decision` kinds, the agent also **plays ability cards** in the
pre-crawl window (see [Ability cards](#ability-cards-pre-crawl) below). Because a
crawl action is not a `Decision`, this uses a **separate agent entry point**,
`Agent.preCrawlPlays`, that the `Game` calls for each automated player just
before a crawl resolves. The one thing the agent still does only at random is the
owner's discard-to-boost (`Game#agentPreCrawl`).

## The heuristics file

One key per decision; each value is an **ordered list of `prefer:` rules** that
form a **tie-break chain**. The first rule scores every candidate and keeps only
the best-scoring; remaining ties pass to the next rule; any ties left after the
last rule are broken at random.

```yaml
choose_boss:
  - prefer: highest_damage
  - prefer: [glory, riches, arcane, undead]
  - prefer: bait_count
```

### Comparators

| `prefer:` value        | Keeps the candidate with…                                          |
|------------------------|--------------------------------------------------------------------|
| `highest_damage`       | the most headline damage (the card's primary channel — lead, else all, else rear; the boss's damage). |
| `lowest_damage`        | the least headline damage (used to throw away the weakest room).   |
| `bait_count`           | the most total bait icons (lures the hardest).                     |
| `[glory, riches, …]`   | a **bait-priority list** — the most icons of the first-listed bait, breaking ties by the next, and so on. Names are exactly glory/riches/undead/arcane. |
| `most_points`          | **simulation:** the most hero deaths when the current town crawls the dungeon this move would produce (each death = a point). |
| `fewest_wounds`        | **simulation:** the fewest surviving parties (one wound each).     |
| `highest_avg_damage`   | **simulation:** the most average post-reduction damage dealt to the current town heroes (hero damage reduction is accounted for). |

The three **simulation** comparators run the town's parties through a *clone* of
the candidate dungeon via [`DungeonForecast`](architecture.md), so nothing in the
live game is mutated (the forecast crawls are `dryRun`, so a grow-on-death room is
never levelled up). They only bite mid-game, during `build_room`, when there is a
dungeon and heroes are in town; at Setup the town is empty, so `choose_boss` and
`place_first_room` rely on the static comparators.

### How `build_room` uses it

For a build, the candidates are **every legal move** — place a room into any of
the 5 slots (an empty slot fills, an occupied one is replaced; an advanced room
may only fill an empty slot or replace a bait-sharing one), or **spend a room card
to upgrade a placed room** (granting its bait icons and a level) — plus **build
nothing**. The default chain plays to win the dungeon-building game:

```yaml
build_room:
  - prefer: most_points        # kill the most heroes in town
  - prefer: fewest_wounds      # then let the fewest survive
  - prefer: highest_avg_damage # then hurt the heroes the most
  - prefer: highest_damage     # last resort (e.g. empty town): raw power
```

Because "build nothing" is always a candidate, the agent will only build when a
placement scores at least as well as leaving the dungeon as-is.

> **(interpretation)** A dungeon owner takes no damage directly — their only
> downside is a **wound per surviving party** — so "minimise the damage taken" is
> implemented as `fewest_wounds`.

## Ability cards (pre-crawl)

Ability cards are **not** decided by a tie-break chain. Just before a party
crawls, every automated player gets to play cards on that crawl — the owner to
strengthen its dungeon, opponents to disrupt it — mirroring what the human can do
in the same window. The `Game` calls `Agent.preCrawlPlays` for each automated
player and applies the returned plays via `Game.playAbility`; **opponents act
first (disruption), then the owner has the last word (buffs).** This is a
simplified stand-in for the full turn-based Ability priority loop still marked
TODO in [phases.md](phases.md#7b-ability).

Each card the actor holds is judged **on its own**: the agent forecasts the
pending crawl WITH and WITHOUT the card — a side-effect-free `PartyCrawlResolver`
dry run through the *live* dungeon, using the modifiers assembled so far — and
spends it only when one of the objectives configured for that card clears its
threshold. The policy lives in the `ai_abilities` block of
[`data/ai_logic.yaml`](../data/ai_logic.yaml); `AbilityPolicy` parses it and
`AbilityChooser` interprets it (agent = interpreter, YAML = logic, as elsewhere).

```yaml
ai_abilities:
  winning_opponent_points: 5   # an opponent is "winning" (worth a wound) at this many points
  cards:
    ability_extra_damage:      { prevent_self_wound: true }        # Bolster
    ability_full_damage:       { prevent_self_wound: true }        # Counter
    ability_no_damage:         { wound_winning_opponent: true, deny_opponent_points: 2 } # Sabotage
    ability_return_to_town:    { deny_opponent_points: 3 }         # Retreat
    ability_draw_rooms:        { refill_room_hand_below: 3 }       # Blueprints
```

### Objectives

| Objective                 | Played by | The card is spent when…                                                                 |
|---------------------------|-----------|-----------------------------------------------------------------------------------------|
| `prevent_self_wound: true`| the owner | a hero would otherwise **survive** its dungeon (a survivor costs the owner a wound), and the card makes the crawl lethal. It targets the room that removes the wound and kills the most. |
| `wound_winning_opponent: true` | an opponent | it makes a hero **survive** the owner's dungeon (handing that owner a wound) — but only when the owner is **winning** (`points >= winning_opponent_points`), so disruption is spent on real threats. |
| `deny_opponent_points: N` | an opponent | it stops the owner scoring at least **N** points (N fewer hero deaths). e.g. Retreat at `3` fires when it saves 3+ points, never for 2. It targets the room that denies the most. |
| `refill_room_hand_below: N` | anyone   | it is a no-target draw card (Blueprints) and the actor holds **fewer than N** room cards. |

Because the owner's objective (`prevent_self_wound`) applies only when the actor
owns the crawl, and the disruption objectives only when it does not, the AI never
plays a card in a direction that would help the dungeon it is crawling against —
e.g. an opponent never Bolsters the owner's room, and the owner never Sabotages
its own. A card whose configured objectives all fall short is simply held.

> The **webapp** reference implementation has no `LogicAgent` (it is not updated
> for the current rules overhaul), so this behaviour lives only in the Android
> client plus the shared spec / YAML.

## Where it is wired

Player 1 is the human; Players 2…N are `LogicAgent`s. The `Game` calls `attach`
on each agent (via the small `Agent` interface) so it can read the town for its
simulations. `GameViewModel.newGame` loads the `ai_logic.yaml` asset per opponent
(`LogicAgent.load`) and passes the agents to `Game`.
