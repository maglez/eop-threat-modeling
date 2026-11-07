package org.maglez.eop.domain;

import java.util.UUID;

/**
 * Raised when a session is requested by an identifier no session has.
 *
 * <p>Deliberately candid, unlike {@link UnknownJoinCodeException}. A session
 * identifier is an unguessable UUID, so there is nothing to be gained by hiding
 * whether one exists — a caller who can name the identifier either had it or
 * guessed 122 bits. A join code is eight characters, so that lookup hides it.
 *
 * <p>That candour is scoped to <em>existence</em>, and does not extend to
 * membership. It rests on the identifier being unguessable, which says nothing
 * about a caller who holds a real identifier but no seat in the session it
 * names. Answering such a caller candidly would disclose the session's state to
 * someone entitled to none of it, so {@link PlayerNotInSessionException} is
 * raised instead and is mapped to a 404 whose body matches this one exactly —
 * see ADR-023, "the play use case derives the acting player from identity". The
 * reasoning above therefore justifies this exception's own message and not the
 * membership answer, which is the opposite trade-off for a different premise.
 *
 * <p>A plain Java exception with no Spring imports, per ADR-005. Mapping it to an
 * HTTP status is the interface layer's job; the domain does not know HTTP exists.
 */
public class SessionNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID sessionId;

    /**
     * Creates the exception for a specific unknown identifier.
     *
     * @param sessionId the identifier that matched no session
     */
    public SessionNotFoundException(final UUID sessionId) {
        super("No session found with identifier " + sessionId);
        this.sessionId = sessionId;
    }

    /**
     * The identifier that matched no session.
     *
     * @return the requested identifier
     */
    public UUID sessionId() {
        return sessionId;
    }
}
