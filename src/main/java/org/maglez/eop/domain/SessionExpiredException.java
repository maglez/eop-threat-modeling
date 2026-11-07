package org.maglez.eop.domain;

import java.util.UUID;

/**
 * Thrown when a request is made against a session whose {@code expires_at}
 * timestamp is in the past.
 *
 * <p>Pure domain exception — no Spring or Jakarta imports. The HTTP mapping
 * (403 Forbidden) lives in the {@code GlobalExceptionHandler} in the
 * adapter layer (ADR-036 §7).
 *
 * <p>The message deliberately does not reveal the expiry timestamp: the caller
 * already knows the session identifier, and leaking the exact expiry time
 * would help an attacker time a replay attack.
 */
public class SessionExpiredException extends RuntimeException {

    private final UUID sessionId;

    /**
     * Creates the exception.
     *
     * @param sessionId the identifier of the expired session
     */
    public SessionExpiredException(final UUID sessionId) {
        super("Session " + sessionId + " has expired");
        this.sessionId = sessionId;
    }

    /**
     * The identifier of the expired session.
     *
     * @return the session identifier
     */
    public UUID sessionId() {
        return sessionId;
    }
}
