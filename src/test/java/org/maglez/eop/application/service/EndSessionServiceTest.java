package org.maglez.eop.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.maglez.eop.domain.GameSessionBuilder.aSession;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.domain.GameResult;
import org.maglez.eop.domain.NotFacilitatorException;
import org.maglez.eop.domain.PlayerBuilder;
import org.maglez.eop.domain.PlayerNotRecognisedException;
import org.maglez.eop.domain.SessionNotInProgressException;
import org.maglez.eop.domain.SessionStatus;
import org.maglez.eop.application.port.in.EndSessionPort;
import org.maglez.eop.application.port.in.SessionEventType;
import org.maglez.eop.application.port.out.GameResultRepository;
import org.maglez.eop.application.port.out.InMemorySessionRepository;
import org.maglez.eop.application.port.out.InMemoryTrickRepository;
import org.maglez.eop.application.port.out.QueuedIdentifierGenerator;
import org.maglez.eop.application.port.out.RecordingSessionEventPublisher;
import org.maglez.eop.domain.GameSessionBuilder;

/**
 * Behaviour of the use case that ends a session early at the facilitator's request.
 *
 * <p>The ordering assertion is the one that earns its keep. The completion is written to
 * the database before it is announced, so a subscriber that reacts to the announcement
 * by re-reading the session cannot observe a table that is still in progress. Announcing
 * first would make that race real and intermittent.
 */
@DisplayName("EndSessionPort")
class EndSessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-02-01T09:45:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String FACILITATOR_TOKEN = PlayerBuilder.DEFAULT_TOKEN;

    private static final UUID RESULT_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private final List<String> order = new ArrayList<>();
    private final RecordingSessionEventPublisher publisher = new RecordingSessionEventPublisher(order);
    private final InMemoryTrickRepository trickRepository = new InMemoryTrickRepository(order);
    private final InMemoryGameResultRepository resultRepository = new InMemoryGameResultRepository(order);
    private final QueuedIdentifierGenerator identifierGenerator = new QueuedIdentifierGenerator(RESULT_ID);

    private EndSessionPort useCaseFor(final InMemorySessionRepository repository) {
        return new EndSessionService(repository,
                new ResolvePlayerService(repository, java.time.Clock.systemUTC()), publisher,
                Optional.empty(), FIXED);
    }

    private EndSessionPort useCasePersisting(final InMemorySessionRepository repository) {
        return new EndSessionService(repository,
                new ResolvePlayerService(repository, java.time.Clock.systemUTC()), publisher,
                Optional.of(new PersistGameResultService(
                        repository, trickRepository, resultRepository, identifierGenerator, FIXED)),
                FIXED);
    }

    @Test
    @DisplayName("completes a session in progress")
    void shouldCompleteASessionInProgress() {
        final var session = aSession().withPlayerCount(3).withStatus(SessionStatus.IN_PROGRESS).build();
        final var repository = new InMemorySessionRepository(order, session);

        final var completed = useCaseFor(repository).execute(session.sessionId(), FACILITATOR_TOKEN);

        assertThat(completed.status()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(completed.updatedAt()).isEqualTo(NOW);
        assertThat(completed.createdAt()).isEqualTo(session.createdAt());
        assertThat(completed.players()).hasSize(3);
    }

    @Test
    @DisplayName("records the completion in the database before announcing it")
    void shouldRecordBeforeAnnouncing() {
        final var session = aSession().withPlayerCount(3).withStatus(SessionStatus.IN_PROGRESS).build();
        final var repository = new InMemorySessionRepository(order, session);

        useCaseFor(repository).execute(session.sessionId(), FACILITATOR_TOKEN);

        assertThat(order).containsExactly("recordCompleted", "publish");
    }

    @Test
    @DisplayName("announces game-completed once, naming the session and the moment")
    void shouldAnnounceTheCompletion() {
        final var session = aSession().withPlayerCount(3).withStatus(SessionStatus.IN_PROGRESS).build();
        final var repository = new InMemorySessionRepository(order, session);

        useCaseFor(repository).execute(session.sessionId(), FACILITATOR_TOKEN);

        assertThat(publisher.published()).hasSize(1);
        final var event = publisher.published().get(0);
        assertThat(event.type()).isEqualTo(SessionEventType.GAME_COMPLETED);
        assertThat(event.sessionId()).isEqualTo(session.sessionId());
        assertThat(event.occurredAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("leaves the stored session completed, not only the returned copy")
    void shouldPersistTheNewStatus() {
        final var session = aSession().withPlayerCount(3).withStatus(SessionStatus.IN_PROGRESS).build();
        final var repository = new InMemorySessionRepository(order, session);

        useCaseFor(repository).execute(session.sessionId(), FACILITATOR_TOKEN);

        assertThat(repository.findById(session.sessionId()).orElseThrow().status())
                .isEqualTo(SessionStatus.COMPLETED);
    }

    @Test
    @DisplayName("records the game result once the session is completed")
    void shouldRecordTheGameResult() {
        final var session = aSession().withPlayerCount(3).withStatus(SessionStatus.IN_PROGRESS).build();
        final var repository = new InMemorySessionRepository(order, session);

        useCasePersisting(repository).execute(session.sessionId(), FACILITATOR_TOKEN);

        assertThat(resultRepository.saved()).isNotNull();
        assertThat(resultRepository.saved().sessionId()).isEqualTo(session.sessionId());
    }

    @Test
    @DisplayName("records the result before announcing the completion")
    void shouldRecordTheResultBeforeAnnouncing() {
        final var session = aSession().withPlayerCount(3).withStatus(SessionStatus.IN_PROGRESS).build();
        final var repository = new InMemorySessionRepository(order, session);

        useCasePersisting(repository).execute(session.sessionId(), FACILITATOR_TOKEN);

        assertThat(order).contains("save");
        assertThat(order.indexOf("save"))
                .as("the result must be on the disk before any subscriber is told the game is over")
                .isLessThan(order.indexOf("publish"));
    }

    @Test
    @DisplayName("still completes the session when the result cannot be recorded")
    void shouldStillCompleteWhenTheResultCannotBeRecorded() {
        final var session = aSession().withPlayerCount(3).withStatus(SessionStatus.IN_PROGRESS).build();
        final var repository = new InMemorySessionRepository(order, session);
        resultRepository.failOnSave();

        final var completed = useCasePersisting(repository).execute(session.sessionId(), FACILITATOR_TOKEN);

        assertThat(completed.status()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(publisher.published()).hasSize(1);
    }

    @Test
    @DisplayName("records no result when the caller is refused")
    void shouldRecordNoResultWhenRefused() {
        final var session = aSession().withPlayerCount(3).withStatus(SessionStatus.IN_PROGRESS).build();
        final var repository = new InMemorySessionRepository(order, session);

        assertThatExceptionOfType(NotFacilitatorException.class)
                .isThrownBy(() -> useCasePersisting(repository)
                        .execute(session.sessionId(), FACILITATOR_TOKEN + "-1"));

        assertThat(resultRepository.saved()).isNull();
    }

    @Test
    @DisplayName("refuses a participant, and writes nothing while refusing")
    void shouldRefuseAParticipant() {
        final var session = aSession().withPlayerCount(3).withStatus(SessionStatus.IN_PROGRESS).build();
        final var repository = new InMemorySessionRepository(order, session);

        assertThatExceptionOfType(NotFacilitatorException.class)
                .isThrownBy(() -> useCaseFor(repository)
                        .execute(session.sessionId(), FACILITATOR_TOKEN + "-1"))
                .withMessageContaining("is not the facilitator");

        assertThat(repository.recordCompletedCalls()).isZero();
        assertThat(publisher.published()).isEmpty();
    }

    @Test
    @DisplayName("refuses a session that is still in the lobby")
    void shouldRefuseALobbySession() {
        final var session = aSession().withPlayerCount(3).build();
        final var repository = new InMemorySessionRepository(order, session);

        assertThatExceptionOfType(SessionNotInProgressException.class)
                .isThrownBy(() -> useCaseFor(repository).execute(session.sessionId(), FACILITATOR_TOKEN))
                .withMessageContaining("LOBBY");

        assertThat(repository.recordCompletedCalls()).isZero();
        assertThat(publisher.published()).isEmpty();
    }

    @Test
    @DisplayName("refuses a session that is already completed")
    void shouldRefuseAnAlreadyCompletedSession() {
        final var session = aSession().withPlayerCount(3).withStatus(SessionStatus.COMPLETED).build();
        final var repository = new InMemorySessionRepository(order, session);

        assertThatExceptionOfType(SessionNotInProgressException.class)
                .isThrownBy(() -> useCaseFor(repository).execute(session.sessionId(), FACILITATOR_TOKEN))
                .withMessageContaining("COMPLETED");

        assertThat(repository.recordCompletedCalls()).isZero();
        assertThat(publisher.published()).isEmpty();
    }

    @Test
    @DisplayName("refuses an unrecognised credential before deciding anything else")
    void shouldRefuseAnUnknownCredential() {
        final var session = aSession().withPlayerCount(3).withStatus(SessionStatus.IN_PROGRESS).build();
        final var repository = new InMemorySessionRepository(order, session);

        assertThatExceptionOfType(PlayerNotRecognisedException.class)
                .isThrownBy(() -> useCaseFor(repository).execute(session.sessionId(), "not-a-credential"));

        assertThat(repository.recordCompletedCalls()).isZero();
        assertThat(publisher.published()).isEmpty();
    }

    private static final class InMemoryGameResultRepository implements GameResultRepository {

        private final List<String> order;
        private GameResult saved;
        private boolean failOnSave;

        private InMemoryGameResultRepository(final List<String> order) {
            this.order = order;
        }

        private GameResult saved() {
            return saved;
        }

        private void failOnSave() {
            this.failOnSave = true;
        }

        @Override
        public void save(final GameResult result) {
            if (failOnSave) {
                throw new IllegalStateException("result store unavailable");
            }
            this.saved = result;
            order.add("save");
        }

        @Override
        public Optional<GameResult> findBySessionId(final UUID sessionId) {
            return saved != null && saved.sessionId().equals(sessionId)
                    ? Optional.of(saved)
                    : Optional.empty();
        }
    }
}
