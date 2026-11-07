package org.maglez.eop.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.maglez.eop.domain.GameSessionBuilder.aSession;
import static org.maglez.eop.domain.PlayerBuilder.aParticipant;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.maglez.eop.domain.ConnectionStatus;
import org.maglez.eop.domain.GameSession;
import org.maglez.eop.domain.IdentityTokenHash;
import org.maglez.eop.domain.PlayerRole;
import org.maglez.eop.domain.SeatAlreadyTakenException;
import org.maglez.eop.domain.SessionFullException;
import org.maglez.eop.domain.SessionNotJoinableException;
import org.maglez.eop.domain.SessionStatus;
import org.maglez.eop.domain.UnknownJoinCodeException;
import org.maglez.eop.application.TooManyJoinAttemptsException;
import org.maglez.eop.application.port.in.JoinSessionPort;
import org.maglez.eop.application.port.in.SessionEventType;
import org.maglez.eop.application.port.out.FixedIdentityTokenGenerator;
import org.maglez.eop.application.port.out.InMemorySessionRepository;
import org.maglez.eop.application.port.out.QueuedIdentifierGenerator;
import org.maglez.eop.application.port.out.RecordingJoinAttemptLimiter;
import org.maglez.eop.application.port.out.RecordingSessionEventPublisher;
import org.maglez.eop.domain.GameSessionBuilder;
import org.maglez.eop.domain.PlayerBuilder;

/**
 * Behaviour of the use case that seats a second player.
 *
 * <p>Three properties matter more than the happy path. The throttle is consulted
 * before the code is parsed, so that a flood of malformed guesses costs the guesser
 * the same as a flood of well formed ones. Every rejection looks identical from
 * outside, so the endpoint cannot be used to enumerate live sessions at forty bits
 * of entropy. And a lost seat race is retried against a fresh read rather than
 * surfaced, because two players pressing join together is ordinary, not exceptional.
 *
 * <p>JUnit's {@code @DisplayName} and the domain's display name type share a simple
 * name, so the domain type is reached through one fully qualified constant and the
 * annotation is imported normally.
 */
@DisplayName("JoinSessionPort")
class JoinSessionServiceTest {

    /**
     * Mirrors the private attempt budget in the use case. Pinning it here is the
     * point: raising the budget should force a deliberate change to this test. The
     * relationship this value has to {@link GameSession#MAXIMUM_PLAYERS} is asserted
     * separately, because it is an invariant rather than a tuning choice.
     */
    private static final int SEAT_ATTEMPTS = 8;

    private static final Instant NOW = Instant.parse("2026-02-01T09:30:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final org.maglez.eop.domain.DisplayName JOINER_NAME =
            org.maglez.eop.domain.DisplayName.of("Grace");

    private static final UUID JOINER_ID = UUID.fromString("00000000-0000-7000-8000-0000000000c1");
    private static final String TOKEN = "grace-plaintext-token";
    private static final String ADDRESS = "203.0.113.9";

    /** The code the seeded lobby really holds. */
    private static final String SEEDED_CODE = "ABC23456";

    /** The same code as a hurried human would type it, mixed case and padded. */
    private static final String TYPED_CODE = " aBc23456 ";

    private final QueuedIdentifierGenerator identifiers = new QueuedIdentifierGenerator(JOINER_ID);
    private final FixedIdentityTokenGenerator tokens = new FixedIdentityTokenGenerator(TOKEN);
    private final RecordingJoinAttemptLimiter limiter = new RecordingJoinAttemptLimiter();
    private final RecordingSessionEventPublisher publisher = new RecordingSessionEventPublisher();

    private JoinSessionPort useCaseFor(final InMemorySessionRepository repository) {
        return new JoinSessionService(repository, identifiers, tokens, limiter, publisher, FIXED);
    }

    @Nested
    @DisplayName("seating a player")
    class Seating {

        @Test
        @DisplayName("takes the next free seat and hands back a credential")
        void shouldSeatTheJoinerInTheNextFreeSeat() {
            final var repository = new InMemorySessionRepository(aSession().build());

            final var admission = useCaseFor(repository).execute(TYPED_CODE, JOINER_NAME, ADDRESS);

            assertThat(admission.playerId()).isEqualTo(JOINER_ID);
            assertThat(admission.playerToken()).isEqualTo(TOKEN);
            assertThat(admission.session().players()).hasSize(2);

            final var joined = admission.session().players().get(1);
            assertThat(joined.playerId()).isEqualTo(JOINER_ID);
            assertThat(joined.displayName()).isEqualTo(JOINER_NAME);
            assertThat(joined.seatOrder()).isEqualTo(1);
            assertThat(joined.role()).isEqualTo(PlayerRole.PARTICIPANT);
            assertThat(joined.connectionStatus()).isEqualTo(ConnectionStatus.CONNECTED);
            assertThat(joined.identityTokenHash()).isEqualTo(IdentityTokenHash.of(TOKEN));
            assertThat(joined.joinedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("folds a mistyped code to the canonical one before looking it up")
        void shouldFoldTheTypedCodeBeforeLookup() {
            final var repository = new InMemorySessionRepository(aSession().build());

            final var admission = useCaseFor(repository).execute(TYPED_CODE, JOINER_NAME, ADDRESS);

            assertThat(admission.session().joinCode().value()).isEqualTo(SEEDED_CODE);
        }

        @Test
        @DisplayName("hands the throttle the code exactly as it was typed, not as it was folded")
        void shouldThrottleOnTheRawAttempt() {
            final var repository = new InMemorySessionRepository(aSession().build());

            useCaseFor(repository).execute(TYPED_CODE, JOINER_NAME, ADDRESS);

            assertThat(limiter.checks()).hasSize(1);
            assertThat(limiter.checks().get(0).address()).isEqualTo(ADDRESS);
            assertThat(limiter.checks().get(0).code()).isEqualTo(TYPED_CODE);
        }

        @Test
        @DisplayName("announces the arrival exactly once")
        void shouldPublishOnePlayerJoinedEvent() {
            final var repository = new InMemorySessionRepository(aSession().build());

            useCaseFor(repository).execute(TYPED_CODE, JOINER_NAME, ADDRESS);

            assertThat(publisher.published()).hasSize(1);
            final var event = publisher.published().get(0);
            assertThat(event.type()).isEqualTo(SessionEventType.PLAYER_JOINED);
            assertThat(event.sessionId()).isEqualTo(aSession().build().sessionId());
            assertThat(event.occurredAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("attributes no failure when the join succeeds")
        void shouldNotChargeASuccessfulJoinAgainstTheThrottle() {
            final var repository = new InMemorySessionRepository(aSession().build());

            useCaseFor(repository).execute(TYPED_CODE, JOINER_NAME, ADDRESS);

            assertThat(limiter.failures()).isEmpty();
        }
    }

    @Nested
    @DisplayName("refusing a join")
    class Refusing {

        @Test
        @DisplayName("treats a code that cannot be a code as an unknown code")
        void shouldRejectAMalformedCodeAsUnknown() {
            final var repository = new InMemorySessionRepository(aSession().build());

            assertThatExceptionOfType(UnknownJoinCodeException.class)
                    .isThrownBy(() -> useCaseFor(repository).execute("!!!", JOINER_NAME, ADDRESS))
                    .withMessageContaining("No joinable session");

            assertThat(limiter.failures()).hasSize(1);
            assertThat(limiter.failures().get(0).code()).isEqualTo("!!!");
        }

        @Test
        @DisplayName("spends no identifier and no credential on a malformed code")
        void shouldNotMintAnythingForAMalformedCode() {
            final var repository = new InMemorySessionRepository(aSession().build());

            assertThatExceptionOfType(UnknownJoinCodeException.class)
                    .isThrownBy(() -> useCaseFor(repository).execute("!!!", JOINER_NAME, ADDRESS));

            assertThat(identifiers.issued()).isZero();
            assertThat(tokens.issued()).isZero();
            assertThat(publisher.published()).isEmpty();
        }

        @Test
        @DisplayName("answers a well formed code for no session exactly as it answers nonsense")
        void shouldNotDistinguishAnUnknownCodeFromNonsense() {
            final var nonsense = new InMemorySessionRepository(aSession().build());
            final var unknown = new InMemorySessionRepository(aSession().build());

            final var forNonsense = catchJoinRefusal(nonsense, "!!!");
            final var forUnknown = catchJoinRefusal(unknown, "ZZZZZZZZ");

            assertThat(forUnknown).hasMessage(forNonsense.getMessage());
            assertThat(forUnknown).hasSameClassAs(forNonsense);
        }

        @Test
        @DisplayName("charges a well formed code that matches nothing against the throttle")
        void shouldChargeAnUnknownCodeAgainstTheThrottle() {
            final var repository = new InMemorySessionRepository(aSession().build());

            assertThatExceptionOfType(UnknownJoinCodeException.class)
                    .isThrownBy(() -> useCaseFor(repository).execute("ZZZZZZZZ", JOINER_NAME, ADDRESS));

            assertThat(limiter.failures()).hasSize(1);
            assertThat(limiter.failures().get(0).code()).isEqualTo("ZZZZZZZZ");
        }

        @Test
        @DisplayName("refuses a throttled caller before parsing, so nonsense still costs them")
        void shouldConsultTheThrottleBeforeParsing() {
            final var repository = new InMemorySessionRepository(aSession().build());
            limiter.refuseWith(Duration.ofSeconds(45));

            assertThatExceptionOfType(TooManyJoinAttemptsException.class)
                    .isThrownBy(() -> useCaseFor(repository).execute("!!!", JOINER_NAME, ADDRESS))
                    .satisfies(refused -> assertThat(refused.retryAfter()).isEqualTo(Duration.ofSeconds(45)));

            assertThat(limiter.checks()).hasSize(1);
            assertThat(limiter.failures()).isEmpty();
        }

        @Test
        @DisplayName("refuses a table that has already left the lobby")
        void shouldRefuseASessionThatIsUnderway() {
            final var repository = new InMemorySessionRepository(
                    aSession().withStatus(SessionStatus.IN_PROGRESS).build());

            assertThatExceptionOfType(SessionNotJoinableException.class)
                    .isThrownBy(() -> useCaseFor(repository).execute(SEEDED_CODE, JOINER_NAME, ADDRESS))
                    .withMessageContaining("IN_PROGRESS");

            assertThat(limiter.failures()).hasSize(1);
            assertThat(limiter.failures().get(0).code()).isEqualTo(SEEDED_CODE);
            assertThat(publisher.published()).isEmpty();
        }

        @Test
        @DisplayName("refuses a seventh player at a table of six")
        void shouldRefuseASeventhPlayer() {
            final var repository = new InMemorySessionRepository(aSession().withPlayerCount(6).build());

            assertThatExceptionOfType(SessionFullException.class)
                    .isThrownBy(() -> useCaseFor(repository).execute(SEEDED_CODE, JOINER_NAME, ADDRESS))
                    .withMessageContaining("maximum of 6 players");

            assertThat(repository.seatPlayerCalls()).isZero();
            assertThat(limiter.failures()).hasSize(1);
            assertThat(limiter.failures().get(0).code()).isEqualTo(SEEDED_CODE);
            assertThat(publisher.published()).isEmpty();
        }

        @Test
        @DisplayName("rejects a missing display name before consulting the throttle")
        void shouldRejectAMissingDisplayName() {
            final var repository = new InMemorySessionRepository(aSession().build());

            assertThatNullPointerException()
                    .isThrownBy(() -> useCaseFor(repository).execute(SEEDED_CODE, null, ADDRESS))
                    .withMessageContaining("displayName");

            assertThat(limiter.checks()).isEmpty();
        }

        private Throwable catchJoinRefusal(final InMemorySessionRepository repository, final String attempt) {
            try {
                new JoinSessionService(
                        repository,
                        new QueuedIdentifierGenerator(JOINER_ID),
                        new FixedIdentityTokenGenerator(TOKEN),
                        new RecordingJoinAttemptLimiter(),
                        new RecordingSessionEventPublisher(),
                        FIXED)
                        .execute(attempt, JOINER_NAME, ADDRESS);
            } catch (final UnknownJoinCodeException refused) {
                return refused;
            }
            throw new AssertionError("the join should have been refused");
        }
    }

    @Nested
    @DisplayName("contesting a seat")
    class ContestingASeat {

        @Test
        @DisplayName("takes the next seat along when another player wins the race")
        void shouldRetryOnTheNextSeatAfterLosingARace() {
            final var repository = new InMemorySessionRepository(aSession().build());
            repository.loseNextSeatRaceTo(aParticipant(1).build());

            final var admission = useCaseFor(repository).execute(SEEDED_CODE, JOINER_NAME, ADDRESS);

            assertThat(repository.seatPlayerCalls()).isEqualTo(2);
            assertThat(admission.session().players()).hasSize(3);
            assertThat(admission.session().players().get(2).playerId()).isEqualTo(JOINER_ID);
            assertThat(admission.session().players().get(2).seatOrder()).isEqualTo(2);
        }

        @Test
        @DisplayName("keeps the same credential across a retry, because the player is the same person")
        void shouldNotMintASecondCredentialOnRetry() {
            final var repository = new InMemorySessionRepository(aSession().build());
            repository.loseNextSeatRaceTo(aParticipant(1).build());

            final var admission = useCaseFor(repository).execute(SEEDED_CODE, JOINER_NAME, ADDRESS);

            assertThat(admission.playerToken()).isEqualTo(TOKEN);
            assertThat(identifiers.issued()).isEqualTo(1);
            assertThat(tokens.issued()).isEqualTo(1);
        }

        @Test
        @DisplayName("announces the arrival once, not once per attempt")
        void shouldPublishOnlyAfterTheSeatIsWon() {
            final var repository = new InMemorySessionRepository(aSession().build());
            repository.loseNextSeatRaceTo(aParticipant(1).build());

            useCaseFor(repository).execute(SEEDED_CODE, JOINER_NAME, ADDRESS);

            assertThat(publisher.published()).hasSize(1);
        }

        @Test
        @DisplayName("gives up after the attempt budget rather than looping for ever")
        void shouldGiveUpAfterTheAttemptBudget() {
            final var repository = new InMemorySessionRepository(aSession().build());
            repository.refuseEverySeat();

            assertThatExceptionOfType(SeatAlreadyTakenException.class)
                    .isThrownBy(() -> useCaseFor(repository).execute(SEEDED_CODE, JOINER_NAME, ADDRESS));

            assertThat(repository.seatPlayerCalls()).isEqualTo(SEAT_ATTEMPTS);
            assertThat(publisher.published()).isEmpty();
        }

        /**
         * The budget exceeding the table size is what keeps this refusal off the path a
         * caller normally travels: a joiner losing races at an otherwise quiet lobby
         * runs out of seats, and is refused as full, before it runs out of attempts.
         * Only a stale fullness check under concurrent joins defeats that arithmetic.
         * Lowering the budget below the table size, or adding a way to vacate a seat,
         * would make the refusal routine — so the relationship is asserted rather than
         * left as a coincidence between two constants.
         */
        @Test
        @DisplayName("budgets more attempts than the table has seats, so a quiet lobby fills before the budget runs out")
        void shouldBudgetMoreAttemptsThanTheTableHasSeats() {
            assertThat(SEAT_ATTEMPTS).isGreaterThan(GameSession.MAXIMUM_PLAYERS);
        }

        @Test
        @DisplayName("re-reads the session on every attempt rather than trusting a stale copy")
        void shouldRereadTheSessionOnEveryAttempt() {
            final var repository = new InMemorySessionRepository(aSession().build());
            repository.loseNextSeatRaceTo(aParticipant(1).build());

            final var admission = useCaseFor(repository).execute(SEEDED_CODE, JOINER_NAME, ADDRESS);
            final GameSession stored = repository.findById(admission.session().sessionId()).orElseThrow();

            assertThat(stored.players()).extracting("seatOrder").containsExactly(0, 1, 2);
        }
    }
}
