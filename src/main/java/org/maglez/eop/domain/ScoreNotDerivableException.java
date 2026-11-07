package org.maglez.eop.domain;

import java.util.UUID;

/**
 * Raised when a score cannot be derived because the game it would be derived from contradicts itself.
 *
 * <p>Every refusal gathered here means the same thing to a caller: nothing. A play attributed to a
 * player who holds no seat, two tricks claiming one sequence number, a player seated twice — none of
 * these is something a request can be reworded to avoid, because none of them is about the request.
 * They are about stored rows that disagree with each other, and the only honest answer is that the
 * server could not complete the work.
 *
 * <p>That is why there is one exception type and not eight. RFC 9457 wants a problem type per
 * condition a client can act on, and there is exactly one such condition here, with eight proximate
 * causes. Eight types would be eight names for one response. The {@link Reason} is what keeps the
 * causes apart where keeping them apart is worth something: in a log, and in a test that wants to
 * pin which guard fired without matching the wording of a message.
 *
 * <p>The message names the identifiers involved, and from here it only ever reaches a log. The
 * boundary answers with a fixed string, because a trick or player identifier belonging to a session
 * the caller may not even be able to see is exactly the sort of internal key that should not be
 * echoed back to whoever asked (ADR-030, ADR-031).
 *
 * <p>Not everything the scoring types refuse arrives here. {@code ScoredPlay}'s seat range and
 * {@code Standing}'s bounds stay {@link IllegalArgumentException}: they guard a constructor that no
 * route can reach, they name no identifier, and a caller who trips one has made a programming error
 * rather than met a contradiction in stored data.
 */
public class ScoreNotDerivableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Reason reason;

    private ScoreNotDerivableException(final Reason reason, final String message) {
        super(message);
        this.reason = reason;
    }

    /**
     * Refusal for a score sheet with nobody seated on it.
     *
     * @return the exception
     */
    public static ScoreNotDerivableException noPlayers() {
        return new ScoreNotDerivableException(Reason.NO_PLAYERS, "A score sheet needs at least one seated player");
    }

    /**
     * Refusal for a play whose player holds no seat in this game.
     *
     * @param trickPlayId identifier of the play that cannot be attributed
     * @return the exception
     */
    public static ScoreNotDerivableException playByUnseatedPlayer(final UUID trickPlayId) {
        return new ScoreNotDerivableException(Reason.PLAY_BY_UNSEATED_PLAYER,
                "Play " + trickPlayId + " was made by a player who is not seated");
    }

    /**
     * Refusal for a play that names one seat while its player sits at another.
     *
     * @param trickPlayId identifier of the play
     * @param namedSeat   the seat the play names
     * @param seatedAt    the seat its player actually holds
     * @return the exception
     */
    public static ScoreNotDerivableException playSeatMismatch(final UUID trickPlayId, final int namedSeat, final int seatedAt) {
        return new ScoreNotDerivableException(Reason.PLAY_SEAT_MISMATCH,
                "Play " + trickPlayId + " names seat " + namedSeat + " but its player is seated at " + seatedAt);
    }

    /**
     * Refusal for a play scored against a player who did not make it.
     *
     * @param trickPlayId identifier of the play
     * @param playerId    identifier of the player it was scored against
     * @return the exception
     */
    public static ScoreNotDerivableException playNotByThisPlayer(final UUID trickPlayId, final UUID playerId) {
        return new ScoreNotDerivableException(Reason.PLAY_NOT_BY_THIS_PLAYER,
                "Play " + trickPlayId + " was not made by player " + playerId);
    }

    /**
     * Refusal for one player appearing twice among the seated players.
     *
     * @param playerId identifier of the duplicated player
     * @return the exception
     */
    public static ScoreNotDerivableException playerSeatedTwice(final UUID playerId) {
        return new ScoreNotDerivableException(Reason.PLAYER_SEATED_TWICE, "Player " + playerId + " is seated twice");
    }

    /**
     * Refusal for one trick appearing twice in the same game.
     *
     * @param trickId identifier of the duplicated trick
     * @return the exception
     */
    public static ScoreNotDerivableException trickRepeated(final UUID trickId) {
        return new ScoreNotDerivableException(Reason.TRICK_REPEATED, "Trick " + trickId + " appears twice");
    }

    /**
     * Refusal for two tricks claiming the same place in the order of play.
     *
     * @param sequence the sequence number claimed twice
     * @return the exception
     */
    public static ScoreNotDerivableException sequenceRepeated(final int sequence) {
        return new ScoreNotDerivableException(Reason.SEQUENCE_REPEATED, "Two tricks claim sequence " + sequence);
    }

    /**
     * Refusal for a points enquiry about a player who holds no seat in this game.
     *
     * @param playerId identifier of the player asked about
     * @return the exception
     */
    public static ScoreNotDerivableException playerNotSeated(final UUID playerId) {
        return new ScoreNotDerivableException(Reason.PLAYER_NOT_SEATED, "No player " + playerId + " is seated in this game");
    }

    /**
     * Which contradiction was found.
     *
     * @return the reason the score could not be derived
     */
    public Reason reason() {
        return reason;
    }

    /**
     * The contradictions a score sheet can refuse to be derived from.
     *
     * <p>These are diagnostic, not client-facing. Nothing at the boundary branches on them and no
     * response names one; they exist so that a log line and a test can say which guard fired.
     */
    public enum Reason {

        /** No player was seated on the sheet at all. */
        NO_PLAYERS,

        /** A play was made by somebody who holds no seat in this game. */
        PLAY_BY_UNSEATED_PLAYER,

        /** A play names one seat while its player sits at another. */
        PLAY_SEAT_MISMATCH,

        /** A play was scored against a player who did not make it. */
        PLAY_NOT_BY_THIS_PLAYER,

        /** One player appears twice among the seated players. */
        PLAYER_SEATED_TWICE,

        /** One trick appears twice in the same game. */
        TRICK_REPEATED,

        /** Two tricks claim the same place in the order of play. */
        SEQUENCE_REPEATED,

        /** Points were asked for a player who holds no seat in this game. */
        PLAYER_NOT_SEATED
    }
}
