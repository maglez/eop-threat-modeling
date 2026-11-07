package org.maglez.eop.application.service;

import java.time.Clock;
import java.util.Objects;
import org.maglez.eop.domain.ConnectionStatus;
import org.maglez.eop.domain.DisplayName;
import org.maglez.eop.domain.GameSession;
import org.maglez.eop.domain.IdentityTokenHash;
import org.maglez.eop.domain.JoinCodeUnavailableException;
import org.maglez.eop.domain.Player;
import org.maglez.eop.domain.PlayerRole;
import org.maglez.eop.application.port.in.CreateSessionPort;
import org.maglez.eop.application.port.in.SessionAdmission;
import org.maglez.eop.application.port.out.IdentifierGenerator;
import org.maglez.eop.application.port.out.IdentityTokenGenerator;
import org.maglez.eop.application.port.out.JoinCodeGenerator;
import org.maglez.eop.application.port.out.SessionRepository;

/**
 * Opens a new session with its creator seated as facilitator.
 *
 * <p>A session and its facilitator are created together, never separately: a
 * session with no players would be reachable by its join code and impossible to
 * start, so that state is not representable here.
 *
 * <p>Join codes are drawn blind and offered to the database, which rejects a
 * duplicate through {@code uq_game_session_join_code}. Asking first whether a code
 * is free would be a check-then-act race that a unique constraint has to catch
 * anyway, so only the constraint is trusted and a rejection is simply retried
 * (ADR-019).
 */
public class CreateSessionService implements CreateSessionPort {

    /**
     * How many join codes are drawn before giving up.
     *
     * <p>Roughly 1.1 trillion codes exist and sessions are counted in dozens, so a
     * single collision is already improbable and five in a row indicates something
     * other than bad luck — a broken generator, or a table that has grown far
     * beyond what this design anticipated. Failing loudly is the correct response
     * to both; looping forever would hide them.
     */
    private static final int MAXIMUM_CODE_ATTEMPTS = 5;

    private final SessionRepository sessionRepository;
    private final IdentifierGenerator identifierGenerator;
    private final JoinCodeGenerator joinCodeGenerator;
    private final IdentityTokenGenerator identityTokenGenerator;
    private final Clock clock;

    /**
     * Creates the use case.
     *
     * @param sessionRepository the port used to insert the session
     * @param identifierGenerator the port supplying session and player identifiers
     * @param joinCodeGenerator the port supplying join codes
     * @param identityTokenGenerator the port supplying identity tokens
     * @param clock the clock used to stamp the session, injected so tests are
     *     deterministic
     */
    public CreateSessionService(
            final SessionRepository sessionRepository,
            final IdentifierGenerator identifierGenerator,
            final JoinCodeGenerator joinCodeGenerator,
            final IdentityTokenGenerator identityTokenGenerator,
            final Clock clock) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository is required");
        this.identifierGenerator = Objects.requireNonNull(identifierGenerator, "identifierGenerator is required");
        this.joinCodeGenerator = Objects.requireNonNull(joinCodeGenerator, "joinCodeGenerator is required");
        this.identityTokenGenerator =
                Objects.requireNonNull(identityTokenGenerator, "identityTokenGenerator is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * Opens a lobby for a facilitator.
     *
     * <p>The identity token and the player identifier are generated once, before
     * the retry loop, so that a redrawn join code does not also change who the
     * facilitator is.
     *
     * @param displayName the name the facilitator chose, already validated
     * @return the created session together with the facilitator's credential
     * @throws NullPointerException if displayName is null
     * @throws JoinCodeUnavailableException if a free join code could not be found
     *     within the attempt budget
     */
    public SessionAdmission execute(final DisplayName displayName) {
        Objects.requireNonNull(displayName, "displayName is required");

        final var now = clock.instant();
        final var playerId = identifierGenerator.nextIdentifier();
        final var plaintextToken =
                identityTokenGenerator.nextToken();
        final var facilitator = new Player(
                playerId,
                displayName,
                0,
                PlayerRole.FACILITATOR,
                ConnectionStatus.CONNECTED,
                IdentityTokenHash.of(plaintextToken),
                now);

        JoinCodeUnavailableException lastRejection = null;
        for (int attempt = 0; attempt < MAXIMUM_CODE_ATTEMPTS; attempt++) {
            final var session = GameSession.openLobby(
                    identifierGenerator.nextIdentifier(), joinCodeGenerator.nextJoinCode(), facilitator, now);
            try {
                sessionRepository.createLobby(session);
                return new SessionAdmission(session, playerId, plaintextToken);
            }
            catch (final JoinCodeUnavailableException rejected) {
                lastRejection = rejected;
            }
        }
        throw Objects.requireNonNullElseGet(lastRejection, JoinCodeUnavailableException::new);
    }
}
