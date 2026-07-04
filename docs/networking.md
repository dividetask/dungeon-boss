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
  spectators, chat. Matchmaking here means the minimum needed to get 2–4 chosen
  people into the same match: **create a match, share a code, join by code**.

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
| `MatchConfig` | The immutable agreed-upon start parameters: `matchId`, ordered player list (id + display name), and the shared **`seed`**. Every device builds its `Game` from exactly this. Serializable. |
| `MoveMessage` | One player's answer to one `Decision`: `matchId`, `seq` (monotonic), `player`, `decisionId`, `choiceId`, optional `target`, optional state `hash`. The **only** game message type. Serializable. |
| `NetworkTransport` | Wraps the relay backend. Sends a `MoveMessage`; delivers incoming messages **in `seq` order, exactly once, reliably**; reports connect/disconnect. Knows nothing about game rules. |
| `Lobby` / `Matchmaker` | Pre-game: **create** a match (mint `matchId` + short join code, pick the seed), **join** by code, list seated players, and **start** (freeze `MatchConfig` and hand off to the transport). The only "matchmaking" surface. |
| `RemoteAgent` | An `Agent` for a **remote** seat: `choose(decision)` blocks until the matching `MoveMessage` for that seat arrives from the transport, then returns its `(choiceId, target)`. The mirror image of a local human. |
| `LocalRelay` *(the local player)* | Not a new class so much as a rule: when the **local** human answers a `Decision`, the client **broadcasts** that answer as a `MoveMessage` before/as it applies it, so remote devices' `RemoteAgent`s can resolve the same decision. |
| `MatchClient` | Orchestrates a live match: owns the local `Game`, the `NetworkTransport`, and the per-seat `Agent` map (local human for our seat, `RemoteAgent` for the others). Feeds ordered `MoveMessage`s into the game loop and drives `DesyncGuard`. Online counterpart to the webapp's request handlers / Android's `GameViewModel`. |

The **`Agent` map** `Game` already takes becomes: our own seat → the local human
input path; every other seat → a `RemoteAgent`. No `Game` change beyond the seed.

## Message schema

Two message families: **lobby** (pre-game, small, human-paced) and **move**
(in-game, the lockstep inputs). JSON on the wire.

```
MatchConfig {
  matchId:  string          # server-minted id for the match
  joinCode: string          # short human-shareable code (e.g. "K7Q2")
  seed:     integer         # shared PRNG seed; seeds every shuffle/random
  players:  [ { seat: 0..3, id: string, name: string } ]   # fixed order
}

MoveMessage {
  matchId:    string
  seq:        integer       # global monotonic order of applied decisions
  player:     integer       # seat 0..3 that made the choice
  decisionId: string        # which Decision this answers (must match the pending one)
  choiceId:   string        # the chosen option's id
  target:     string|null   # optional target (e.g. which room/slot)
  hash:       string|null   # optional DesyncGuard checkpoint of state after apply
}
```

`decisionId` is included so a device can assert the incoming move answers the
`Decision` its own `Game` is currently waiting on — a mismatch means a desync or a
bug, caught immediately.

## Match lifecycle

```
CREATE   Host: Lobby.create() → matchId + joinCode + seed → MatchConfig (host seated 0)
JOIN     Others: Lobby.join(joinCode) → seated in arrival order (seats 1..3)
LOBBY    Host sees seated players; 2–4 total; host presses Start
START    Lobby freezes MatchConfig; broadcast to all; each device:
           game = Game.new(players, seed = config.seed, agents = agentMap(config))
PLAY     loop:
           local Decision  → human answers → broadcast MoveMessage(seq++) → apply
           remote Decision → RemoteAgent blocks on transport → MoveMessage → apply
           automatic phases run locally on every device (no messages)
           DesyncGuard checkpoints each round
END       Scoreboard game-over on every device identically; show result
```

Seat 0 (the creator/host) owns only **pre-game** choices that must be single-sourced
— the seed and the join code. Once the match starts the host has **no special
authority**; it is an ordinary lockstep peer.

## Reconnection: replay from the move log

Lockstep makes reconnection nearly free. The relay retains the ordered
`MoveMessage` log for the match (this is what a relay is good at). A device that
drops and returns:

1. Rebuilds a fresh `Game` from the same `MatchConfig` (same seed → same start).
2. **Replays every `MoveMessage`** from `seq 0` up to the latest in order.
3. Arrives at the exact current state, then resumes live.

No snapshotting of game state is required — the seed plus the input log **is** the
save file. Open policy question: what the **other** players do while someone is
gone — **pause and wait** for a reconnect window, vs. **timeout → the match ends /
that player forfeits**. Recommended default: pause with a timeout, then end the
match **(interpretation)**.

## The relay backend

The relay is a **dumb pipe + lobby store** — it never runs game logic, never
validates a move against the rules, and holds no `Game`. It must provide, per
match: **ordered, reliable, exactly-once** message fan-out, presence for the
lobby, and retention of the move log for replay. Given Android-first and no
game logic to host, a **managed real-time backend** is the fastest path and needs
no server of our own to operate.

| Option | Notes |
|--------|-------|
| **Firebase Realtime DB / Firestore** *(recommended)* | Native Android SDK; "append to an ordered move list + presence" is its core competency; no server code to write or host. Start here. |
| **Ably / PubNub** | Pure pub/sub with guaranteed ordering if a message bus is preferred over a database. |
| **Self-hosted WebSocket relay** | Only if we want to own the infrastructure; more work, no rules benefit. |

Avoid **Google Play Games** real-time multiplayer: Google **deprecated** that API,
and it would lock the design to Android just when we want the web client to join
later.

`NetworkTransport` is the seam that hides this choice — swapping Firebase for
another backend must not touch `Game`, `RemoteAgent`, or `MatchClient`.

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

1. **Determinism pass** on the engine: route all randomness through one injected
   seeded generator; add `seed` to `Game` construction; make iteration orders
   explicit. (Testable offline: same seed + same inputs → identical end state.)
2. **`RemoteAgent`** + the local-broadcast rule against an in-memory fake
   transport (two `Game`s in one process kept in lockstep).
3. **`NetworkTransport` (Firebase)** + `MoveMessage` serialization.
4. **`Lobby` / `Matchmaker`** (create / join-by-code / start) + `MatchConfig`.
5. **`MatchClient`** wiring into the Android `GameViewModel`; **`DesyncGuard`**.
6. **Reconnect-by-replay** and the disconnect policy.

## Open questions

- **Disconnect policy** — pause-and-wait vs. timeout-to-forfeit for the *other*
  players (default proposed above).
- **`AbilityPhase`** — the turn-based priority loop is still unbuilt
  ([phases.md](phases.md), [architecture.md](architecture.md)). It is the one
  phase with genuine turn-by-turn cross-player interaction and will exercise the
  networking hardest; it should be built alongside (or before) online play.
- **Match size changes mid-lobby** — whether late joins are allowed before Start
  (proposed: no; the seated list freezes at Start).
