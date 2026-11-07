package org.maglez.eop.application.port.out;

import org.maglez.eop.domain.JoinCode;

/**
 * Port that produces the short code a facilitator shares so others can join.
 *
 * <p>The implementation must draw from a cryptographically secure source. A join
 * code is eight characters, which is exactly forty bits of entropy — enough that a
 * blind search is impractical, and small enough that a predictable generator would
 * be guessable outright rather than merely brute-forceable. See ADR-019, which
 * records the distributed attack model the length is chosen against and why the rate
 * limiter guarding this value is not a courtesy.
 *
 * <p>Collisions are not this port's problem. The generator draws blind, the
 * database rejects a duplicate through {@code uq_game_session_join_code}, and the
 * caller draws again. Asking the generator to check first would introduce a
 * check-then-act race that the unique constraint already settles.
 */
public interface JoinCodeGenerator {

    /**
     * Produces a candidate join code.
     *
     * @return a syntactically valid join code, which may already be in use
     */
    JoinCode nextJoinCode();
}
