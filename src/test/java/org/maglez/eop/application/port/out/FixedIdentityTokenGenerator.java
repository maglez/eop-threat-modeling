package org.maglez.eop.application.port.out;

/**
 * Identity token generator that always returns the same token.
 *
 * <p>Deliberately constant. A test needs the plaintext in order to hash it and
 * compare, and the property worth asserting is that the token is drawn exactly once
 * per admission even when the write is retried — which the issue count records
 * without the value having to vary.
 */
public final class FixedIdentityTokenGenerator implements IdentityTokenGenerator {

    private final String token;

    private int issued;

    public FixedIdentityTokenGenerator(final String token) {
        this.token = token;
    }

    @Override
    public String nextToken() {
        issued++;
        return token;
    }

    /**
     * @return how many tokens were handed out
     */
    public int issued() {
        return issued;
    }
}
