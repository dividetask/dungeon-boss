import { createServer as createHttpServer } from "node:http";
import { WebSocketServer } from "ws";
import { Matchmaker } from "./Matchmaker.js";
import { Session } from "./Session.js";
import { newPlayerId } from "./ids.js";

/**
 * The Dungeon Boss matchmaking + lockstep relay server.
 *
 * Clients connect over WebSocket, ask to be matched into a 2–4 player table, and
 * once matched exchange only their move *inputs* — never game state. The server
 * pairs waiting players automatically (no codes) and sequences the moves. It runs
 * NO game rules; the game engine lives on the devices (see docs/networking.md).
 *
 * Wire protocol (JSON text frames) — see server/README.md for the full contract.
 *   client → server: queue | cancel | move | reconnect | ping
 *   server → client: hello | queued | cancelled | matched | move | log | peer | pong | error
 */
export function startServer({ port = 0, host = "0.0.0.0" } = {}) {
  const matchmaker = new Matchmaker();

  const http = createHttpServer((req, res) => {
    // A plain health check for load balancers / uptime pings.
    if (req.method === "GET" && (req.url === "/" || req.url === "/health")) {
      res.writeHead(200, { "content-type": "text/plain" });
      res.end("ok");
      return;
    }
    res.writeHead(404);
    res.end();
  });

  const wss = new WebSocketServer({ server: http });

  wss.on("connection", (ws) => {
    const session = new Session(ws, newPlayerId());
    // The player learns its own id up front so it can reconnect to its seat later.
    session.send({ type: "hello", playerId: session.playerId });

    ws.on("message", (data) => {
      let msg;
      try {
        msg = JSON.parse(data.toString());
      } catch {
        session.send({ type: "error", message: "malformed json" });
        return;
      }
      route(matchmaker, session, msg);
    });

    ws.on("close", () => matchmaker.handleDisconnect(session));
    ws.on("error", () => {}); // a socket error triggers 'close'; nothing extra to do
  });

  return new Promise((resolve) => {
    http.listen(port, host, () => {
      resolve({
        matchmaker,
        wss,
        http,
        port: http.address().port,
        close: () =>
          new Promise((done) => {
            // Force-close live client sockets first, otherwise http.close() waits
            // on them forever and never fires its callback.
            for (const client of wss.clients) client.terminate();
            wss.close(() => http.close(() => done()));
            http.closeAllConnections?.();
          }),
      });
    });
  });
}

/** Dispatch one parsed client message to the matchmaker. */
function route(matchmaker, session, msg) {
  switch (msg?.type) {
    case "queue":
      if (typeof msg.name === "string" && msg.name.trim()) session.name = msg.name.trim().slice(0, 40);
      matchmaker.handleQueue(session, msg.players);
      break;
    case "cancel":
      matchmaker.handleCancel(session);
      break;
    case "move":
      matchmaker.handleMove(session, msg);
      break;
    case "reconnect":
      matchmaker.handleReconnect(session, msg.matchId, msg.playerId);
      break;
    case "ping":
      session.send({ type: "pong" });
      break;
    default:
      session.send({ type: "error", message: `unknown message type: ${msg?.type}` });
  }
}

// Start listening when run directly (`npm start`), not when imported by a test.
const isMain = process.argv[1] && import.meta.url === `file://${process.argv[1]}`;
if (isMain) {
  const port = Number(process.env.PORT) || 8080;
  startServer({ port }).then(({ port }) => {
    // eslint-disable-next-line no-console
    console.log(`Dungeon Boss matchmaker listening on :${port}`);
  });
}
