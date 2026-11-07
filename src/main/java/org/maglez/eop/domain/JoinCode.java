package org.maglez.eop.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The short code a facilitator reads out so other players can join.
 *
 * <p>Eight characters of Crockford base32. The alphabet excludes {@code I},
 * {@code L} and {@code O} because they are misread as {@code 1} and {@code 0} on
 * a video call, and {@code U} so that a random string does not spell a word
 * somebody has to say out loud in a meeting (ADR-019).
 *
 * <p>Eight characters is exactly forty bits. That is not enough on its own to
 * make the join endpoint's rate limiter optional, but it is enough that the
 * limiter is no longer the only thing standing between a distributed attacker
 * and a real lobby. The code was six characters until EOP-24; see ADR-019 for
 * the attack model that moved it.
 *
 * <p>Stored and transmitted in one canonical form: upper case, with the
 * ambiguous characters already folded away. {@link #parse(String)} does that
 * folding, so a human who types {@code 7qk2fmv9} in lower case reaches
 * {@code 7QK2FMV9}, and one who writes a letter {@code O} where the code has a
 * zero — {@code 7QKOFMV9} for {@code 7QK0FMV9} — still reaches the right
 * session. {@code I} and {@code L} fold to {@code 1} the same way. Note that
 * {@code U} is deliberately not folded: it is excluded from the alphabet
 * outright, so a code containing one is refused rather than reinterpreted.
 *
 * @param value the canonical eight-character code
 */
public record JoinCode(String value) {

    /** The Crockford base32 alphabet, in order. Thirty-two symbols. */
    public static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    /** Characters in a code. Changing this changes the entropy; see ADR-019. */
    public static final int LENGTH = 8;

    /**
     * Rejects a code that is not already canonical.
     *
     * <p>Strict on purpose: this constructor is for generated codes and for
     * values read back out of the database, where anything non-canonical is a
     * defect rather than a typing mistake. Human input goes through
     * {@link #parse(String)}.
     *
     * @throws NullPointerException     if the value is null
     * @throws IllegalArgumentException if the value is not exactly
     *                                  {@link #LENGTH} characters from
     *                                  {@link #ALPHABET}
     */
    public JoinCode {
        Objects.requireNonNull(value, "value is required");
        if (value.length() != LENGTH) {
            throw new IllegalArgumentException("A join code is exactly " + LENGTH + " characters, was " + value.length());
        }
        for (int index = 0; index < value.length(); index++) {
            if (ALPHABET.indexOf(value.charAt(index)) < 0) {
                throw new IllegalArgumentException("A join code contains only Crockford base32 characters");
            }
        }
    }

    /**
     * Interprets something a human typed, or reports that it cannot be a code.
     *
     * <p>Returns an empty optional rather than throwing, because the caller's
     * response to "that is not a code" and to "no session has that code" must be
     * identical. Distinguishing them would turn the join endpoint into an oracle
     * that confirms which codes are real, which is exactly the help an attacker
     * enumerating the keyspace needs.
     *
     * @param raw whatever arrived on the request, possibly null
     * @return the canonical code, or empty if the input cannot be one
     */
    public static Optional<JoinCode> parse(final String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        final String folded = raw.strip()
                .toUpperCase(Locale.ROOT)
                .replace('I', '1')
                .replace('L', '1')
                .replace('O', '0');
        if (folded.length() != LENGTH) {
            return Optional.empty();
        }
        for (int index = 0; index < folded.length(); index++) {
            if (ALPHABET.indexOf(folded.charAt(index)) < 0) {
                return Optional.empty();
            }
        }
        return Optional.of(new JoinCode(folded));
    }
}
