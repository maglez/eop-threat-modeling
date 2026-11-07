package org.maglez.eop.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.maglez.eop.domain.GameSessionBuilder.aSession;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.domain.PlayerBuilder;
import org.maglez.eop.domain.PlayerNotRecognisedException;
import org.maglez.eop.domain.PlayerRole;
import org.maglez.eop.domain.SessionExpiredException;
import org.maglez.eop.domain.SessionNotFoundException;
import org.maglez.eop.application.port.in.ResolvePlayerPort;
import org.maglez.eop.application.port.out.InMemorySessionRepository;
import org.maglez.eop.domain.GameSessionBuilder;

/**
 * Behaviour of the use case that turns a credential back into a player.
 *
 * <p>Every endpoint that acts on behalf of a player passes through here, so the
 * important assertions are about what the refusals give away. A credential that is
 * absent, blank, or simply wrong all produce the same exception with the same
 * message: a caller holding a session identifier must not be able to tell whether a
 * guessed credential belongs to nobody or to somebody else.
 */
@DisplayName("ResolvePlayerPort")
class ResolvePlayerServiceTest {

    private static final String FACILITATOR_TOKEN = PlayerBuilder.DEFAULT_TOKEN;
    /** A fixed "now" used by tests that need deterministic clock behaviour. */
    private static final Instant FIXED_NOW = Instant.parse("2026-08-16T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private static final UUID ABSENT_SESSION = UUID.fromString("00000000-0000-7000-8000-0000000000e1");

    @Test
    @DisplayName("returns the session together with the player holding the credential")
    void shouldResolveTheHolderOfTheCredential() {
        final var session = aSession().withPlayerCount(3).build();
        final var useCase = new ResolvePlayerService(new InMemorySessionRepository(session), FIXED_CLOCK);

        final var resolved = useCase.execute(session.sessionId(), FACILITATOR_TOKEN);

        assertThat(resolved.session().sessionId()).isEqualTo(session.sessionId());
        assertThat(resolved.player().seatOrder()).isZero();
        assertThat(resolved.player().role()).isEqualTo(PlayerRole.FACILITATOR);
    }

    @Test
    @DisplayName("resolves a participant rather than always answering with the facilitator")
    void shouldResolveAParticipant() {
        final var session = aSession().withPlayerCount(3).build();
        final var useCase = new ResolvePlayerService(new InMemorySessionRepository(session), FIXED_CLOCK);

        final var resolved = useCase.execute(session.sessionId(), FACILITATOR_TOKEN + "-2");

        assertThat(resolved.player().seatOrder()).isEqualTo(2);
        assertThat(resolved.player().role()).isEqualTo(PlayerRole.PARTICIPANT);
    }

    @Test
    @DisplayName("reports a session that does not exist as missing, not as a bad credential")
    void shouldReportAnAbsentSessionAsMissing() {
        final var useCase = new ResolvePlayerService(new InMemorySessionRepository(), FIXED_CLOCK);

        assertThatExceptionOfType(SessionNotFoundException.class)
                .isThrownBy(() -> useCase.execute(ABSENT_SESSION, FACILITATOR_TOKEN))
                .withMessageContaining(ABSENT_SESSION.toString());
    }

    @Test
    @DisplayName("rejects a missing session identifier outright")
    void shouldRejectANullSessionIdentifier() {
        final var useCase = new ResolvePlayerService(new InMemorySessionRepository(), FIXED_CLOCK);

        assertThatNullPointerException()
                .isThrownBy(() -> useCase.execute(null, FACILITATOR_TOKEN))
                .withMessageContaining("sessionId");
    }

    @Test
    @DisplayName("answers an absent, a blank and a wrong credential identically")
    void shouldNotDistinguishBetweenWaysOfFailing() {
        final var session = aSession().withPlayerCount(3).build();
        final var useCase = new ResolvePlayerService(new InMemorySessionRepository(session), FIXED_CLOCK);

        final var absent = refusalFor(useCase, session.sessionId(), null);
        final var blank = refusalFor(useCase, session.sessionId(), "   ");
        final var wrong = refusalFor(useCase, session.sessionId(), "not-anybodys-credential");

        assertThat(blank).hasMessage(absent.getMessage());
        assertThat(wrong).hasMessage(absent.getMessage());
        assertThat(absent).hasMessageContaining(session.sessionId().toString());
    }

    @Test
    @DisplayName("never echoes the rejected credential back in the message")
    void shouldNotEchoTheRejectedCredential() {
        final var session = aSession().withPlayerCount(3).build();
        final var useCase = new ResolvePlayerService(new InMemorySessionRepository(session), FIXED_CLOCK);

        final var refusal = refusalFor(useCase, session.sessionId(), "guessed-credential");

        assertThat(refusal.getMessage()).doesNotContain("guessed-credential");
    }

    private PlayerNotRecognisedException refusalFor(
            final ResolvePlayerPort useCase, final UUID sessionId, final String token) {
        try {
            useCase.execute(sessionId, token);
        } catch (final PlayerNotRecognisedException refused) {
            return refused;
        }
        throw new AssertionError("the credential should have been refused");
    }

    @Test
    @DisplayName("expiry check fires before the token check — blank token on an expired session yields SessionExpiredException")
    void shouldCheckExpiryBeforeToken() {
        // Arrange: session is expired; token is blank (would normally yield PlayerNotRecognisedException)
        final var expired = aSession()
                .withPlayerCount(1)
                .withExpiresAt(FIXED_NOW.minusSeconds(1))
                .build();
        final var useCase = new ResolvePlayerService(new InMemorySessionRepository(expired), FIXED_CLOCK);

        // Act + Assert: expiry fires first — blank token does NOT produce PlayerNotRecognisedException
        assertThatExceptionOfType(SessionExpiredException.class)
                .isThrownBy(() -> useCase.execute(expired.sessionId(), ""))
                .withMessageContaining(expired.sessionId().toString());
    }

    @Test
    @DisplayName("rejects a credential for a session whose expiry timestamp is in the past")
    void shouldRejectAnExpiredSession() {
        // expiresAt is 1 second before FIXED_NOW, so the session is expired
        final var expired = aSession()
                .withPlayerCount(1)
                .withExpiresAt(FIXED_NOW.minusSeconds(1))
                .build();
        final var useCase = new ResolvePlayerService(new InMemorySessionRepository(expired), FIXED_CLOCK);

        assertThatExceptionOfType(SessionExpiredException.class)
                .isThrownBy(() -> useCase.execute(expired.sessionId(), FACILITATOR_TOKEN))
                .withMessageContaining(expired.sessionId().toString());
    }

    @Test
    @DisplayName("resolves a session whose expiry timestamp is in the future")
    void shouldResolveASessionThatHasNotYetExpired() {
        // expiresAt is 1 hour after FIXED_NOW, so the session is still active
        final var active = aSession()
                .withPlayerCount(1)
                .withExpiresAt(FIXED_NOW.plusSeconds(3600))
                .build();
        final var useCase = new ResolvePlayerService(new InMemorySessionRepository(active), FIXED_CLOCK);

        final var resolved = useCase.execute(active.sessionId(), FACILITATOR_TOKEN);

        assertThat(resolved.session().sessionId()).isEqualTo(active.sessionId());
    }
}
