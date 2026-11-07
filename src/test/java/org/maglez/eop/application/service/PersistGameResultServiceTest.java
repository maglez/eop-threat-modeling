package org.maglez.eop.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.maglez.eop.domain.GameSessionBuilder.aSession;
import static org.maglez.eop.domain.PlayerBuilder.aParticipant;
import static org.maglez.eop.domain.PlayerBuilder.aPlayer;
import static org.maglez.eop.domain.TrickBuilder.aTrick;
import static org.maglez.eop.domain.TrickPlayBuilder.aPlayBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.maglez.eop.domain.DeckFixture;
import org.maglez.eop.domain.GameResult;
import org.maglez.eop.domain.Rank;
import org.maglez.eop.domain.SessionNotFoundException;
import org.maglez.eop.domain.SessionStatus;
import org.maglez.eop.domain.StrideCategory;
import org.maglez.eop.domain.Trick;
import org.maglez.eop.application.port.in.PersistGameResultPort;
import org.maglez.eop.application.port.out.GameResultRepository;
import org.maglez.eop.application.port.out.InMemorySessionRepository;
import org.maglez.eop.application.port.out.InMemoryTrickRepository;
import org.maglez.eop.application.port.out.QueuedIdentifierGenerator;
import org.maglez.eop.domain.GameSession;
import org.maglez.eop.domain.GameSessionBuilder;

import org.maglez.eop.domain.PlayerBuilder;
import org.maglez.eop.domain.TrickBuilder;
import org.maglez.eop.domain.TrickPlayBuilder;

/**
 * Exercises {@link PersistGameResultPort}.
 *
 * <p>This use case is called internally after the last trick resolves. It reads the session and
 * all tricks, derives the score sheet, and writes a {@link GameResult} to the repository.
 *
 * <p>No authorisation check is performed here — the caller (trick resolution path) has already
 * authorised the play. What is pinned here is that the result is derived from the correct session
 * and that the repository receives a well-formed result.
 */
@DisplayName("PersistGameResultPort")
class PersistGameResultServiceTest {

    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-7000-8000-0000000000d0");
    private static final UUID RESULT_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T11:00:00Z");

    private static final int SEATS = 3;
    private static final long TRICK_PREFIX = 1000L;
    private static final long PLAY_PREFIX = 900L;

    private final List<String> order = new ArrayList<>();
    private final InMemorySessionRepository sessionRepository =
            new InMemorySessionRepository(order, completedTable());
    private final InMemoryTrickRepository trickRepository = new InMemoryTrickRepository(order);
    private final InMemoryGameResultRepository resultRepository = new InMemoryGameResultRepository();
    private final QueuedIdentifierGenerator identifierGenerator = new QueuedIdentifierGenerator(RESULT_ID);
    private final Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    @Nested
    @DisplayName("persisting the result")
    class PersistingTheResult {

        @Test
        @DisplayName("saves a game result with the correct session identifier")
        void shouldSaveResultWithCorrectSessionId() {
            trickRepository.seededWithHistory(resolvedTrick(1, Rank.FIVE, Rank.KING, Rank.SEVEN));

            useCase().execute(SESSION_ID);

            assertThat(resultRepository.saved()).isNotNull();
            assertThat(resultRepository.saved().sessionId()).isEqualTo(SESSION_ID);
        }

        @Test
        @DisplayName("saves a game result with the correct timestamps")
        void shouldSaveResultWithCorrectTimestamps() {
            trickRepository.seededWithHistory(resolvedTrick(1, Rank.FIVE, Rank.KING, Rank.SEVEN));

            useCase().execute(SESSION_ID);

            // startedAt and finalisedAt are both set to clock.instant() since the session
            // row does not carry a startedAt timestamp
            assertThat(resultRepository.saved().startedAt()).isEqualTo(FIXED_NOW);
            assertThat(resultRepository.saved().finalisedAt()).isEqualTo(FIXED_NOW);
        }

        @Test
        @DisplayName("saves a game result with standings derived from the tricks")
        void shouldSaveResultWithStandingsFromTricks() {
            // Seat 1 wins the trick (KING beats FIVE and SEVEN)
            trickRepository.seededWithHistory(resolvedTrick(1, Rank.FIVE, Rank.KING, Rank.SEVEN));

            useCase().execute(SESSION_ID);

            final var standings = resultRepository.saved().standings();
            assertThat(standings).hasSize(SEATS);
            // Seat 1 has 2 points (threat + trick), others have 1 each
            assertThat(standings.get(0).points()).isEqualTo(2);
            assertThat(standings.get(0).seatOrder()).isEqualTo(1);
        }

        @Test
        @DisplayName("saves a result with no standings when no tricks were played")
        void shouldSaveResultWithZeroPointsWhenNoTricks() {
            // No tricks seeded — empty history

            useCase().execute(SESSION_ID);

            assertThat(resultRepository.saved()).isNotNull();
            assertThat(resultRepository.saved().standings()).hasSize(SEATS);
            assertThat(resultRepository.saved().standings())
                    .extracting(s -> s.points())
                    .containsOnly(0);
        }

        @Test
        @DisplayName("reads tricks for the correct session")
        void shouldReadTricksForTheCorrectSession() {
            useCase().execute(SESSION_ID);

            assertThat(trickRepository.tricksAskedFor()).contains(SESSION_ID);
        }
    }

    @Nested
    @DisplayName("error handling")
    class ErrorHandling {

        @Test
        @DisplayName("throws SessionNotFoundException when the session does not exist")
        void shouldThrowWhenSessionNotFound() {
            final var unknownId = UUID.fromString("00000000-0000-7000-8000-000000000099");

            assertThatExceptionOfType(SessionNotFoundException.class)
                    .isThrownBy(() -> useCase().execute(unknownId));
        }

        @Test
        @DisplayName("refuses a null sessionId")
        void shouldRefuseNullSessionId() {
            assertThatNullPointerException().isThrownBy(() -> useCase().execute(null));
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("refuses to be built without its collaborators")
        void shouldRefuseToBeBuiltWithoutCollaborators() {
            assertThatNullPointerException().isThrownBy(
                    () -> new PersistGameResultService(null, trickRepository, resultRepository, identifierGenerator, clock));
            assertThatNullPointerException().isThrownBy(
                    () -> new PersistGameResultService(sessionRepository, null, resultRepository, identifierGenerator, clock));
            assertThatNullPointerException().isThrownBy(
                    () -> new PersistGameResultService(sessionRepository, trickRepository, null, identifierGenerator, clock));
            assertThatNullPointerException().isThrownBy(
                    () -> new PersistGameResultService(sessionRepository, trickRepository, resultRepository, null, clock));
            assertThatNullPointerException().isThrownBy(
                    () -> new PersistGameResultService(sessionRepository, trickRepository, resultRepository, identifierGenerator, null));
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private PersistGameResultPort useCase() {
        return new PersistGameResultService(
                sessionRepository, trickRepository, resultRepository, identifierGenerator, clock);
    }

    private static org.maglez.eop.domain.GameSession completedTable() {
        return aSession()
                .withSessionId(SESSION_ID)
                .withStatus(SessionStatus.COMPLETED)
                .withPlayerCount(SEATS)
                .build();
    }

    private static Trick resolvedTrick(final int sequence, final Rank... ranks) {
        // Player IDs must match those produced by GameSessionBuilder.withPlayerCount(SEATS)
        // seat 0 → aPlayer() → "00000000-0000-7000-8000-000000000001"
        // seat N → aParticipant(N) → "00000000-0000-7000-8000-0000000000aN"
        final var playerIds = new java.util.ArrayList<UUID>();
        playerIds.add(aPlayer().build().playerId());
        for (int seat = 1; seat < ranks.length; seat++) {
            playerIds.add(aParticipant(seat).build().playerId());
        }

        var builder = aTrick()
                .withTrickId(new UUID(TRICK_PREFIX, sequence))
                .withSequence(sequence)
                .withLeaderSeat(0);
        for (int seatOrder = 0; seatOrder < ranks.length; seatOrder++) {
            final var card = DeckFixture.card(StrideCategory.SPOOFING, ranks[seatOrder]);
            builder = builder.andPlay(
                    aPlayBy(seatOrder, card)
                            .withPlayerId(playerIds.get(seatOrder))
                            .withTrickPlayId(new UUID(PLAY_PREFIX + sequence, seatOrder))
                            .build());
        }
        return builder.build().resolved();
    }

    // -------------------------------------------------------------------------
    // In-memory fake for GameResultRepository
    // -------------------------------------------------------------------------

    private static final class InMemoryGameResultRepository implements GameResultRepository {

        private GameResult saved;

        GameResult saved() {
            return saved;
        }

        @Override
        public void save(final GameResult result) {
            this.saved = result;
        }

        @Override
        public Optional<GameResult> findBySessionId(final UUID sessionId) {
            if (saved != null && saved.sessionId().equals(sessionId)) {
                return Optional.of(saved);
            }
            return Optional.empty();
        }
    }
}
