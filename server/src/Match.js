/**
 * A formed table of 2–4 players and the ordered log of their moves. The server's
 * only in-game job is to be the **sequencer**: it stamps every incoming move with
 * a monotonic `seq` and broadcasts the ordered stream to all seats, so every
 * device applies moves in the same order and stays in lockstep. It never inspects
 * or validates a move against the rules — the game runs on the devices.
 *
 * The move log is retained for the life of the match so a device that drops can
 * reconnect and replay from the start (see docs/networking.md, reconnect-by-replay).
 */
export class Match {
  constructor(id, seed, sessions) {
    this.id = id;
    this.seed = seed;
    // Seats are assigned by arrival order; seat 0 is the first-queued player.
    this.players = sessions.map((s, seat) => ({ seat, id: s.playerId, name: s.name }));
    this.seats = new Map(); // seat -> Session (its live socket; may be detached on disconnect)
    sessions.forEach((s, seat) => {
      s.matchId = id;
      s.seat = seat;
      this.seats.set(seat, s);
    });
    this.log = []; // ordered [{ seq, player, decisionId, choiceId, target }]
    this.nextSeq = 0;
  }

  /** The `matched` config a given seat needs to build its local game. */
  configFor(seat) {
    return {
      type: "matched",
      config: {
        matchId: this.id,
        seed: this.seed,
        players: this.players, // [{ seat, id, name }]
        you: seat, // which seat is local (a human); the others are RemoteAgents
      },
    };
  }

  /** Send the `matched` config to every seated player. */
  announce() {
    for (const [seat, session] of this.seats) {
      session.send(this.configFor(seat));
    }
  }

  /**
   * Stamp a move from [fromSeat] with the next `seq`, retain it, and broadcast the
   * ordered move to every connected seat (including the sender, so all devices
   * apply the identical ordered stream). Rule-blind: the payload is forwarded as-is.
   */
  submitMove(fromSeat, payload) {
    const move = {
      type: "move",
      seq: this.nextSeq++,
      player: fromSeat,
      decisionId: payload.decisionId ?? null,
      choiceId: payload.choiceId ?? null,
      target: payload.target ?? null,
    };
    this.log.push(move);
    this.broadcast(move);
    return move;
  }

  broadcast(message) {
    for (const session of this.seats.values()) session.send(message);
  }

  /** Everyone except [seat] — used to tell peers about a disconnect/reconnect. */
  broadcastExcept(seat, message) {
    for (const [s, session] of this.seats) {
      if (s !== seat) session.send(message);
    }
  }

  /** Reattach a reconnecting player's new socket to its seat and replay the log. */
  reattach(seat, session) {
    session.matchId = this.id;
    session.seat = seat;
    this.seats.set(seat, session);
    session.send({ type: "log", matchId: this.id, moves: this.log });
    this.broadcastExcept(seat, { type: "peer", event: "reconnected", player: seat });
  }

  /** Mark a seat's socket gone (the player may reconnect later); tell the peers. */
  detach(seat) {
    const session = this.seats.get(seat);
    if (session) session.ws = null; // keep the seat; the move log survives for replay
    this.broadcastExcept(seat, { type: "peer", event: "disconnected", player: seat });
  }

  /** True once no seat has a live socket — the coordinator can drop the match. */
  isAbandoned() {
    for (const session of this.seats.values()) {
      if (session.ws && session.ws.readyState === 1) return false;
    }
    return true;
  }
}
