package org.maglez.eop.domain;

/**
 * Where a session is in its life.
 *
 * <p>Stored as the constant name rather than the ordinal, so a database dump is
 * readable and reordering these constants is not a silent data migration.
 *
 * <p>Only {@link #LOBBY} accepts new players, and only {@link #LOBBY} can be
 * started. A session in {@link #IN_PROGRESS} advances to {@link #COMPLETED}
 * automatically when the last trick is resolved (EOP-15 Slice C), or
 * immediately when the facilitator calls the end-session endpoint.
 */
public enum SessionStatus {

    /** Players are gathering. Joining is open; play has not begun. */
    LOBBY,
    /** The facilitator has closed the lobby. Joining is refused from here on. */
    IN_PROGRESS,
    /** Every trick has been played and the score is final. */
    COMPLETED,
    /**
     * Given up on rather than finished. Written by the expiry sweep, and not
     * observable by a client via that path.
     *
     * <p>{@code SessionRepository.abandonAndDelete} sets this state and deletes
     * the row in the same transaction, so no fetch can observe it — the next
     * request for an expired session is a 404, not a session reporting
     * {@code ABANDONED}. The write exists so that the deletion has a reason
     * recorded against it rather than happening silently, and so that the sweep
     * can exclude rows it has already claimed.
     *
     * <p>Read that as a property of the one path that writes it, not of the state
     * itself. The sweep is the sole writer today and nothing structural enforces
     * that; a future caller that marked sessions for later reaping would make
     * this state observable, and no test would fail. The TypeScript mirror in
     * {@code ui/src/api.ts} therefore lists it and deliberately gives it no
     * branch, so that the day it becomes observable the gap shows up as a missing
     * branch on a type that already has the member (EOP-105).
     */
    ABANDONED;

    /**
     * Whether a player may join a session in this state.
     *
     * <p>Expressed as a question about the state rather than an equality check
     * scattered across use cases, so that adding a state forces this method to
     * be reconsidered in one place.
     *
     * @return true only in {@link #LOBBY}
     */
    public boolean acceptsNewPlayers() {
        return this == LOBBY;
    }
}
