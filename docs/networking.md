# Networking — Online Multiplayer & Matchmaking

> **Status: proposed, not yet built.** This document specifies the design for
> online play between separate devices. No networking code exists in either
> client today; both are local-only (hotseat + AI agents). This is the spec to
> build against, written in the same class-per-responsibility style as
> [architecture.md](architecture.md).

## Scope

- **Who plays:** **2–4 real humans**, each on their own device, over the
  **internet**. No AI opponents in an online match (the local `RandomAgent` /
  `LogicAgent` are for offline/practice play only).
- **First target client:** two (up to four) **Android** phones. The design is
  client-agnostic so the web app can join the same matches later.
- **Not in scope here:** accounts, friend lists, ranked/skill matchmaking,
  spectators, chat. Matchmaking here is **automatic**: a player asks for a 2–4
  player table and the **server pairs whoever is waiting** into a match. The app
  finds opponents — no codes, no invites, no out-of-band sharing.

## The core decision: non-authoritative lockstep

There is **no authoritative game server**. The game is free (no stakes) and the
audience is small, so the cost of an authoritative server — running the engine
remotely and serializing full game state across the wire — is not justified
**(interpretation; revisit if a competitive/ranked mode is ever added)**.

Instead every device runs its **own local `Game`** (the engine already ships on
every client) and the devices stay identical by **deterministic lockstep**:

```
Phone A ─┐                                   ┌─ RemoteAgent(A)
         ├── decision messages ──► Relay ──► ├─ RemoteAgent(B)   each phone's
Phone B ─┘   (ordered, tiny)     (dumb pipe) ├─ RemoteAgent(C)   own Game applies
                                             └─ RemoteAgent(D)   the same inputs
```

1. At match start every device agrees on a **shared RNG seed** and a fixed
   **player order** (see `MatchConfig`).
2. Every device constructs the **same `Game`** from that seed + order. Because
   deck shuffles are seeded, all decks are identical on every device.
3. When a player answers a `Decision`, their device broadcasts **only the input**
   — a small `MoveMessage` (`{seq, player, decisionId, choiceId, target}`) — not
   the resulting state.
4. Every device (including the sender) applies each `MoveMessage` to its **local**
   `Game` in the agreed `seq` order. All devices advance through identical state.

Only **inputs** ever cross the wire, never game state. The full board never needs
a wire format; the existing `Decision` (already "options, no logic") plus a
choice id is the entire payload. This is both less work than an authoritative
server (no state serialization) and less traffic.

### Why this fits Dungeon Boss

- The engine is **turn-based and discrete** — integers, ids, and enumerated
  choices; no floating point, physics, or real-time simulation to diverge.
- `Game` already **drives the turn as a queue of `Decision`s** resolved through
  the `Agent` seam. A remote player is just a new kind of `Agent` (`RemoteAgent`)
  — the same seam that `RandomAgent` / `LogicAgent` already plug into.
- Automatic phases (Arrival, Draw, Recharge) are already deterministic given the
  deck, so they need **no** messages — every device runs them locally in step.

## Determinism: the one hard requirement

Lockstep is only correct if every device computes the **same result from the same
inputs**. This is very achievable here, but every source of nondeterminism must be
routed through the shared seed or eliminated. This is the load-bearing
constraint of the whole design.

| Source | Rule |
|--------|------|
| **`Deck` shuffle** | The big one. All shuffles draw from **one seeded PRNG** created from `MatchConfig.seed`. Same seed → identical decks on every device. |
| **Any other RNG** (e.g. `PartyNamer`) | Same seeded PRNG. **No unseeded/global random anywhere** on the online path. |
| **Iteration order** | Players, parties, town, encounters, and decisions must iterate in an **explicit, stable order** (list/board order), never hash-map or set iteration order that may differ across Ruby/Kotlin or JVM builds. |
| **Wall clock / locale** | Nothing on the game path may depend on time, timezone, or locale-sensitive formatting. |

Because these must hold, the engine work is: **audit every `rand`/`shuffle`/random
call and make them take an injected seeded generator.** `Game` construction gains
a `seed` parameter that seeds this generator.

### DesyncGuard (detect, don't correct)

Even with care, a determinism bug can slip in. Every device periodically (e.g.
end of each round) computes a cheap **hash of its own game state** and includes it
on the next message (or a dedicated checkpoint message). If two devices report
different hashes for the same `seq`, they have **desynced** — surface an error and
halt that match rather than let players see divergent boards. The guard only
*detects* divergence (it is a debugging/safety net, not an authority that
overrides anyone).

## Classes and responsibilities

New classes for online play. They live at the edges; the engine (`Game`,
`Decision`, phases) is unchanged except for accepting a seeded generator.

| Class | Responsibility |
|-------|----------------|
**Client-side** (Android; the web app later):

| Class | Responsibility |
|-------|----------------|
| `MatchConfig` | The immutable start parameters the server hands out in `matched`: `matchId`, ordered player list (id + display name), the shared **`seed`**, and `you` (the local seat). Every device builds its `Game` from exactly this. |
| `MoveMessage` | One player's answer to one `Decision`: `seq` (server-stamped), `player` (seat), `decisionId`, `choiceId`, optional `target`. The **only** in-game message type. |
| `NetworkTransport` | The WebSocket client to the matchmaking server. Sends `queue` / `move` / `reconnect`; delivers incoming `matched` / ordered `move` / `log` messages; reports connect/disconnect. Knows nothing about game rules. |
| `Matchmaking` (client) | The online entry point: connect, send `queue{players}`, wait for `matched`. **Automatic** — no codes; the server finds opponents. Surfaces "searching… / matched" to the UI. |
| `RemoteAgent` | An `Agent` for a **remote** seat: `choose(decision)` blocks until the matching `move` for that seat arrives from the transport, then returns its `(choiceId, target)`. The mirror image of a local human. |
| `LocalRelay` *(the local player)* | Not a new class so much as a rule: when the **local** human answers a `Decision`, the client **sends** that answer as a `move`; it is applied only when it comes back from the server in `seq` order (the server is the sequencer), keeping every device's order identical. |
| `MatchClient` | Orchestrates a live match: owns the local `Game`, the `NetworkTransport`, and the per-seat `Agent` map (local human for `config.you`, `RemoteAgent` for the others). Feeds ordered `move`s into the game loop and drives `DesyncGuard`. Online counterpart to Android's `GameViewModel`. |

The **`Agent` map** `Game` already takes becomes: our own seat → the local human
input path; every other seat → a `RemoteAgent`. No `Game` change beyond the seed.

**Server-side** (`server/`, a self-hosted Node service — **built**; see
[../server/README.md](../server/README.md)). It runs no game rules — it groups
players and orders moves:

| Class | Responsibility |
|-------|----------------|
| `MatchmakingQueue` | The waiting pool. One FIFO queue per desired table size (2–4); forms a table as soon as enough compatible players wait. This is what makes matchmaking automatic. |
| `Match` | A formed table: seats, the shared `seed`, and the ordered move log. **Sequences** each incoming move (`seq++`) and broadcasts it to every seat; retains the log for reconnect-by-replay. |
| `Matchmaker` | Coordinator: routes each client message to the queue or its match; mints the `matchId` + **`seed`** at match formation. |
| `Session` | One connected client (its socket + whether it is queued or seated). |

## Message schema

JSON text frames over one WebSocket per player. The full contract (every message
type, both directions) lives in [../server/README.md](../server/README.md); the
two the game turns on are:

```
matched (server → client, when a table forms):
  config {
    matchId: string
    seed:    integer     # server-minted shared PRNG seed; seeds every shuffle
    players: [ { seat: 0..3, id: string, name: string } ]   # fixed seat order
    you:     integer     # which seat is local (a human); others are RemoteAgents
  }

move (client → server to submit; server → clients to apply, stamped):
  seq:        integer    # server-assigned global order (absent on submit)
  player:     integer    # seat 0..3 that made the choice
  decisionId: string     # which Decision this answers (must match the pending one)
  choiceId:   string     # the chosen option's id
  target:     string|null# optional target (e.g. which room/slot)
```

`decisionId` lets a device assert the incoming move answers the `Decision` its own
`Game` is currently waiting on — a mismatch means a desync or a bug, caught
immediately. (`DesyncGuard` state hashes travel out-of-band, e.g. a periodic
checkpoint message, so they never gate the move stream.)

## Match lifecycle

```
CONNECT  each device opens a WebSocket → server sends hello{ playerId }
QUEUE    device sends queue{ name, players:2..4 } → server sends queued{ waiting }
MATCH    server pairs the waiting players automatically (no codes) and sends
           matched{ config } to every seat
START    each device: game = Game.seeded(config.seed, players); local seat = config.you;
           agents = { config.you → local human, others → RemoteAgent }
PLAY     loop:
           local Decision  → human answers → send move → (server stamps seq) →
             move echoes back → apply in seq order
           remote Decision → RemoteAgent blocks until that seat's move arrives → apply
           automatic phases run locally on every device (no messages)
           DesyncGuard checkpoints each round
END      Scoreboard game-over on every device identically; show result
```

The server is the neutral party only at the edges: it **forms the table** and
**mints the seed**, and in-game it **orders the moves**. It has no authority over
the rules — every device computes the game itself.

## Reconnection: replay from the move log

Lockstep makes reconnection nearly free. The server retains the ordered move log
for the match (`Match` keeps it in memory). A device that drops and returns sends
`reconnect{ matchId, playerId }` and:

1. Rebuilds a fresh `Game` from the same `config` (same seed → same start).
2. **Replays every move** in the returned `log` from `seq 0` up to the latest.
3. Arrives at the exact current state, then resumes live.

No snapshotting of game state is required — the seed plus the input log **is** the
save file. Open policy question: what the **other** players do while someone is
gone — **pause and wait** for a reconnect window, vs. **timeout → the match ends /
that player forfeits**. Recommended default: pause with a timeout, then end the
match **(interpretation)**.

## The matchmaking server

**Chosen: a self-hosted WebSocket server** (`server/`, built — see
[../server/README.md](../server/README.md)). A managed database (Firebase et al.)
was considered but ruled out: **automatic matchmaking** needs a waiting-pool that
pairs players and forms tables, which is server logic, not just a shared document.
Rather than push that into serverless functions, a small dedicated service is
clearer and keeps the whole online path in one testable place. It still runs **no
game rules** — it groups players and orders moves, nothing more.

What it provides, per match: automatic **pairing** of waiting players into 2–4
player tables (no codes), **ordered** move fan-out (the server stamps `seq`),
minting of the shared **seed**, and retention of the **move log** for reconnect.

It is a single stateless Node process (matches live in memory); run one instance
to start. Deploy behind TLS so phones connect over `wss://`. Google Play Games
real-time multiplayer was **not** used: Google deprecated that API and it would
lock the design to Android just when we want the web client to join later.

`NetworkTransport` is the seam that hides the backend — the client speaks the
WebSocket protocol and never assumes a particular host, so the server can be moved
or replaced without touching `Game`, `RemoteAgent`, or `MatchClient`.

## Trade-off and fallback

The alternative non-authoritative design is **host-authoritative**: one player's
device runs the single `Game` and the others are thin clients. It avoids the
determinism audit but **requires full game-state serialization** and dies (or needs
host-migration) when the host leaves. For a turn-based card game with the engine
already on every device, **lockstep is the better fit** — trivial messages, free
reconnect-by-replay, and no host to lose. The price is the determinism discipline
above. If the determinism audit ever turns up something genuinely hard to seed,
host-authoritative is the documented fallback.

## Build order (suggested)

1. **Determinism pass** on the engine — *(started, Android)*: route all randomness
   through one injected seeded generator; add `seed` to `Game` construction; make
   iteration orders explicit. Testable offline: same seed + same inputs → identical
   end state. **Landed:** `Game.seeded(seed)` (the Kotlin engine already threaded a
   single `rng` through every deck, `PartyNamer`, and the auto-boost roll) plus
   `NetworkDeterminismTest` — same-seed shuffle/deal equality and a full seeded
   playthrough that stays byte-identical (via `exportJson`) to game over.
2. **`RemoteAgent`** + the local-broadcast rule against an in-memory fake
   transport (two `Game`s in one process kept in lockstep). *(next)*
3. **Matchmaking server** — *(built)*: automatic pairing, move sequencing, move
   log, reconnect; self-hosted Node service in `server/` with passing tests for
   auto-match, sequenced relay, and reconnect-by-replay.
4. **`NetworkTransport`** (Android WebSocket client) + `MoveMessage` /
   `MatchConfig` (de)serialization against the server protocol.
5. **`Matchmaking` + the "Play online" button** in the Android UI (a separate
   entry point from "New game" / play-computer): connect, `queue`, show
   "searching…", then start the match on `matched`.
6. **`MatchClient`** wiring into the Android `GameViewModel`; **`DesyncGuard`**;
   reconnect-by-replay and the disconnect policy.

Server-side (step 3) is done and tested. The remaining work is all **client-side**
(steps 2, 4, 5, 6): teach the Android app to speak the protocol, add the button,
and slot `RemoteAgent` into the existing `Agent` seam.

## Open questions

- **Disconnect policy** — pause-and-wait vs. timeout-to-forfeit for the *other*
  players (default proposed above).
- **`AbilityPhase`** — the turn-based priority loop is still unbuilt
  ([phases.md](phases.md), [architecture.md](architecture.md)). It is the one
  phase with genuine turn-by-turn cross-player interaction and will exercise the
  networking hardest; it should be built alongside (or before) online play.
- **Thin queue / no opponents** — a table forms only when exactly `players`
  people want the same size. If few players are online, someone waiting for a
  4-player table may wait forever. Options for later: a **backfill timeout**
  (start a smaller table, or offer to add AI after N seconds), a single "any
  size" queue, or a shown "N searching" count. Deliberately omitted for now —
  the current server pairs strictly by requested size.
- **Skill / region matchmaking** — out of scope; the queue is FIFO by size only.
