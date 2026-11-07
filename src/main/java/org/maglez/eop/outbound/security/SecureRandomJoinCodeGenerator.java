package org.maglez.eop.outbound.security;

import java.security.SecureRandom;
import org.maglez.eop.domain.JoinCode;
import org.maglez.eop.application.port.out.JoinCodeGenerator;
import org.springframework.stereotype.Component;

/**
 * Draws join codes from a cryptographically secure source.
 *
 * <p>Eight characters from a thirty-two symbol alphabet is a keyspace of about
 * 1.1 trillion, or exactly forty bits. Read that number against a distributed
 * attacker rather than a single one: the join limiter allows ten failures per
 * minute per client address, so the useful figure is not how long one host needs
 * but how long a pool of hosts needs. The code was six characters until EOP-24,
 * and at thirty bits roughly a thousand proxied addresses expected to find one of
 * a few dozen live lobbies within days. Forty bits multiplies that by 1024
 * (ADR-019).
 *
 * <p>The per-code half of the limiter does not help against that search, which is
 * worth stating because its name suggests otherwise: enumeration never guesses the
 * same code twice, so every attempt lands in its own fresh bucket and the per-code
 * budget is never spent. Length is what bounds enumeration; the limiter bounds the
 * rate at which a single address may work.
 *
 * <p>{@link SecureRandom} rather than {@link java.util.Random} for the obvious
 * reason and one less obvious one: an attacker who could predict the sequence would
 * not need to guess at all, and the rate limiter would never see a failed attempt
 * to count.
 *
 * <p>Codes are drawn blind. Uniqueness is enforced by
 * {@code uq_game_session_join_code} and a collision comes back as a rejected
 * insert, because checking whether a code is free before using it is a
 * check-then-act race that two simultaneous facilitators would lose together.
 */
@Component
public class SecureRandomJoinCodeGenerator implements JoinCodeGenerator {

    private final SecureRandom random;

    /**
     * Creates a generator over the platform's default secure random source.
     *
     * <p>The no-argument {@link SecureRandom} constructor is the correct choice
     * rather than a named algorithm: it asks the platform for its strongest
     * available source, and it does not block on Linux the way reading
     * {@code /dev/random} would.
     */
    public SecureRandomJoinCodeGenerator() {
        this.random = new SecureRandom();
    }

    @Override
    public JoinCode nextJoinCode() {
        final var alphabet = JoinCode.ALPHABET;
        final var drawn = new char[JoinCode.LENGTH];
        for (int position = 0; position < drawn.length; position++) {
            drawn[position] = alphabet.charAt(random.nextInt(alphabet.length()));
        }
        return new JoinCode(new String(drawn));
    }
}
