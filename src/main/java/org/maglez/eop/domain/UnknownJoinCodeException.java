package org.maglez.eop.domain;

/**
 * Raised when a join code does not lead to a session that accepts players.
 *
 * <p>Carries nothing. Not the code, not the reason, not a distinction between
 * "never existed", "mistyped", "expired" and "abandoned" — because the response
 * produced from it must be identical in every one of those cases.
 *
 * <p>That is not tidiness. An eight-character code has forty bits of entropy,
 * which bounds a blind search but is worth nothing if the endpoint distinguishes
 * its answers: one that replied "that code is not real" differently from "that
 * code is real but closed" would be an oracle confirming which codes exist.
 * The absence of fields on this class is what stops a later maintainer from
 * helpfully putting the reason in the response body.
 */
public class UnknownJoinCodeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception. There is deliberately nothing to pass in. */
    public UnknownJoinCodeException() {
        super("No joinable session for the supplied code");
    }
}
