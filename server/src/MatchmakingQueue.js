/**
 * The pool of players waiting to be matched. This is what makes matchmaking
 * automatic: players do not exchange codes — they ask for a table of a given
 * size and the queue forms one as soon as enough compatible players are waiting.
 *
 * One FIFO queue per desired table size (2, 3, or 4), so a player who wants a
 * 3-player game is only ever matched with other players who want a 3-player game.
 * FIFO keeps it fair: whoever waited longest is seated first.
 */
export class MatchmakingQueue {
  constructor() {
    this.bySize = new Map(); // size -> [Session] in arrival order
  }

  /** Add a session waiting for a [size]-player table. */
  enqueue(session, size) {
    session.queuedSize = size;
    const list = this.bySize.get(size) ?? [];
    list.push(session);
    this.bySize.set(size, list);
  }

  /** Remove a session from whatever queue it is in (cancel / disconnect). No-op if absent. */
  remove(session) {
    const size = session.queuedSize;
    if (size === null) return;
    const list = this.bySize.get(size);
    if (list) {
      const i = list.indexOf(session);
      if (i >= 0) list.splice(i, 1);
    }
    session.queuedSize = null;
  }

  /** How many are currently waiting for a [size]-player table. */
  waiting(size) {
    return this.bySize.get(size)?.length ?? 0;
  }

  /**
   * If a full table can be formed for [size], remove and return exactly [size]
   * longest-waiting sessions; otherwise return null. The caller turns them into a
   * Match. Each returned session is cleared out of the queue.
   */
  takeTable(size) {
    const list = this.bySize.get(size);
    if (!list || list.length < size) return null;
    const table = list.splice(0, size);
    for (const s of table) s.queuedSize = null;
    return table;
  }
}
