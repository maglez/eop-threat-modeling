package org.maglez.eop.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.maglez.eop.domain.GameSessionBuilder.aSession;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.domain.PlayerBuilder;
import org.maglez.eop.domain.PlayerNotRecognisedException;
import org.maglez.eop.domain.SessionStatus;
import org.maglez.eop.application.port.in.GetSessionStatePort;
import org.maglez.eop.application.port.out.InMemorySessionRepository;
import org.maglez.eop.domain.GameSessionBuilder;

/**
 * Behaviour of the read used to resynchronise a client.
 *
 * <p>The use case is deliberately thin, so the only thing worth asserting is that it
 * stays thin: it returns the session behind the credential and it refuses in exactly
 * the same way the credential check refuses. A future version tempted to filter or
 * summarise the state should have to change this test to do so.
 */
@DisplayName("GetSessionStatePort")
class GetSessionStateServiceTest {

    private static final String FACILITATOR_TOKEN = PlayerBuilder.DEFAULT_TOKEN;

    @Test
    @DisplayName("returns the whole session behind a valid credential")
    void shouldReturnTheSessionForAValidCredential() {
        final var session = aSession().withPlayerCount(3).build();
        final var repository = new InMemorySessionRepository(session);
        final var useCase = new GetSessionStateService(new ResolvePlayerService(repository, java.time.Clock.systemUTC()));

        final var state = useCase.execute(session.sessionId(), FACILITATOR_TOKEN);

        assertThat(state.sessionId()).isEqualTo(session.sessionId());
        assertThat(state.joinCode()).isEqualTo(session.joinCode());
        assertThat(state.status()).isEqualTo(SessionStatus.LOBBY);
        assertThat(state.players()).hasSize(3);
    }

    @Test
    @DisplayName("serves a participant the same state it serves the facilitator")
    void shouldServeParticipantsTheSameState() {
        final var session = aSession().withPlayerCount(3).build();
        final var repository = new InMemorySessionRepository(session);
        final var useCase = new GetSessionStateService(new ResolvePlayerService(repository, java.time.Clock.systemUTC()));

        final var forFacilitator = useCase.execute(session.sessionId(), FACILITATOR_TOKEN);
        final var forParticipant = useCase.execute(session.sessionId(), FACILITATOR_TOKEN + "-1");

        assertThat(forParticipant).isEqualTo(forFacilitator);
    }

    @Test
    @DisplayName("refuses a caller who holds no credential for the table")
    void shouldRefuseAnUnrecognisedCaller() {
        final var session = aSession().withPlayerCount(3).build();
        final var repository = new InMemorySessionRepository(session);
        final var useCase = new GetSessionStateService(new ResolvePlayerService(repository, java.time.Clock.systemUTC()));

        assertThatExceptionOfType(PlayerNotRecognisedException.class)
                .isThrownBy(() -> useCase.execute(session.sessionId(), "not-a-credential"));
    }
}
