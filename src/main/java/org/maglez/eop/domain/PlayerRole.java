package org.maglez.eop.domain;

/**
 * What a player is allowed to do beyond playing cards.
 *
 * <p>This is not an authorisation model. There is no authentication in this
 * system (ADR-015), so a role cannot be trusted to mean more than it says: the
 * facilitator holds exactly one privilege, starting play, and it is not
 * transferable. Anything else a facilitator does, a participant may also do.
 */
public enum PlayerRole {

    /** Created the session and is the only player who can start it. */
    FACILITATOR,
    /** Joined with a code. Plays the game and nothing more. */
    PARTICIPANT;

    /**
     * Whether a player in this role may close the lobby and start play.
     *
     * @return true only for {@link #FACILITATOR}
     */
    public boolean canStartPlay() {
        return this == FACILITATOR;
    }
}
