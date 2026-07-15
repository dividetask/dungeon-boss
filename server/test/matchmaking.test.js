import { test } from "node:test";
import assert from "node:assert/strict";
import { WebSocket } from "ws";
import { startServer } from "../src/index.js";

/** Open a client and collect messages; `next(type)` waits for the next of a type. */
function client(port) {
  const ws = new WebSocket(`ws://127.0.0.1:${port}`);
  const inbox = [];
  const waiters = [];
  ws.on("message", (data) => {
    const msg = JSON.parse(data.toString());
    inbox.push(msg);
    for (let i = waiters.length - 1; i >= 0; i--) {
      if (waiters[i].type === msg.type) {
        waiters.splice(i, 1)[0].resolve(msg);
      }
    }
  });
  return {
    ws,
    open: () => new Promise((res) => ws.on("open", res)),
    send: (m) => ws.send(JSON.stringify(m)),
    // Resolve with an already-received message of this type, or wait for the next.
    next: (type) =>
      new Promise((resolve) => {
        const found = inbox.find((m) => m.type === type && !m._claimed);
        if (found) {
          found._claimed = true;
          resolve(found);
        } else {
          waiters.push({ type, resolve });
        }
      }),
    close: () => ws.close(),
  };
}

test("two players are auto-matched into a table with a shared seed", async () => {
  const server = await startServer({ port: 0 });
  const a = client(server.port);
  const b = client(server.port);
  await a.open();
  await b.open();

  // Each client is told its own player id on connect.
  const helloA = await a.next("hello");
  const helloB = await b.next("hello");
  assert.ok(helloA.playerId && helloB.playerId);

  // Both ask for a 2-player table — no codes exchanged; the server pairs them.
  a.send({ type: "queue", name: "Ada", players: 2 });
  b.send({ type: "queue", name: "Bo", players: 2 });

  const matchedA = await a.next("matched");
  const matchedB = await b.next("matched");

  // Same match, same seed, same roster, distinct seats.
  assert.equal(matchedA.config.matchId, matchedB.config.matchId);
  assert.equal(matchedA.config.seed, matchedB.config.seed);
  assert.equal(typeof matchedA.config.seed, "number");
  assert.deepEqual(
    matchedA.config.players.map((p) => p.name),
    ["Ada", "Bo"],
  );
  assert.notEqual(matchedA.config.you, matchedB.config.you);
  assert.deepEqual([matchedA.config.you, matchedB.config.you].sort(), [0, 1]);

  await server.close();
});

test("a 3-player table only forms once three players are waiting", async () => {
  const server = await startServer({ port: 0 });
  const a = client(server.port);
  const b = client(server.port);
  await a.open();
  await b.open();
  await a.next("hello");
  await b.next("hello");

  a.send({ type: "queue", name: "A", players: 3 });
  b.send({ type: "queue", name: "B", players: 3 });

  // Two waiting for a 3-table: no match yet. Give the server a beat to (not) form one.
  await new Promise((r) => setTimeout(r, 50));
  assert.equal(a.ws.readyState, WebSocket.OPEN);

  const c = client(server.port);
  await c.open();
  await c.next("hello");
  c.send({ type: "queue", name: "C", players: 3 });

  const [mA, mB, mC] = await Promise.all([a.next("matched"), b.next("matched"), c.next("matched")]);
  assert.equal(mA.config.matchId, mB.config.matchId);
  assert.equal(mB.config.matchId, mC.config.matchId);
  assert.equal(mA.config.players.length, 3);

  await server.close();
});

test("moves are sequenced and broadcast in order to every seat", async () => {
  const server = await startServer({ port: 0 });
  const a = client(server.port);
  const b = client(server.port);
  await a.open();
  await b.open();
  await a.next("hello");
  await b.next("hello");
  a.send({ type: "queue", name: "Ada", players: 2 });
  b.send({ type: "queue", name: "Bo", players: 2 });
  const matchedA = await a.next("matched");
  const matchId = matchedA.config.matchId;
  const seatA = matchedA.config.you;

  // Seat A plays a move; BOTH clients receive it, stamped seq 0, echoed to the sender.
  a.send({ type: "move", matchId, decisionId: "CHOOSE_BOSS", choiceId: "boss_goblin", target: null });
  const gotA = await a.next("move");
  const gotB = await b.next("move");

  assert.equal(gotA.seq, 0);
  assert.equal(gotB.seq, 0);
  assert.equal(gotA.player, seatA);
  assert.equal(gotB.choiceId, "boss_goblin");
  assert.equal(gotB.decisionId, "CHOOSE_BOSS");
  assert.deepEqual(gotA, gotB); // every seat sees the identical ordered move

  await server.close();
});

test("a reconnecting player replays the full move log", async () => {
  const server = await startServer({ port: 0 });
  const a = client(server.port);
  const b = client(server.port);
  await a.open();
  await b.open();
  const helloB = await b.next("hello");
  await a.next("hello");
  a.send({ type: "queue", name: "Ada", players: 2 });
  b.send({ type: "queue", name: "Bo", players: 2 });
  const matchedA = await a.next("matched");
  await b.next("matched");
  const matchId = matchedA.config.matchId;

  // A couple of moves happen, then B drops.
  a.send({ type: "move", matchId, decisionId: "CHOOSE_BOSS", choiceId: "boss_goblin" });
  await a.next("move");
  a.send({ type: "move", matchId, decisionId: "PLACE_FIRST_ROOM", choiceId: "room_pit", target: 0 });
  await a.next("move");
  b.close();
  await new Promise((r) => setTimeout(r, 50));

  // B reconnects with its player id and replays the log to rebuild state.
  const b2 = client(server.port);
  await b2.open();
  await b2.next("hello");
  b2.send({ type: "reconnect", matchId, playerId: helloB.playerId });
  const log = await b2.next("log");

  assert.equal(log.matchId, matchId);
  assert.equal(log.moves.length, 2);
  assert.deepEqual(log.moves.map((m) => m.seq), [0, 1]);
  assert.equal(log.moves[1].choiceId, "room_pit");

  await server.close();
});
