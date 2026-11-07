package org.maglez.eop.application.port.in;

/**
 * The kinds of change a session can announce.
 *
 * <p>Each constant carries the name that appears on the wire. That name is fixed
 * by the published contract in {@code docs/api/openapi.yml}, so it is kept beside
 * the constant rather than in a translation table in the delivery layer, where the
 * two could drift apart without anything failing.
 *
 * <p>Pure domain-adjacent type: no Spring, no Jakarta, no persistence annotations.
 *
 * <p>Every constant here is published. {@link #HAND_DEALT}, {@link #CARD_PLAYED},
 * {@link #TRICK_RESOLVED} and {@link #GAME_COMPLETED} were minted before anything
 * emitted them, because a wire name belongs to the contract and a client matching
 * on a name must keep working unchanged once the server starts sending it; EOP-14
 * Slice E wired the publisher into the three trick-play use cases, so they are
 * emitted now, and EOP-15 Slice C wires {@link #GAME_COMPLETED}. That asymmetry
 * is deliberate and survives: a later release may begin emitting a name declared
 * here, but it may never rename one.
 *
 * <p>They reach a subscriber only where {@code eop.features.trick-play} is on,
 * which is a fact about a deployment rather than about this type.
 */
public enum SessionEventType {

    /** A participant was seated in the lobby. */
    PLAYER_JOINED("player-joined"),

    /** The facilitator closed the lobby and play began. */
    GAME_STARTED("game-started"),

    /** The deck was dealt and every seated player now holds a hand. */
    HAND_DEALT("hand-dealt"),

    /** A player added a card to the current trick. */
    CARD_PLAYED("card-played"),

    /** A trick was resolved and the seat that took it is known. */
    TRICK_RESOLVED("trick-resolved"),

    /**
     * Every trick has been played and the score is final, or the facilitator
     * ended the session early. Either way the session is now
     * {@link org.maglez.eop.domain.SessionStatus#COMPLETED} and no further
     * play is possible.
     */
    GAME_COMPLETED("game-completed");

    private final String wireName;

    SessionEventType(final String wireName) {
        this.wireName = wireName;
    }

    /**
     * Returns the event name as it appears in the {@code event:} field of a
     * server-sent event frame.
     *
     * @return the wire name, lower case and hyphenated
     */
    public String wireName() {
        return wireName;
    }
}
