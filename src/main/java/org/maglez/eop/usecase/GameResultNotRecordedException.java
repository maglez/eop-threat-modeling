package org.maglez.eop.usecase;

import java.util.Objects;
import java.util.UUID;

/**
 * Raised when a session is {@code COMPLETED} but no {@code GameResult} row has been recorded for it.
 *
 * <p>The session exists, the caller is seated at it, and it is in the one state the leaderboard is served
 * in — there is simply no result aggregate to confirm. That is a different fact from "no such session",
 * and it used to share {@code SessionNotFoundException} with it: one line of {@link GetLeaderboardUseCase}
 * threw that type for two unrelated conditions, its own {@code @throws} tag admitted as much, and a seated
 * player was told "No session found with identifier ..." about a session they were demonstrably sitting in.
 *
 * <p>Three paths reach this state, and only one of them is a timing window:
 *
 * <ul>
 *   <li><strong>The facilitator ended the session early.</strong> {@link EndSessionUseCase} records the
 *       completion and does not write a result, by design — it stops play before every card is played, so
 *       there is no final standing to record. No result row will ever appear for that session.</li>
 *   <li><strong>The result write failed.</strong> On the automatic path the write is best-effort:
 *       {@link ResolveTrickUseCase} and {@link PlayCardUseCase} both catch and log any exception from
 *       {@link PersistGameResultUseCase} rather than failing the trick that was already durably committed.
 *       Nothing retries it, so that row will not appear either.</li>
 *   <li><strong>A read landed mid-completion.</strong> Completion and the result write are two steps, so a
 *       leaderboard read between them finds this state and a later read succeeds.</li>
 * </ul>
 *
 * <p>Hence "not recorded" rather than "not yet persisted": two of the three causes are permanent, and a type
 * name promising that a retry will eventually work would be untrue in the common case. The player is still
 * offered a retry by the front end, which is right for the third cause and harmless for the other two.
 *
 * <p>Splitting this out of {@code SessionNotFoundException} discloses nothing. Membership is resolved before
 * the repository is read: a caller who is not seated is stopped by {@link ResolvePlayerUseCase} with
 * {@code PlayerNotRecognisedException} and never reaches this point. Only a seated player can observe this
 * exception, and a seated player already knows their own session exists.
 *
 * <p><strong>It still maps to 404</strong>, exactly as {@code SessionNotFoundException} does, so no client
 * changes: the front end stopped ejecting the player on a leaderboard 404 in EOP-82 and now ejects only on
 * 403 (ADR-042). Only the problem title and detail differ, so the two conditions are finally distinguishable
 * without moving the status and invalidating that handling.
 *
 * <p>Placed in {@code org.maglez.eop.usecase} rather than beside {@code SessionNotFoundException} and
 * {@code GameNotCompletedException} in {@code org.maglez.eop.entity}, deliberately. Those two are statements
 * about identity and lifecycle state, which an entity can make without knowing that anything is stored
 * anywhere. "No row has been recorded" is a statement about a repository port — {@link GameResultRepository}
 * lives in this package — and the entity layer must not know persistence exists. This package already holds
 * {@code RateLimitedException} and two siblings, so the placement introduces nothing new.
 *
 * <p>A plain Java exception with no Spring imports, per ADR-005. Mapping it to an HTTP status is the
 * interface layer's job; the domain does not know HTTP exists.
 */
public final class GameResultNotRecordedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID sessionId;

    /**
     * Creates the exception for a completed session that has no recorded result.
     *
     * @param sessionId the identifier of the completed session, never {@code null}
     */
    public GameResultNotRecordedException(final UUID sessionId) {
        super("Session " + Objects.requireNonNull(sessionId, "sessionId is required")
                + " is completed but has no recorded result");
        this.sessionId = sessionId;
    }

    /**
     * Returns the session whose result was not recorded.
     *
     * @return the session identifier, never {@code null}
     */
    public UUID sessionId() {
        return sessionId;
    }
}
