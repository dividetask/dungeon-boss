# Dungeon Boss — Matchmaking Server

A small, self-hosted WebSocket service that does two things for online play:

1. **Matchmaking** — players ask for a 2–4 player table and the server pairs
   whoever is waiting into a match **automatically**. No codes, no invites: the
   app finds opponents, the player does not.
2. **Lockstep relay** — once a table forms, the server sequences the players'
   move *inputs* and broadcasts them in order, so every device applies the same
   moves to its own copy of the game.

It runs **no game rules**. The Dungeon Boss engine lives on the devices; this
service only groups players and orders messages. See
[../docs/networking.md](../docs/networking.md) for the overall design and why it
is non-authoritative.

## Run it

```sh
cd server
npm install
npm start            # listens on $PORT (default 8080)
npm test             # runs the matchmaking / relay / reconnect tests
```

`GET /` (or `/health`) returns `200 ok` for load balancers and uptime checks.
Everything else happens over the WebSocket on the same port.

## Deploy it

It is a single stateless Node process (state is in memory, per running instance).

- **Any host with Node ≥ 18**: `PORT=8080 node src/index.js` behind a TLS
  terminator (nginx/Caddy) so phones connect over `wss://`.
- **Fly.io / Render / Railway / a VPS**: deploy the `server/` folder, set `PORT`,
  expose the port. No database, no build step.
- **Docker**: `node:20-alpine`, `npm ci --omit=dev`, `CMD ["node","src/index.js"]`.

Because matches live in one process's memory, run a **single instance** to start.
Scaling to several instances later needs sticky routing (all players of a match
on the same instance) or a shared store — out of scope for now.

## Wire protocol

JSON text frames over one WebSocket per player. A player id is issued on connect
and is how a dropped player reconnects to its seat.

### client → server

| type | fields | meaning |
|------|--------|---------|
| `queue` | `name`, `players` (2–4) | Join matchmaking for a table of this size. |
| `cancel` | — | Leave the queue (only while still waiting). |
| `move` | `decisionId`, `choiceId`, `target?` | A lockstep input (the local player's answer to a decision). |
| `reconnect` | `matchId`, `playerId` | Rejoin a match after dropping; the server replays the move log. |
| `ping` | — | Keepalive; server replies `pong`. |

### server → client

| type | fields | meaning |
|------|--------|---------|
| `hello` | `playerId` | Your id (save it for `reconnect`). Sent on connect. |
| `queued` | `players`, `waiting` | You are in the queue; how many are waiting so far. |
| `cancelled` | — | You left the queue. |
| `matched` | `config` | A table formed — start the game. See below. |
| `move` | `seq`, `player`, `decisionId`, `choiceId`, `target` | An ordered move to apply to your local game. |
| `log` | `matchId`, `moves[]` | The full ordered move log (reply to `reconnect`); replay it to catch up. |
| `peer` | `event` (`disconnected`/`reconnected`), `player` | A seat's connection changed. |
| `pong` | — | Reply to `ping`. |
| `error` | `message` | Something was rejected. |

### `matched` config

```json
{
  "matchId": "uuid",
  "seed": 44675031607119,
  "players": [ { "seat": 0, "id": "uuid", "name": "Ada" },
               { "seat": 1, "id": "uuid", "name": "Bo" } ],
  "you": 0
}
```

The client builds `Game.seeded(seed)` with these players in seat order. The `you`
seat is the local human; every other seat is driven by a `RemoteAgent` that waits
for that seat's `move` messages. The **server mints the shared `seed`** — it is the
neutral party at match start — so all devices shuffle identically.

## How a match flows

```
connect            → hello { playerId }
queue{players:2}   → queued { waiting }        (repeat per player)
   … server pairs the waiting players …
                   ← matched { config }         (to every seat)
build Game.seeded(config.seed); local seat = config.you
local player decides → move{...}  → server stamps seq, broadcasts →  move{seq,...} to ALL seats
apply moves in seq order on every device  (lockstep)
drop → reconnect{matchId,playerId} → log{moves} → replay → resume
```

The server assigns a single global `seq` per match, so every device receives the
identical ordered move stream — that total order is what keeps the games in
lockstep. The server never inspects a move's meaning; an illegal move is caught by
the receiving client's own engine (it asserts the move answers the decision it is
waiting on).

## Layout

```
server/
├── package.json
└── src/
    ├── index.js            # WebSocket server + message routing (the only I/O)
    ├── Matchmaker.js       # coordinator: routes messages, forms matches
    ├── MatchmakingQueue.js # the waiting pool; auto-forms tables by size (FIFO)
    ├── Match.js            # a formed table: seats, seed, ordered move log, relay
    ├── Session.js          # one connected client (socket + where it is)
    └── ids.js              # match id / player id / shared seed
```

One class, one job — mirroring the game engine's design in
[../docs/architecture.md](../docs/architecture.md).
