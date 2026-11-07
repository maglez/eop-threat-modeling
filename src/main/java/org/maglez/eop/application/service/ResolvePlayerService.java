package org.maglez.eop.application.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.maglez.eop.domain.IdentityTokenHash;
import org.maglez.eop.domain.PlayerNotRecognisedException;
import org.maglez.eop.domain.SessionExpiredException;
import org.maglez.eop.domain.SessionNotFoundException;
import org.maglez.eop.application.port.in.ResolvePlayerPort;
import org.maglez.eop.application.port.in.ResolvedPlayer;
import org.maglez.eop.application.port.out.SessionRepository;

/**
 * Establishes which seated player a request was made on behalf of.
 *
 * <p>There is no authentication scheme here and no framework filter doing this
 * work. The identity token is the entire control (ADR-015), so this class is the
 * single place where possession of a token becomes a named player, and every
 * operation that acts on behalf of a player goes through it.
 *
 * <p>The session is read from the database on every call, never from a cache or a
 * subscriber registry. That is what makes the first request after a deployment
 * behave exactly like any other request (ADR-014).
 */
public class ResolvePlayerService implements ResolvePlayerPort {

    private final SessionRepository sessionRepository;
    private final Clock clock;

    /**
     * Creates the use case.
     *
     * @param sessionRepository the port used to read sessions
     * @param clock the clock used to evaluate session expiry — injected so that
     *     tests can fix the instant and avoid wall-clock races
     */
    public ResolvePlayerService(final SessionRepository sessionRepository, final Clock clock) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * Resolves a token to the player it identifies within a session.
     *
     * <p>A missing token and an unrecognised token produce the same exception. The
     * caller presented no proof of identity in both cases, and distinguishing them
     * would report back whether a token was the right shape.
     *
     * @param sessionId the session the caller is acting on
     * @param playerToken the plaintext identity token presented with the request,
     *     which may be null or blank when the caller sent no header at all
     * @return the session paired with the player the token identifies
     * @throws NullPointerException if sessionId is null
     * @throws SessionNotFoundException if no session has that identifier
     * @throws PlayerNotRecognisedException if no token was presented, or the token
     *     presented does not belong to a player in that session
     */
    public ResolvedPlayer execute(final UUID sessionId, final String playerToken) {
        Objects.requireNonNull(sessionId, "sessionId is required");

        final var session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));

        if (session.expiresAt().isBefore(Instant.now(clock))) {
            throw new SessionExpiredException(sessionId);
        }

        if (playerToken == null || playerToken.isBlank()) {
            throw new PlayerNotRecognisedException(sessionId);
        }

        final var player = session.playerByTokenHash(IdentityTokenHash.of(playerToken))
                .orElseThrow(() -> new PlayerNotRecognisedException(sessionId));

        return new ResolvedPlayer(session, player);
    }
}
