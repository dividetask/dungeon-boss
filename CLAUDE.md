# CLAUDE.md — Project Guidelines for Dungeon Boss

## Project Overview

Dungeon Boss is a competitive card game in which each player builds a dungeon
out of **room cards** behind a **boss card** and tries to lure wandering
**heroes** to their doom. Players score points when a hero dies in their
dungeon and take wounds when a hero survives the crawl.

This repository contains two clients plus a shared specification:

- **Web app** (`webapp/`) — a [Sinatra](https://sinatrarb.com/) application,
  written in Ruby. Its purpose is **testing and prototyping the game rules**.
  It is the reference implementation of the game engine.
- **Android app** (`android/`) — the production client (added later).
- **Shared specification** (`docs/`) — the single source of truth for game
  rules, phases, card schemas, and the class/responsibility design that *both*
  clients implement.
- **Card data** (`data/cards/`) — the canonical definition of every card, split
  into `bosses.yaml`, `rooms.yaml` (rooms + upgrades + advanced rooms),
  `heroes.yaml`, and `abilities.yaml`. Both clients load the same files.

Because the two clients are written in different languages (Ruby and Kotlin),
they cannot share source code. They share the **`docs/` specification** and the
**`data/cards/`** card files instead. Keep both clients faithful to the docs.

### Versioning of scope

- **Current scope:** Resolve the game using damage, health, bait icons,
  preferred bait, **data-driven levelling heroes** (HP / courage / party
  reduction scale with a per-hero level; damage modifiers during the crawl),
  and **courage / parties** (afraid heroes band together). The turn runs
  **Arrival → Discard → Draw → Build → Crawl (Entice → Ability → Gauntlet) →
  Recharge** over a **5-slot dungeon** (see [docs/phases.md](docs/phases.md)).
- **Documented but not yet built:** the **Ability** priority loop (turn-based
  play/pass) is specced as a **TODO**; the existing pre-Gauntlet ability/boost
  interaction stands in for now. The **gameplay effect of a room's level** (from
  room-card upgrades) is being implemented on a separate branch.
- **Still ignored:** **boss/room ability *text*** beyond the declarative
  `effect` data already supported.

## Critical Rules

### Distinct classes with minimal responsibility

- Model every concept as its own small class with a single clear job.
- Phases are classes too (`SetupPhase`, `ArrivalPhase`, `DiscardPhase`,
  `DrawPhase`, `BuildPhase`, `EnticePhase`, `AbilityPhase`, `GauntletPhase`,
  `RechargePhase`). A phase orchestrates a step of the turn and nothing else.
- Keep data holders (cards) free of game-flow logic. Keep game-flow logic
  (phases, resolvers) free of data definitions.
- See [docs/architecture.md](docs/architecture.md) for the canonical class
  list both clients implement.

### Do not loop on errors — stop and explain

- If an approach fails twice, stop immediately. Do not retry the same strategy.
- Explain what went wrong, what was attempted, and suggest alternatives — let me
  decide how to proceed.
- Do not silently retry file operations, compilations, or code execution hoping
  for a different result.
- If a tool call or code execution returns an error, analyze the error before
  taking any further action.

### Ask questions often

- Ask questions often, especially when there is ambiguity or you believe I have
  made a mistake.
- Ask questions in plain chat text, not via the `AskUserQuestion` /
  multiple-choice prompt tool. Write the question(s) as normal prose and wait
  for a reply.

### Stop after asking questions

- Whenever you have a question, or multiple questions, stop immediately after
  asking them.

### Design document conventions

- The canonical bait types are exactly: **glory**, **riches**, **undead**,
  **arcane**. Use these names verbatim.
- Dungeon layout language: a dungeon has **5 ordered room slots** (some may be
  empty) with the **boss** on the right. A hero **enters from the left** and
  crawls rightward (skipping empty slots) toward the boss. The **leftmost slot
  (slot 0) is the entrance**.
- Cross-reference between documents using relative markdown links:
  `[phases.md](docs/phases.md)`.
- Where a rule is ambiguous in the source design, the chosen interpretation is
  documented inline and flagged with **(interpretation)** so it can be revised.

## File Structure

```
.
├── CLAUDE.md               # This file
├── README.md
├── data/
│   └── cards/              # Canonical card definitions (bosses/rooms/heroes/abilities)
├── docs/                   # Shared specification (source of truth)
│   ├── README.md           # Index
│   ├── game-overview.md    # What the game is, win condition, components
│   ├── phases.md           # The turn structure, phase by phase
│   ├── cards.md            # Card types, fields, and the YAML schema
│   ├── architecture.md     # Class design + responsibilities (both clients)
│   ├── pseudocode.md       # Per-class behavioural pseudocode
│   └── screens.md          # Each UI screen/state and its controls
├── webapp/                 # Sinatra reference implementation (Ruby)
└── android/                # Android client (added later)
```
