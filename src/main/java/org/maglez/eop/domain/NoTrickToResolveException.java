package org.maglez.eop.domain;

import java.util.UUID;

/**
 * Raised when a session is asked to resolve a trick and no trick has been opened.
 *
 * <p>The state is ordinary, not exotic: it is exactly what a session looks like
 * between the deal and the leader's first card. Any seated player may ask for a
 * resolution — resolving is a mechanical consequence of a complete trick, not a
 * facilitator privilege — so an impatient client polling a resolve endpoint
 * immediately after the deal reaches this on the first try.
 *
 * <p>Answered 409 rather than 404. Nothing is missing that the caller named: the
 * session exists, the hands exist, and the resource the caller is asking about
 * will exist as soon as somebody leads. A 404 would tell an honest client its
 * session identifier was wrong, which is the one thing it can be sure it is not.
 *
 * <p>Kept distinct from {@link HandNotDealtException}, which is the earlier state
 * — no hands at all — and from {@link TrickNotCompleteException}, which is the
 * later one, where a trick exists but is still waiting on a seat. Three states,
 * three types, because a client that wants to show "waiting for the lead" rather
 * than "waiting for Dana" cannot tell them apart from a shared type.
 *
 * <p>Raised by the resolve use case, which is the only place that can see it: the
 * repository port is asked for the current trick and answers an empty optional,
 * and an empty optional is not an error until somebody has decided what they
 * wanted it for.
 *
 * <p>A plain Java exception with no Spring imports, per ADR-005.
 */
public class NoTrickToResolveException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID sessionId;

    /**
     * Creates the exception for a session with no trick in progress.
     *
     * @param sessionId the session that has no trick to resolve
     */
    public NoTrickToResolveException(final UUID sessionId) {
        super("Session " + sessionId + " has no trick to resolve");
        this.sessionId = sessionId;
    }

    /**
     * The session that has no trick in progress.
     *
     * @return the session identifier
     */
    public UUID sessionId() {
        return sessionId;
    }
}
