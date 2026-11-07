package org.maglez.eop.application.port.out;

/**
 * Port that produces the opaque credential identifying one player.
 *
 * <p>The returned value is the only thing that distinguishes one player from
 * another: there is no account, no password and no authentication scheme
 * (ADR-015). It is therefore returned to its owner exactly once, at the moment of
 * admission, and stored only as a digest.
 *
 * <p>The implementation must use {@link java.security.SecureRandom} with at least
 * 256 bits of output. That width is what makes storing a plain SHA-256 digest
 * correct rather than negligent: there is no password to grind, because the input
 * was never human-chosen.
 */
public interface IdentityTokenGenerator {

    /**
     * Produces a fresh identity token in plaintext.
     *
     * @return a URL-safe token string, never {@code null}
     */
    String nextToken();
}
