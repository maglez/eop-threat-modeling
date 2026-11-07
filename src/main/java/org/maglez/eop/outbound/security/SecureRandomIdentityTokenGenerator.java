package org.maglez.eop.outbound.security;

import java.security.SecureRandom;
import java.util.Base64;
import org.maglez.eop.application.port.out.IdentityTokenGenerator;
import org.springframework.stereotype.Component;

/**
 * Issues the opaque credential that distinguishes one player from another.
 *
 * <p>Thirty-two random bytes, so two hundred and fifty-six bits, encoded base64url
 * without padding. The width is not decoration. Because the value is drawn from
 * {@link SecureRandom} and never chosen by a person, a plain SHA-256 digest is the
 * correct way to store it: there is no dictionary to attack and nothing for a key
 * derivation function's work factor to defend against (ADR-015).
 *
 * <p>base64url rather than plain base64 so the value survives being placed in a URL
 * unescaped. It never is placed in one — the credential travels in a request header
 * and is never accepted as a query parameter — but a credential that cannot be
 * mangled by transport is one fewer thing to get right later.
 *
 * <p>The returned value is handed to its owner exactly once, in the response to the
 * request that created it. This application keeps only its digest, so a value lost
 * by the client cannot be recovered, only replaced by rejoining as a new player.
 */
@Component
public class SecureRandomIdentityTokenGenerator implements IdentityTokenGenerator {

    /**
     * Two hundred and fifty-six bits, matching the digest that stores it.
     *
     * <p>Chosen so the credential is not the weakest part of the design; at this
     * width guessing one is not a threat model, it is arithmetic.
     */
    private static final int ENTROPY_BYTES = 32;

    private final SecureRandom random;

    /**
     * Creates a generator over the platform's default secure random source.
     */
    public SecureRandomIdentityTokenGenerator() {
        this.random = new SecureRandom();
    }

    @Override
    public String nextToken() {
        final var entropy = new byte[ENTROPY_BYTES];
        random.nextBytes(entropy);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    }
}
