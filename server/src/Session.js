/**
 * One connected client. Holds the socket and where the player currently is:
 * either waiting in the matchmaking queue, or seated in a match. Carries no game
 * state — the game runs entirely on the devices.
 */
export class Session {
  constructor(ws, playerId) {
    this.ws = ws;
    this.playerId = playerId;
    this.name = "Player";
    // Set while queueing:
    this.queuedSize = null; // desired table size (2..4) while in the queue, else null
    // Set once seated in a match:
    this.matchId = null;
    this.seat = null;
  }

  inQueue() {
    return this.queuedSize !== null;
  }

  inMatch() {
    return this.matchId !== null;
  }

  send(message) {
    // ws.OPEN === 1; guard so a send to a half-closed socket never throws.
    if (this.ws && this.ws.readyState === 1) {
      this.ws.send(JSON.stringify(message));
    }
  }
}
