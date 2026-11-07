package org.maglez.eop.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.maglez.eop.domain.GameSessionBuilder.aSession;
import static org.maglez.eop.domain.PlayerBuilder.aParticipant;
import static org.maglez.eop.domain.PlayerBuilder.aPlayer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.maglez.eop.domain.GameNotCompletedException;
import org.maglez.eop.domain.GameResult;
import org.maglez.eop.domain.GameSession;
import org.maglez.eop.domain.Player;
import org.maglez.eop.domain.PlayerBuilder;
import org.maglez.eop.domain.PlayerNotRecognisedException;
import org.maglez.eop.domain.ScoreSheet;
import org.maglez.eop.domain.SessionNotFoundException;
import org.maglez.eop.domain.SessionStatus;

import org.maglez.eop.application.GameResultNotRecordedException;
import org.maglez.eop.application.port.in.GetLeaderboardPort;
import org.maglez.eop.application.port.in.LeaderboardResult;
import org.maglez.eop.application.port.out.GameResultRepository;
import org.maglez.eop.application.port.out.InMemorySessionRepository;
import org.maglez.eop.application.port.out.InMemoryTrickRepository;
import org.maglez.eop.domain.GameSessionBuilder;

/**
 * Exercises {@link GetLeaderboardPort}.
 *
 * <p>The leaderboard is only available once the session is COMPLETED and a result has been
 * persisted. Any other state is a conflict. Authorisation is required: a stranger who guesses
 * a session identifier must not read the result.
 *
 * <p>The use case reads tricks to compute the STRIDE breakdown. Assertions that the trick
 * repository was not asked pin the short-circuit contract (auth failures before any read).
 */
@DisplayName("GetLeaderboardPort")
class GetLeaderboardServiceTest {

    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-7000-8000-0000000000d0");
    private static final UUID OTHER_SESSION_ID = UUID.fromString("00000000-0000-7000-8000-0000000000d1");
    private static final UUID RESULT_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final Instant STARTED_AT = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant FINALISED_AT = Instant.parse("2026-01-01T11:00:00Z");

    private final List<String> order = new ArrayList<>();
    private final InMemorySessionRepository sessionRepository = new InMemorySessionRepository(order, completedTable());
    private final InMemoryGameResultRepository resultRepository = new InMemoryGameResultRepository();
    private final InMemoryTrickRepository trickRepository = new InMemoryTrickRepository(order);

    @Nested
    @DisplayName("reading the leaderboard")
    class ReadingTheLeaderboard {

        @Test
        @DisplayName("carries the status read off the resolved session, not a literal")
        void shouldCarryTheResolvedSessionStatus() {
            // EOP-87: the adapter used to write the string "COMPLETED" itself, which was correct
            // only because the guard above refuses every other status. The status now travels from
            // the session the repository returned, so it cannot drift from it.
            //
            // This asserts the value is the resolved session's own status. It cannot, by
            // construction, distinguish that from a hardcoded COMPLETED, because the guard one step
            // above makes every other status unreachable here. The test that does draw that
            // distinction is GameOverControllerTest.shouldEchoTheResolvedSessionStatus, which stubs
            // this use case and hands the adapter an IN_PROGRESS result the guard would never allow.
            resultRepository.seed(aGameResult());

            final var returned = useCase().execute(SESSION_ID, tokenFor(0));

            assertThat(returned.sessionStatus()).isEqualTo(completedTable().status());
        }

        @Test
        @DisplayName("reads the result row as an existence gate without returning it")
        void shouldNotReturnTheGameResult() {
            // The row is still required — shouldThrowWhenNoResultPersisted proves that — but it is
            // deliberately not carried out of the use case, because standings are recomputed from
            // the tricks on every read (ADR-030) and no consumer ever read it.
            //
            // The record components are asserted reflectively rather than by comparing the returned
            // value against a rebuilt one: a record's generated equals() makes that comparison a
            // tautology, so it would survive re-adding a GameResult component. This fails the
            // moment anybody puts the row back into the return value.
            resultRepository.seed(aGameResult());

            final var returned = useCase().execute(SESSION_ID, tokenFor(0));

            assertThat(LeaderboardResult.class.getRecordComponents())
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .containsExactly("sessionStatus", "scoreSheet");
            assertThat(returned.scoreSheet()).isNotNull();
            assertThat(resultRepository.findBySessionIdCalls()).isEqualTo(1);
        }

        @Test
        @DisplayName("returns a score sheet for the completed session")
        void shouldReturnAScoreSheet() {
            resultRepository.seed(aGameResult());

            final var returned = useCase().execute(SESSION_ID, tokenFor(0));

            assertThat(returned.scoreSheet()).isNotNull();
        }

        @Test
        @DisplayName("throws GameNotCompletedException when the session is not completed")
        void shouldThrowWhenSessionNotCompleted() {
            final var inProgressSession = aSession()
                    .withSessionId(SESSION_ID)
                    .withStatus(SessionStatus.IN_PROGRESS)
                    .withPlayers(seatedPlayers())
                    .build();
            final var repo = new InMemorySessionRepository(order, inProgressSession);
            final var useCase = new GetLeaderboardService(
                    new ResolvePlayerService(repo, java.time.Clock.systemUTC()),
                    resultRepository,
                    trickRepository);

            assertThatExceptionOfType(GameNotCompletedException.class)
                    .isThrownBy(() -> useCase.execute(SESSION_ID, tokenFor(0)));
        }

        @Test
        @DisplayName("throws GameResultNotRecordedException when the session is completed with no result")
        void shouldThrowWhenNoResultPersisted() {
            // resultRepository is empty — no result seeded

            assertThatExceptionOfType(GameResultNotRecordedException.class)
                    .isThrownBy(() -> useCase().execute(SESSION_ID, tokenFor(0)))
                    .withMessageContaining(SESSION_ID.toString())
                    .withMessageContaining("no recorded result");
        }

        @Test
        @DisplayName("does not reuse SessionNotFoundException for a completed session with no result")
        void shouldNotReuseSessionNotFoundForAnUnrecordedResult() {
            // Before EOP-86 this line threw SessionNotFoundException, so a seated player was told
            // their own session did not exist. The session is present and the caller is seated at
            // it — only the result row is missing, which is a different fact and now a different type.
            assertThatExceptionOfType(GameResultNotRecordedException.class)
                    .isThrownBy(() -> useCase().execute(SESSION_ID, tokenFor(0)))
                    .isNotInstanceOf(SessionNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("GameResultNotRecordedException")
    class TheUnrecordedResultException {

        @Test
        @DisplayName("refuses a null session identifier")
        void shouldRefuseANullSessionId() {
            // The identifier is the only thing this exception carries, and the handler interpolates
            // it straight into the problem detail. A null would reach the client as the string
            // "null", so the constructor rejects it rather than rendering it.
            assertThatNullPointerException()
                    .isThrownBy(() -> new GameResultNotRecordedException(null))
                    .withMessageContaining("sessionId is required");
        }

        @Test
        @DisplayName("exposes the session identifier it was given")
        void shouldExposeTheSessionId() {
            assertThat(new GameResultNotRecordedException(SESSION_ID).sessionId()).isEqualTo(SESSION_ID);
        }
    }

    @Nested
    @DisplayName("authorising the caller")
    class AuthorisingTheCaller {

        @Test
        @DisplayName("refuses a token that belongs to no seat here, before reading any result")
        void shouldRefuseAStrangerBeforeReadingResult() {
            final var useCase = useCase();

            assertThatExceptionOfType(PlayerNotRecognisedException.class)
                    .isThrownBy(() -> useCase.execute(SESSION_ID, "a token from somewhere else"));

            assertThat(resultRepository.findBySessionIdCalls()).isZero();
        }

        @Test
        @DisplayName("refuses a missing credential, before reading any result")
        void shouldRefuseAMissingCredentialBeforeReadingResult() {
            final var useCase = useCase();

            assertThatExceptionOfType(PlayerNotRecognisedException.class)
                    .isThrownBy(() -> useCase.execute(SESSION_ID, null));

            assertThat(resultRepository.findBySessionIdCalls()).isZero();
        }

        @Test
        @DisplayName("refuses a session that does not exist, before reading any result")
        void shouldRefuseAnUnknownSessionBeforeReadingResult() {
            final var useCase = useCase();

            assertThatExceptionOfType(SessionNotFoundException.class)
                    .isThrownBy(() -> useCase.execute(OTHER_SESSION_ID, tokenFor(0)));

            assertThat(resultRepository.findBySessionIdCalls()).isZero();
        }

        @Test
        @DisplayName("refuses a null session identifier")
        void shouldRefuseANullSessionIdentifier() {
            final var useCase = useCase();

            assertThatNullPointerException().isThrownBy(() -> useCase.execute(null, tokenFor(0)));

            assertThat(resultRepository.findBySessionIdCalls()).isZero();
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("refuses to be built without its collaborators")
        void shouldRefuseToBeBuiltWithoutCollaborators() {
            final var resolver = new ResolvePlayerService(sessionRepository, java.time.Clock.systemUTC());

            assertThatNullPointerException().isThrownBy(
                    () -> new GetLeaderboardService(null, resultRepository, trickRepository));
            assertThatNullPointerException().isThrownBy(
                    () -> new GetLeaderboardService(resolver, null, trickRepository));
            assertThatNullPointerException().isThrownBy(
                    () -> new GetLeaderboardService(resolver, resultRepository, null));
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private GetLeaderboardPort useCase() {
        final var resolver = new ResolvePlayerService(sessionRepository, java.time.Clock.systemUTC());
        return new GetLeaderboardService(resolver, resultRepository, trickRepository);
    }

    private static GameSession completedTable() {
        return aSession()
                .withSessionId(SESSION_ID)
                .withStatus(SessionStatus.COMPLETED)
                .withPlayers(seatedPlayers())
                .build();
    }

    private static List<Player> seatedPlayers() {
        return List.of(
                aPlayer().build(),
                aParticipant(1).build(),
                aParticipant(2).build());
    }

    private static String tokenFor(final int seatOrder) {
        final var base = PlayerBuilder.DEFAULT_TOKEN;
        return seatOrder == 0 ? base : base + "-" + seatOrder;
    }

    private static GameResult aGameResult() {
        final var session = completedTable();
        final var scoreSheet = ScoreSheet.of(session.players(), List.of());
        return GameResult.of(RESULT_ID, session, scoreSheet, STARTED_AT, FINALISED_AT);
    }

    // -------------------------------------------------------------------------
    // In-memory fake for GameResultRepository
    // -------------------------------------------------------------------------

    private static final class InMemoryGameResultRepository implements GameResultRepository {

        private GameResult stored;
        private int findBySessionIdCalls;

        void seed(final GameResult result) {
            this.stored = result;
        }

        int findBySessionIdCalls() {
            return findBySessionIdCalls;
        }

        @Override
        public void save(final GameResult result) {
            this.stored = result;
        }

        @Override
        public Optional<GameResult> findBySessionId(final UUID sessionId) {
            findBySessionIdCalls++;
            if (stored != null && stored.sessionId().equals(sessionId)) {
                return Optional.of(stored);
            }
            return Optional.empty();
        }
    }
}
