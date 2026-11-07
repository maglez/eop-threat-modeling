package org.maglez.eop.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.maglez.eop.domain.GameSessionBuilder.aSession;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.maglez.eop.domain.Card;
import org.maglez.eop.domain.DeckFixture;
import org.maglez.eop.domain.GameNotCompletedException;
import org.maglez.eop.domain.GameSession;
import org.maglez.eop.domain.NotFacilitatorException;
import org.maglez.eop.domain.PlayerBuilder;
import org.maglez.eop.domain.PlayerNotRecognisedException;
import org.maglez.eop.domain.SessionStatus;
import org.maglez.eop.application.port.in.NewGamePort;
import org.maglez.eop.application.port.in.SessionEventType;
import org.maglez.eop.application.port.out.InMemoryCardRepository;
import org.maglez.eop.application.port.out.InMemoryHandRepository;
import org.maglez.eop.application.port.out.InMemorySessionRepository;
import org.maglez.eop.application.port.out.InMemoryTrickRepository;
import org.maglez.eop.application.port.out.QueuedIdentifierGenerator;
import org.maglez.eop.application.port.out.RecordingSessionEventPublisher;
import org.maglez.eop.domain.GameSessionBuilder;
import org.maglez.eop.domain.SessionNotFoundException;

/**
 * Tests {@link NewGamePort}.
 *
 * <p>The interesting claims here are about authorisation and sequencing. Authorisation is
 * two-layered: the caller must be the facilitator, and the session must be completed. Sequencing
 * matters because the clear operations must run before the reset, and the deal must follow the
 * reset — a deal against a COMPLETED session would be refused by the real adapter.
 *
 * <p>The shared call log threads through all four collaborators so a single assertion can pin
 * the order across port boundaries. Refusal tests assert the call count as well as the exception,
 * because a use case that throws after writing has still written.
 */
@DisplayName("NewGamePort")
class NewGameServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");

    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final UUID FIRST_HAND = UUID.fromString("00000000-0000-7000-8000-0000000000e0");

    private static final UUID SECOND_HAND = UUID.fromString("00000000-0000-7000-8000-0000000000e1");

    private static final UUID THIRD_HAND = UUID.fromString("00000000-0000-7000-8000-0000000000e2");

    private static final int SEATS = 3;

    private final List<String> order = new ArrayList<>();

    private final InMemoryHandRepository handRepository = new InMemoryHandRepository(order);

    private final InMemoryTrickRepository trickRepository = new InMemoryTrickRepository(order);

    private final RecordingDeckShuffler shuffler = new RecordingDeckShuffler(order);

    private final InMemoryCardRepository cardRepository =
            new InMemoryCardRepository(DeckFixture.fullDeck().toArray(new Card[0]));

    private final QueuedIdentifierGenerator identifiers =
            new QueuedIdentifierGenerator(FIRST_HAND, SECOND_HAND, THIRD_HAND);

    private final RecordingSessionEventPublisher publisher = new RecordingSessionEventPublisher(order);

    @Nested
    @DisplayName("Happy path")
    class HappyPath {

        @Test
        @DisplayName("clears tricks, then hands, then resets session, then deals")
        void shouldExecuteInOrder() {
            final var session = completedTable(SEATS);
            final var sessionRepository = new InMemorySessionRepository(order, session);

            useCaseFor(sessionRepository).execute(session.sessionId(), PlayerBuilder.DEFAULT_TOKEN);

            assertThat(order).containsSubsequence(
                    "clearTricksForNewGame",
                    "clearHandsForNewGame",
                    "resetToInProgress",
                    "shuffle",
                    "recordDeal",
                    "publish");
        }

        @Test
        @DisplayName("deals the whole deck to the same seats")
        void shouldDealToSameSeats() {
            final var session = completedTable(SEATS);
            final var sessionRepository = new InMemorySessionRepository(order, session);

            useCaseFor(sessionRepository).execute(session.sessionId(), PlayerBuilder.DEFAULT_TOKEN);

            assertThat(handRepository.recordDealCalls()).isOne();
            assertThat(handRepository.recordedHands().totalCards())
                    .as("the whole deck is redealt, with nothing discarded (EOP-92)")
                    .isEqualTo(DeckFixture.fullDeck().size());
            assertThat(handRepository.recordedHands().seats())
                    .containsExactly(0, 1, 2);
        }

        @Test
        @DisplayName("mints exactly one hand identifier per seat")
        void shouldMintOneHandIdentifierPerSeat() {
            final var session = completedTable(SEATS);
            final var sessionRepository = new InMemorySessionRepository(order, session);

            useCaseFor(sessionRepository).execute(session.sessionId(), PlayerBuilder.DEFAULT_TOKEN);

            assertThat(identifiers.issued()).isEqualTo(SEATS);
        }

        @Test
        @DisplayName("publishes HAND_DEALT after the deal is written")
        void shouldPublishHandDealtAfterWrite() {
            final var session = completedTable(SEATS);
            final var sessionRepository = new InMemorySessionRepository(order, session);

            useCaseFor(sessionRepository).execute(session.sessionId(), PlayerBuilder.DEFAULT_TOKEN);

            assertThat(publisher.published()).singleElement().satisfies(event -> {
                assertThat(event.type()).isEqualTo(SessionEventType.HAND_DEALT);
                assertThat(event.sessionId()).isEqualTo(session.sessionId());
                assertThat(event.occurredAt()).isEqualTo(NOW);
            });
            assertThat(order).containsSubsequence("recordDeal", "publish");
        }
    }

    @Nested
    @DisplayName("Authorisation")
    class Authorisation {

        @Test
        @DisplayName("refuses a participant, and writes nothing while refusing")
        void shouldRefuseAParticipant() {
            final var session = completedTable(SEATS);
            final var sessionRepository = new InMemorySessionRepository(order, session);
            final var useCase = useCaseFor(sessionRepository);
            final var participant = tokenForSeat(1);

            assertThatExceptionOfType(NotFacilitatorException.class)
                    .isThrownBy(() -> useCase.execute(session.sessionId(), participant));

            assertThat(handRepository.recordDealCalls()).isZero();
            assertThat(shuffler.calls()).isZero();
            assertThat(order).doesNotContain("clearTricksForNewGame", "clearHandsForNewGame",
                    "resetToInProgress", "recordDeal", "publish");
        }

        @Test
        @DisplayName("refuses a stranger, and writes nothing while refusing")
        void shouldRefuseAStranger() {
            final var session = completedTable(SEATS);
            final var sessionRepository = new InMemorySessionRepository(order, session);
            final var useCase = useCaseFor(sessionRepository);

            assertThatExceptionOfType(PlayerNotRecognisedException.class)
                    .isThrownBy(() -> useCase.execute(session.sessionId(), "not-a-seated-player"));

            assertThat(handRepository.recordDealCalls()).isZero();
            assertThat(shuffler.calls()).isZero();
        }

        @Test
        @DisplayName("refuses a session that is still in progress, not completed")
        void shouldRefuseAnInProgressSession() {
            final var session = aSession().withPlayerCount(SEATS).withStatus(SessionStatus.IN_PROGRESS).build();
            final var sessionRepository = new InMemorySessionRepository(order, session);
            final var useCase = useCaseFor(sessionRepository);

            assertThatExceptionOfType(GameNotCompletedException.class)
                    .isThrownBy(() -> useCase.execute(session.sessionId(), PlayerBuilder.DEFAULT_TOKEN))
                    .satisfies(ex -> assertThat(ex.sessionId()).isEqualTo(session.sessionId()));

            assertThat(handRepository.recordDealCalls()).isZero();
            assertThat(shuffler.calls()).isZero();
            assertThat(order).doesNotContain("clearTricksForNewGame", "clearHandsForNewGame",
                    "resetToInProgress", "recordDeal");
        }

        @Test
        @DisplayName("refuses a lobby session, not completed")
        void shouldRefuseALobbySession() {
            final var session = aSession().withPlayerCount(SEATS).withStatus(SessionStatus.LOBBY).build();
            final var sessionRepository = new InMemorySessionRepository(order, session);
            final var useCase = useCaseFor(sessionRepository);

            assertThatExceptionOfType(GameNotCompletedException.class)
                    .isThrownBy(() -> useCase.execute(session.sessionId(), PlayerBuilder.DEFAULT_TOKEN));

            assertThat(handRepository.recordDealCalls()).isZero();
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("rejects a null session identifier")
        void shouldRejectNullSessionId() {
            final var sessionRepository = new InMemorySessionRepository(order);
            final var useCase = useCaseFor(sessionRepository);

            assertThatNullPointerException()
                    .isThrownBy(() -> useCase.execute(null, PlayerBuilder.DEFAULT_TOKEN));
        }

        @Test
        @DisplayName("reports an unknown session as absent")
        void shouldReportUnknownSession() {
            final var sessionRepository = new InMemorySessionRepository(order);
            final var useCase = useCaseFor(sessionRepository);

            assertThatExceptionOfType(org.maglez.eop.domain.SessionNotFoundException.class)
                    .isThrownBy(() -> useCase.execute(UUID.randomUUID(), PlayerBuilder.DEFAULT_TOKEN));
        }
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("rejects a null resolvePlayerPort")
        void shouldRejectNullResolvePlayerPort() {
            final var sessionRepository = new InMemorySessionRepository(order);
            assertThatNullPointerException().isThrownBy(() -> new NewGameService(
                    null, handRepository, trickRepository, sessionRepository, dealer(), FIXED));
        }

        @Test
        @DisplayName("rejects a null handRepository")
        void shouldRejectNullHandRepository() {
            final var sessionRepository = new InMemorySessionRepository(order);
            assertThatNullPointerException().isThrownBy(() -> new NewGameService(
                    new ResolvePlayerService(sessionRepository, Clock.systemUTC()),
                    null, trickRepository, sessionRepository, dealer(), FIXED));
        }

        @Test
        @DisplayName("rejects a null trickRepository")
        void shouldRejectNullTrickRepository() {
            final var sessionRepository = new InMemorySessionRepository(order);
            assertThatNullPointerException().isThrownBy(() -> new NewGameService(
                    new ResolvePlayerService(sessionRepository, Clock.systemUTC()),
                    handRepository, null, sessionRepository, dealer(), FIXED));
        }

        @Test
        @DisplayName("rejects a null sessionRepository")
        void shouldRejectNullSessionRepository() {
            final var sessionRepository = new InMemorySessionRepository(order);
            assertThatNullPointerException().isThrownBy(() -> new NewGameService(
                    new ResolvePlayerService(sessionRepository, Clock.systemUTC()),
                    handRepository, trickRepository, null, dealer(), FIXED));
        }

        @Test
        @DisplayName("rejects a null handDealer")
        void shouldRejectNullHandDealer() {
            final var sessionRepository = new InMemorySessionRepository(order);
            assertThatNullPointerException().isThrownBy(() -> new NewGameService(
                    new ResolvePlayerService(sessionRepository, Clock.systemUTC()),
                    handRepository, trickRepository, sessionRepository, null, FIXED));
        }

        @Test
        @DisplayName("rejects a null clock")
        void shouldRejectNullClock() {
            final var sessionRepository = new InMemorySessionRepository(order);
            assertThatNullPointerException().isThrownBy(() -> new NewGameService(
                    new ResolvePlayerService(sessionRepository, Clock.systemUTC()),
                    handRepository, trickRepository, sessionRepository, dealer(), null));
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static GameSession completedTable(final int players) {
        return aSession().withPlayerCount(players).withStatus(SessionStatus.COMPLETED).build();
    }

    private static String tokenForSeat(final int seat) {
        return PlayerBuilder.DEFAULT_TOKEN + "-" + seat;
    }

    private HandDealer dealer() {
        return new HandDealer(cardRepository, shuffler, handRepository, identifiers, publisher, FIXED);
    }

    private NewGamePort useCaseFor(final InMemorySessionRepository sessionRepository) {
        return new NewGameService(
                new ResolvePlayerService(sessionRepository, Clock.systemUTC()),
                handRepository,
                trickRepository,
                sessionRepository,
                dealer(),
                FIXED);
    }
}
