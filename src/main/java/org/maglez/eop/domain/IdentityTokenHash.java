package org.maglez.eop.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * The SHA-256 digest of a player's identity token, hex encoded.
 *
 * <p>The token itself is 256 bits of {@code SecureRandom} output, handed to the
 * client once and never stored (ADR-015). Whoever holds it is that player, so
 * keeping it in the clear would be the same class of mistake as storing a
 * plaintext password.
 *
 * <p>A plain digest rather than a password KDF is the right choice here, and for
 * a specific reason: a KDF exists to make guessing a low-entropy human secret
 * expensive. The input here is not low-entropy, so there is nothing to slow down
 * — an attacker who could brute-force 256 random bits would not be inconvenienced
 * by an iteration count.
 *
 * <p>Comparison happens in Java, in memory — not as a database lookup. A
 * presented token is digested and matched against the digests already loaded on
 * the session by {@link Player#isIdentifiedBy(IdentityTokenHash)}, across at
 * most {@value GameSession#MAXIMUM_PLAYERS} players. The
 * {@code uq_player_identity_token_hash} index enforces uniqueness and is never
 * used as a lookup path. {@link #equals(Object)} therefore compares with
 * {@link MessageDigest#isEqual}, not the {@link String#equals} a record would
 * otherwise generate, which returns on the first differing byte.
 *
 * <p>That closes no known exploit. An attacker supplies a token, not a digest,
 * so steering the digest bytes to walk a comparison one position at a time would
 * take SHA-256 preimage work. The reason to be constant-time regardless is that
 * this type is the seam any future credential would reuse: a rejoin PIN or a
 * short reconnect code would carry far less entropy and would inherit whatever
 * this comparison does.
 *
 * <p>{@link #toString()} is redacted: a digest is not a credential, but log
 * output has a way of ending up in places nobody audited.
 *
 * <p>Uses {@link MessageDigest} from the JDK, not a framework hashing utility,
 * because this class lives in the domain and the domain imports nothing.
 *
 * @param value the digest as sixty-four lower-case hex characters
 */
public record IdentityTokenHash(String value) {

    /** Hex characters in a SHA-256 digest, and the column width. */
    public static final int HEX_LENGTH = 64;

    private static final String ALGORITHM = "SHA-256";

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    /**
     * Rejects anything that is not a SHA-256 digest in hex.
     *
     * @throws NullPointerException     if the value is null
     * @throws IllegalArgumentException if the value is not sixty-four
     *                                  lower-case hex characters
     */
    public IdentityTokenHash {
        Objects.requireNonNull(value, "value is required");
        if (value.length() != HEX_LENGTH) {
            throw new IllegalArgumentException("A token hash is exactly " + HEX_LENGTH + " hex characters, was " + value.length());
        }
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            final boolean isHex = (character >= '0' && character <= '9') || (character >= 'a' && character <= 'f');
            if (!isHex) {
                throw new IllegalArgumentException("A token hash contains only lower-case hex characters");
            }
        }
    }

    /**
     * Digests a plaintext identity token.
     *
     * @param plaintextToken the token as issued to the client
     * @return the digest to store and to compare against
     */
    public static IdentityTokenHash of(final String plaintextToken) {
        Objects.requireNonNull(plaintextToken, "plaintextToken is required");
        final byte[] digest = digest(plaintextToken.getBytes(StandardCharsets.UTF_8));
        final char[] hex = new char[digest.length * 2];
        for (int index = 0; index < digest.length; index++) {
            final int unsigned = digest[index] & 0xFF;
            hex[index * 2] = HEX_DIGITS[unsigned >>> 4];
            hex[index * 2 + 1] = HEX_DIGITS[unsigned & 0x0F];
        }
        return new IdentityTokenHash(new String(hex));
    }

    private static byte[] digest(final byte[] input) {
        try {
            return MessageDigest.getInstance(ALGORITHM).digest(input);
        }
        catch (final NoSuchAlgorithmException impossible) {
            // Every conforming Java runtime is required to provide SHA-256.
            throw new IllegalStateException(ALGORITHM + " is unavailable on this runtime", impossible);
        }
    }

    /**
     * A redacted description, safe to log.
     *
     * @return a constant marker carrying no part of the digest
     */
    @Override
    public String toString() {
        return "IdentityTokenHash[redacted]";
    }

    /**
     * Compares two digests without returning early on the first differing byte.
     *
     * <p>The result is identical to the comparison a record would generate. The
     * difference is that it takes the same time whether the digests diverge in
     * the first position or the last, so a caller cannot learn a prefix by
     * timing repeated attempts. Both operands are exactly
     * {@value #HEX_LENGTH} characters by construction, so no length is leaked
     * either.
     *
     * @param other the object to compare against
     * @return true when the other object is an {@code IdentityTokenHash} holding the same digest
     */
    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof IdentityTokenHash candidate)) {
            return false;
        }
        return MessageDigest.isEqual(asciiBytes(), candidate.asciiBytes());
    }

    /**
     * Consistent with {@link #equals(Object)}: digests that compare equal are the same text.
     *
     * <p>This is a plain string hash and is not constant time, which is why no
     * hash-based collection in this application is keyed on this type or on
     * {@link Player}. Do not introduce one. A {@code HashMap} or {@code HashSet}
     * compares the cached {@code int} before it calls {@link #equals(Object)}, so
     * keying a lookup on a digest would make its cost depend on the digest again
     * and quietly bypass the constant-time comparison above.
     *
     * @return the hash of the digest text
     */
    @Override
    public int hashCode() {
        return value.hashCode();
    }

    /** The digest text as bytes. Hex is ASCII, so the encoding cannot alter the length. */
    private byte[] asciiBytes() {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
