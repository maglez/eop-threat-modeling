package org.maglez.eop.domain;

import java.util.UUID;

/**
 * Raised when a trick is asked to resolve to a winning play that was not made into
 * it.
 *
 * <p>This one has no storage backstop and never will. ADR-023 measured both attacks
 * and found them accepted by the schema: {@code fk_trick_winner_play} confines the
 * winning play to the {@code trick_play} table and to nothing else, so a play from a
 * different trick, and even a play from a different session, both satisfy it. The
 * composite key that would confine it — {@code trick (id, winner_play_id)} referencing
 * {@code trick_play (trick_id, id)} — cannot be expressed, because a composite foreign
 * key with {@code ON DELETE SET NULL} nulls every referencing column including
 * {@code trick.id}, which is the primary key and not nullable. PostgreSQL 15 can null a
 * named subset; Liquibase cannot express that, and whether H2 accepts it is unmeasured.
 *
 * <p>So the check in the resolve use case is the only check that exists. That is the
 * reason this exception's Javadoc says so at length: a guard with no second line of
 * defence has to be documented as such, or the next person to read the schema will
 * assume the foreign key means more than it does.
 *
 * <p>Answered as <strong>422</strong> and deliberately not 409. A conflict invites the
 * caller to re-read and retry, and retrying cannot help here: the play named does not
 * belong to this trick and will not come to belong to it however many times the request
 * is repeated. The request is well-formed and its content is wrong, which is what 422
 * means.
 *
 * <p>Nothing throws this yet. The trick-play error vocabulary was shipped as one piece,
 * ahead of the use cases that raise it, and {@code ResolveTrickPort} is the intended
 * and only thrower. Recorded here so that a reader does not mistake a declared exception
 * and a mapped status for a guard that is already running.
 *
 * <p>The storage adapter's own detection of a corrupt {@code winner_play_id} is
 * deliberately <em>not</em> this exception. That case is a column of ours holding a play
 * from another trick, which is our corruption and answered as a server fault; raising a
 * 422 for it would blame the caller for a request that was well-formed and honest.
 *
 * <p>A plain Java exception with no Spring imports, per ADR-005.
 */
public class WinningPlayNotInTrickException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID trickId;

    private final UUID playId;

    /**
     * Creates the exception for a winning play from elsewhere.
     *
     * @param trickId the trick being resolved
     * @param playId  the play named as the winner
     */
    public WinningPlayNotInTrickException(final UUID trickId, final UUID playId) {
        super("Play " + playId + " was not made into trick " + trickId);
        this.trickId = trickId;
        this.playId = playId;
    }

    /**
     * The trick being resolved.
     *
     * @return the trick identifier
     */
    public UUID trickId() {
        return trickId;
    }

    /**
     * The play named as the winner.
     *
     * @return the play identifier
     */
    public UUID playId() {
        return playId;
    }
}
