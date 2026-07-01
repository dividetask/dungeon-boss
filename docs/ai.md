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

It does **not** yet play ability cards or choose pre-crawl boosts beyond the
existing random discard-to-boost (`Game#agentPreCrawl`). That is a natural
follow-up: it needs a new agent entry point, because crawl actions are not
surfaced as `Decision`s.

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

## Where it is wired

Player 1 is the human; Players 2…N are `LogicAgent`s. The `Game` calls `attach`
on each agent (via the small `Agent` interface) so it can read the town for its
simulations. `GameViewModel.newGame` loads the `ai_logic.yaml` asset per opponent
(`LogicAgent.load`) and passes the agents to `Game`.
