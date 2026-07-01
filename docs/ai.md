# Computer Opponent (AI)

Both clients' opponents are played by **`LogicAgent`**, an automated player whose
strategy lives in **data** rather than code: the file
[`data/ai_logic.yaml`](../data/ai_logic.yaml) (the Android app carries its own
copy under `android/app/src/main/assets/ai_logic.yaml`, kept in sync). The agent
is the interpreter; the YAML is the logic. This mirrors how card `effect`s are
declarative data interpreted by `BossEffect` / `RoomEffect` / `AbilityEffect` —
the AI follows the same split, so its behaviour can be tuned without touching
code.

> An older, strategy-free opponent (`RandomAgent`) still exists as a baseline and
> for tests; it picks a legal option at random.

## What the AI decides

Like any player, the agent only ever answers the four `Decision` kinds the game
asks for (see [architecture.md](architecture.md) and `Decision::KINDS`):

| Decision           | When   | What it picks                                         |
|--------------------|--------|-------------------------------------------------------|
| `choose_boss`      | Setup  | one of two drawn bosses (the other is discarded)      |
| `place_first_room` | Setup  | a room from hand to place beside the boss             |
| `discard_room`     | Build  | a room from hand to throw away (mandatory)            |
| `build_room`       | Build  | a room/upgrade/advanced-room move, or build nothing   |

It does **not** yet play ability cards or choose pre-crawl boosts beyond the
existing random discard-to-boost (`Game#agent_pre_crawl`). That is a natural
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
  - prefer: [glory, riches, power, undead]
  - prefer: bait_count
```

### Comparators

| `prefer:` value        | Keeps the candidate with…                                          |
|------------------------|--------------------------------------------------------------------|
| `highest_damage`       | the most printed damage (boss/room damage; an upgrade's bonus).    |
| `lowest_damage`        | the least printed damage (used to throw away the weakest room).    |
| `bait_count`           | the most total bait icons (lures the hardest).                     |
| `[glory, riches, …]`   | a **bait-priority list** — the most icons of the first-listed bait, breaking ties by the next, and so on. Names are exactly glory/riches/undead/power. |
| `most_points`          | **simulation:** the most hero deaths when the current town crawls the dungeon this move would produce (each death = a point). |
| `fewest_wounds`        | **simulation:** the fewest surviving parties (one wound each).     |
| `highest_avg_damage`   | **simulation:** the most average post-reduction damage dealt to the current town heroes (hero damage reduction is accounted for). |

The three **simulation** comparators run the town's parties through a *clone* of
the candidate dungeon via [`DungeonForecast`](architecture.md), so nothing in the
live game is mutated. They only bite mid-game, during `build_room`, when there is
a dungeon and heroes are in town; at Setup the town is empty, so `choose_boss`
and `place_first_room` rely on the static comparators.

### How `build_room` uses it

For a build, the candidates are **every legal move** — add a room to the left,
replace any room, attach an upgrade, drop an advanced room on a matching slot —
plus **build nothing**. The default chain plays to win the dungeon-building game:

```yaml
build_room:
  - prefer: most_points        # kill the most heroes in town
  - prefer: fewest_wounds      # then let the fewest survive
  - prefer: highest_avg_damage # then hurt the heroes the most
  - prefer: highest_damage     # last resort (e.g. empty town): raw power
```

Because "build nothing" is always a candidate, the agent will only build when a
placement scores at least as well as leaving the dungeon as-is.

> **(interpretation)** The player who specified these heuristics asked for the
> placement leading to "the minimum damage." A dungeon owner takes no damage
> directly — their only downside is a **wound per surviving party** — so this is
> implemented as `fewest_wounds`.

## Where it is wired

Player 1 is the human; Players 2…N are `LogicAgent`s. In both clients the `Game`
calls `attach` on each agent (via a small `Agent` abstraction) so it can read the
town for its simulations.

- **Web app:** `POST /new` (in `webapp/app.rb`) loads `data/ai_logic.yaml` once
  per opponent and hands the agents to `Game`.
- **Android:** `GameViewModel.newGame` loads the `ai_logic.yaml` asset per
  opponent (`LogicAgent.load`) and passes them to `Game`. `DungeonForecast` uses
  the resolver's `dryRun` flag so a forecast never grows a grow-on-death room.
