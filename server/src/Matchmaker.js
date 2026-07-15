import { MatchmakingQueue } from "./MatchmakingQueue.js";
import { Match } from "./Match.js";
import { newMatchId, newSeed } from "./ids.js";

const MIN_SIZE = 2;
const MAX_SIZE = 4;

/**
 * The coordinator: owns the matchmaking queue and the live matches, and routes
 * every client message to the right place. This is the whole "server" as far as
 * game flow is concerned — and it still contains no game rules, only player
 * grouping and move sequencing.
 */
export class Matchmaker {
  constructor() {
    this.queue = new MatchmakingQueue();
    this.matches = new Map(); // matchId -> Match
  }

  /** A player asks for a [size]-player table. Auto-forms a match once enough wait. */
  handleQueue(session, size) {
    if (session.inMatch()) {
      session.send({ type: "error", message: "already in a match" });
      return;
    }
    const n = clampSize(size);
    if (session.inQueue()) this.queue.remove(session);
    this.queue.enqueue(session, n);
    session.send({ type: "queued", players: n, waiting: this.queue.waiting(n) });
    this.tryForm(n);
  }

  /** Form as many full tables as the queue currently allows for [size]. */
  tryForm(size) {
    let table = this.queue.takeTable(size);
    while (table) {
      const match = new Match(newMatchId(), newSeed(), table);
      this.matches.set(match.id, match);
      match.announce();
      table = this.queue.takeTable(size);
    }
  }

  /** A player cancels while still waiting. */
  handleCancel(session) {
    this.queue.remove(session);
    session.send({ type: "cancelled" });
  }

  /** A lockstep input from a seated player — sequenced and relayed to the table. */
  handleMove(session, payload) {
    const match = session.inMatch() ? this.matches.get(session.matchId) : null;
    if (!match) {
      session.send({ type: "error", message: "not in a match" });
      return;
    }
    match.submitMove(session.seat, payload);
  }

  /**
   * A returning player reattaches to its seat and gets the full move log to replay.
   * Identity is the server-issued playerId from the original `matched` config.
   */
  handleReconnect(session, matchId, playerId) {
    const match = this.matches.get(matchId);
    if (!match) {
      session.send({ type: "error", message: "no such match" });
      return;
    }
    const seat = match.players.find((p) => p.id === playerId)?.seat;
    if (seat === undefined) {
      session.send({ type: "error", message: "not a member of that match" });
      return;
    }
    session.playerId = playerId;
    session.name = match.players[seat].name;
    match.reattach(seat, session);
  }

  /** A socket closed: drop it from the queue, or detach its seat (keep the match). */
  handleDisconnect(session) {
    if (session.inQueue()) this.queue.remove(session);
    if (session.inMatch()) {
      const match = this.matches.get(session.matchId);
      if (match) {
        match.detach(session.seat);
        if (match.isAbandoned()) this.matches.delete(match.id);
      }
    }
  }
}

function clampSize(size) {
  const n = Number.isInteger(size) ? size : MIN_SIZE;
  return Math.min(MAX_SIZE, Math.max(MIN_SIZE, n));
}
