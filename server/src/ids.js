import { randomUUID } from "node:crypto";

/** A short, unguessable match id (matches are found by matchmaking, not typed). */
export function newMatchId() {
  return randomUUID();
}

/** A stable per-player id, handed back on `matched` so a client can reconnect to its seat. */
export function newPlayerId() {
  return randomUUID();
}

/**
 * The shared RNG seed for one match. The server is the neutral party that mints
 * it once and sends it to every device, so all clients build the same
 * `Game.seeded(seed)` (see docs/networking.md). Kept within JS's safe integer
 * range so it survives JSON round-tripping and parses cleanly into a Kotlin Long.
 */
export function newSeed() {
  return Math.floor(Math.random() * Number.MAX_SAFE_INTEGER);
}
