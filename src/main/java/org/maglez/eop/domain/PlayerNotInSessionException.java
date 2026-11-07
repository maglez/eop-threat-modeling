package org.maglez.eop.domain;

import java.util.UUID;

/**
 * Raised when a recognised player asks to act in a session they hold no seat in.
 *
 * <p>Distinct from {@link NotYourSeatException}, and the distinction is the whole
 * point of having two types. {@code NotYourSeatException} is a member of this
 * session reaching for another member's seat: the caller is entitled to know the
 * session exists, so the refusal is candid and answered as 403. This exception is
 * a stranger to the session — a real player, with a real credential, from some
 * other session or from none — and such a caller is entitled to know nothing at
 * all, including whether the session they named exists.
 *
 * <p>So this is answered as a <strong>404 whose body is identical to
 * {@link SessionNotFoundException}'s</strong>, and the identity is the security
 * property rather than a convenience. A 403 here would confirm that the guessed
 * identifier names a real session, which turns the endpoint into an oracle for
 * enumerating live games; a 404 with its own wording would do the same thing more
 * slowly. Only a response indistinguishable from "no such session" reveals
 * nothing.
 *
 * <p>The message is therefore a byte-for-byte copy of
 * {@code SessionNotFoundException}'s, because the handler that maps both uses the
 * exception's own message as the problem detail. Changing the wording in one place
 * and not the other silently reopens the disclosure, which is why
 * {@code GlobalExceptionHandlerTest} asserts equality of the two problem details
 * rather than merely that both are 404.
 *
 * <p>The session identifier is the only state carried, deliberately. A field
 * naming the player, the seat, or which check failed would eventually be logged
 * into a response by a well-meaning change; a field that does not exist cannot be.
 * The player and the reason belong in the server's own logs, where the operator
 * can see them and the caller cannot.
 *
 * <p>A plain Java exception with no Spring imports, per ADR-005. Mapping it to an
 * HTTP status is the interface layer's job; the domain does not know HTTP exists.
 */
public class PlayerNotInSessionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID sessionId;

    /**
     * Creates the exception for a session the caller holds no seat in.
     *
     * <p>The message is identical to {@link SessionNotFoundException}'s by
     * construction. It must stay that way; see the class comment.
     *
     * @param sessionId the session the caller named
     */
    public PlayerNotInSessionException(final UUID sessionId) {
        super("No session found with identifier " + sessionId);
        this.sessionId = sessionId;
    }

    /**
     * The session the caller named.
     *
     * @return the requested identifier
     */
    public UUID sessionId() {
        return sessionId;
    }
}
