package org.maglez.eop.inbound.web;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.maglez.eop.domain.AlreadyPlayedInTrickException;
import org.maglez.eop.domain.CardAlreadyPlayedException;
import org.maglez.eop.domain.CardNotFoundException;
import org.maglez.eop.domain.CardNotInHandException;
import org.maglez.eop.domain.GameNotCompletedException;
import org.maglez.eop.domain.HandAlreadyDealtException;
import org.maglez.eop.domain.HandCompleteException;
import org.maglez.eop.domain.HandNotDealtException;
import org.maglez.eop.domain.IdentityTokenHash;
import org.maglez.eop.domain.InvalidInputException;
import org.maglez.eop.domain.JoinCodeUnavailableException;
import org.maglez.eop.domain.MustFollowSuitException;
import org.maglez.eop.domain.NoTamperingCardDealtException;
import org.maglez.eop.domain.NotFacilitatorException;
import org.maglez.eop.domain.NotYourSeatException;
import org.maglez.eop.domain.OutOfTurnException;
import org.maglez.eop.domain.PlayerMismatchException;
import org.maglez.eop.domain.PlayerNotInSessionException;
import org.maglez.eop.domain.PlayerNotRecognisedException;
import org.maglez.eop.domain.ScoreNotDerivableException;
import org.maglez.eop.domain.SeatAlreadyTakenException;

import org.maglez.eop.domain.SessionFullException;
import org.maglez.eop.domain.SessionNotFoundException;
import org.maglez.eop.domain.SessionNotInProgressException;
import org.maglez.eop.domain.SessionNotJoinableException;
import org.maglez.eop.domain.SessionStatus;
import org.maglez.eop.domain.StrideCategory;
import org.maglez.eop.domain.TooFewPlayersException;
import org.maglez.eop.domain.TrickAlreadyOpenException;
import org.maglez.eop.domain.TrickAlreadyResolvedException;
import org.maglez.eop.domain.TrickNotCompleteException;

import org.maglez.eop.domain.WinningPlayNotInTrickException;
import org.maglez.eop.application.GameResultNotRecordedException;
import org.maglez.eop.application.RateLimitedException;
import org.maglez.eop.application.TooManyJoinAttemptsException;
import org.maglez.eop.application.TooManySubscribersException;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.maglez.eop.domain.Card;
import org.maglez.eop.domain.Hand;
import org.maglez.eop.domain.Hands;
import org.maglez.eop.domain.Player;
import org.maglez.eop.domain.Trick;

/**
 * The error handling rules require a test for every mapped exception, because
 * this class is a single point of failure: a bug here hides every error the API
 * would otherwise report.
 *
 * <p>Handlers are called directly rather than through a request. Each one is a
 * pure function from an exception to a problem detail, so a servlet container
 * would add nothing but seconds; the status and title of every mapping are
 * pinned here, and the wiring that chooses a handler is proved once by the
 * endpoint tests.
 */
@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    /** A session identifier, reused so the tests read as one conversation. */
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-7000-8000-0000000000ff");

    /** A player identifier distinct from the session's. */
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-7000-8000-0000000000a1");

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("an unknown card is a 404 naming the identifier")
    void shouldMapCardNotFoundTo404() {
        final UUID missing = UUID.fromString("00000000-0000-4000-8000-0000000000ff");

        final ProblemDetail problem = handler.handleCardNotFound(new CardNotFoundException(missing));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problem.getTitle()).isEqualTo("Card not found");
        assertThat(problem.getDetail()).contains(missing.toString());
    }

    /**
     * The narrowed input mapping, and the two things that used to be wrong with it.
     *
     * <p>Until this handler was narrowed it answered every {@code IllegalArgumentException} with a 400 carrying the
     * exception's own message. That was two defects rather than one. A library-thrown message was echoed to the caller
     * verbatim, and — the less obvious half — because the mapping was more specific than the {@code Exception}
     * catch-all, a fault of that kind bypassed the only error-level log in the application: an internal defect was
     * billed to the caller as a 400 and left no record at all.</p>
     *
     * <p>Status assertions alone cannot show either half is fixed, so these tests attach a Logback appender to the
     * handler's own logger and assert on what was emitted as well as on what was returned.</p>
     */
    @Nested
    @DisplayName("mapping a rejected input value")
    class CallerInput {

        private final Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);

        private final ListAppender<ILoggingEvent> emitted = new ListAppender<>();

        private Level originalLevel;

        @BeforeEach
        void captureLogging() {
            originalLevel = logger.getLevel();
            logger.setLevel(Level.DEBUG);
            emitted.start();
            logger.addAppender(emitted);
        }

        @AfterEach
        void releaseLogging() {
            logger.detachAppender(emitted);
            emitted.stop();
            logger.setLevel(originalLevel);
        }

        @Test
        @DisplayName("one of our own guard clauses is a 400 carrying its own message")
        void shouldMapInvalidInputTo400() {
            final ProblemDetail problem = handler.handleInvalidInput(new InvalidInputException("size must be at most 100, was 500"));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            assertThat(problem.getTitle()).isEqualTo("Invalid request");
            assertThat(problem.getDetail()).as("the message is ours by construction, written for a caller")
                    .isEqualTo("size must be at most 100, was 500");
        }

        @Test
        @DisplayName("a rejection is logged at warn, so that nothing at all reaches a caller unlogged")
        void shouldLogARejectionAtWarnRatherThanError() {
            handler.handleInvalidInput(new InvalidInputException("size must be at most 100, was 500"));

            assertThat(emitted.list).hasSize(1);
            assertThat(emitted.list.getFirst().getLevel()).as("a caller sending a bad value is not a fault of ours, but it is still a trace")
                    .isEqualTo(Level.WARN);
            assertThat(emitted.list.getFirst().getThrowableProxy())
                    .as("a caller can provoke this at will, so the trace would be a flood; the message alone is the diagnosis")
                    .isNull();
        }

        @Test
        @DisplayName("a library-thrown IllegalArgumentException is a generic 500, not an echoed 400")
        void shouldNotEchoALibraryThrownIllegalArgument() {
            final ProblemDetail problem = handler.handleUnexpected(new NumberFormatException("For input string: \"/opt/eop/secrets/db.properties\""));

            assertThat(problem.getStatus()).as("a NumberFormatException is an IllegalArgumentException, and is nobody's guard clause")
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
            assertThat(problem.getTitle()).isEqualTo("Internal server error");
            assertThat(problem.getDetail()).isEqualTo("The request could not be completed.");
            assertThat(problem.getDetail()).doesNotContain("secrets");
        }

        @Test
        @DisplayName("a library-thrown IllegalArgumentException is logged at error, which the old mapping silenced")
        void shouldLogALibraryThrownIllegalArgumentAtError() {
            handler.handleUnexpected(new NumberFormatException("For input string: \"nope\""));

            assertThat(emitted.list).hasSize(1);
            assertThat(emitted.list.getFirst().getLevel()).as("this is the log the old 400 mapping robbed us of")
                    .isEqualTo(Level.ERROR);
            assertThat(emitted.list.getFirst().getThrowableProxy()).as("an internal defect is worth a full trace on every occurrence")
                    .isNotNull();
        }
    }

    /**
     * The invariant the narrowing exists to create, rather than a behaviour of any one handler.
     *
     * <p>The defect this test guards was not a wrong status code but a missing rule: mapping an exception type we do
     * not own commits this class to answering for every library that throws it, and to doing so more specifically than
     * the {@code Exception} catch-all, which is what silenced the log. A future handler could reintroduce that by
     * mapping {@code IllegalStateException}, or {@code IllegalArgumentException} again, and every existing test here
     * would still pass. So the rule is asserted directly: a mapping may name only a type this project declares, except
     * the {@code Exception} catch-all itself.</p>
     *
     * <p>The check deliberately reaches past the JDK. {@code IllegalArgumentException} was merely the instance we were
     * bitten by; mapping {@code jakarta.validation.ConstraintViolationException} or a Spring binding exception would
     * echo text we did not write just as readily, and rob the fault of the same log. Naming the packages we do not own
     * states the rule at the width of the defect instead of the width of one example.</p>
     *
     * <p>Scope is this class's own declared methods. Handlers inherited from
     * {@code ResponseEntityExceptionHandler} are Spring's to define, are already narrow, and answer framework faults
     * with field-level detail of their own — they are not the defect, and asserting over them would fail immediately
     * and permanently.</p>
     */
    @Test
    @DisplayName("no handler maps a type this project does not own, except the Exception catch-all itself")
    void shouldMapNoForeignExceptionTypeApartFromTheCatchAll() {
        final List<String> notOurs = List.of("java.", "javax.", "jakarta.", "org.springframework.", "com.fasterxml.",
                "org.hibernate.", "org.slf4j.", "ch.qos.");

        final List<Class<? extends Throwable>> foreignMappings = Arrays.stream(GlobalExceptionHandler.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(ExceptionHandler.class))
                .filter(Objects::nonNull)
                .flatMap(annotation -> Arrays.stream(annotation.value()))
                .filter(mapped -> mapped != Exception.class)
                .filter(mapped -> notOurs.stream().anyMatch(prefix -> mapped.getName().startsWith(prefix)))
                .toList();

        assertThat(foreignMappings)
                .as("a type we do not own is thrown by every library that shares it, so mapping one answers for text we did "
                        + "not write and, being more specific than the Exception catch-all, denies the fault its only error log")
                .isEmpty();
    }

    @Test
    @DisplayName("an unexpected failure is a 500 that reveals nothing about the inside of the system")
    void shouldMapUnexpectedTo500WithoutLeakingDetail() {
        final ProblemDetail problem = handler.handleUnexpected(new IllegalStateException("jdbc:postgresql://10.20.1.7:5432/eop refused"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getTitle()).isEqualTo("Internal server error");
        assertThat(problem.getDetail()).isEqualTo("The request could not be completed.");
        assertThat(problem.getDetail()).doesNotContain("postgresql");
    }

    @Nested
    @DisplayName("mapping a session failure")
    class SessionFailures {

        @Test
        @DisplayName("an unknown session identifier is a 404 naming it, because a UUID is not worth concealing")
        void shouldMapSessionNotFoundTo404() {
            final ProblemDetail problem = handler.handleSessionNotFound(new SessionNotFoundException(SESSION_ID));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
            assertThat(problem.getTitle()).isEqualTo("Session not found");
            assertThat(problem.getDetail()).contains(SESSION_ID.toString());
        }

        @Test
        @DisplayName("an unknown join code is a 404 with a fixed body that names nothing")
        void shouldMapUnknownJoinCodeTo404() {
            final ProblemDetail problem = handler.handleUnknownJoinCode();

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
            assertThat(problem.getTitle()).isEqualTo("No such session");
            assertThat(problem.getDetail()).isEqualTo("No session matches that join code.");
        }

        @Test
        @DisplayName("every unknown join code produces the same body, so the endpoint is no oracle")
        void shouldMapEveryUnknownJoinCodeIdentically() {
            final ProblemDetail mistyped = handler.handleUnknownJoinCode();
            final ProblemDetail neverExisted = handler.handleUnknownJoinCode();

            assertThat(neverExisted.getStatus()).isEqualTo(mistyped.getStatus());
            assertThat(neverExisted.getTitle()).isEqualTo(mistyped.getTitle());
            assertThat(neverExisted.getDetail()).isEqualTo(mistyped.getDetail());
        }

        @Test
        @DisplayName("a session past the lobby is a 409 with a fixed detail — no UUID or status leaked")
        void shouldMapSessionNotJoinableTo409() {
            final ProblemDetail problem =
                    handler.handleSessionNotJoinable(new SessionNotJoinableException(SESSION_ID, SessionStatus.IN_PROGRESS));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
            assertThat(problem.getTitle()).isEqualTo("Session is not in the lobby");
            assertThat(problem.getDetail())
                    .isEqualTo("This session is no longer in the lobby.")
                    .doesNotContain(SESSION_ID.toString())
                    .doesNotContain("IN_PROGRESS");
        }

        @Test
        @DisplayName("a full table is a 409 with a fixed detail — no UUID or capacity leaked")
        void shouldMapSessionFullTo409() {
            final ProblemDetail problem = handler.handleSessionFull(new SessionFullException(SESSION_ID, 6));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
            assertThat(problem.getTitle()).isEqualTo("Session is full");
            assertThat(problem.getDetail())
                    .isEqualTo("This session has no available seats. Try a different join code.")
                    .doesNotContain(SESSION_ID.toString())
                    .doesNotContain("6");
        }

        @Test
        @DisplayName("too few players is a 409, because waiting for another player fixes it")
        void shouldMapTooFewPlayersTo409() {
            final ProblemDetail problem = handler.handleTooFewPlayers(new TooFewPlayersException(SESSION_ID, 2, 3));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
            assertThat(problem.getTitle()).isEqualTo("Not enough players to start");
            assertThat(problem.getDetail()).contains("2 players").contains("at least 3");
        }

        @Test
        @DisplayName("an unrecognised credential is a 403, not a 401, because there is no challenge to offer")
        void shouldMapPlayerNotRecognisedTo403() {
            final ProblemDetail problem = handler.handlePlayerNotRecognised(new PlayerNotRecognisedException(SESSION_ID));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
            assertThat(problem.getTitle()).isEqualTo("Player not recognised");
            assertThat(problem.getDetail()).contains(SESSION_ID.toString());
        }

        @Test
        @DisplayName("an expired session is a 403 Forbidden")
        void shouldMapSessionExpiredTo403() {
            final ProblemDetail problem = handler.handleSessionExpired();

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
            assertThat(problem.getTitle()).isEqualTo("Session expired");
            assertThat(problem.getDetail())
                    .isEqualTo("The session has expired. Please start a new session.");
        }

        @Test
        @DisplayName("a participant trying to start play is a 403")
        void shouldMapNotFacilitatorTo403() {
            final ProblemDetail problem = handler.handleNotFacilitator(new NotFacilitatorException(SESSION_ID, PLAYER_ID));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
            assertThat(problem.getTitle()).isEqualTo("Only the facilitator can start play");
            assertThat(problem.getDetail()).contains(PLAYER_ID.toString());
        }
    }

    @Nested
    @DisplayName("answering a caller who is not in the session")
    class Membership {

        @Test
        @DisplayName("a stranger acting on a session is a 404, not a 403")
        void shouldMapPlayerNotInSessionTo404() {
            final ProblemDetail problem = handler.handlePlayerNotInSession(new PlayerNotInSessionException(SESSION_ID));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
            assertThat(problem.getTitle()).isEqualTo("Session not found");
            assertThat(problem.getDetail()).contains(SESSION_ID.toString());
        }

        @Test
        @DisplayName("the whole body equals the body for a session that does not exist, not merely the status")
        void shouldAnswerAStrangerExactlyAsAMissingSessionIsAnswered() {
            final ProblemDetail missing = handler.handleSessionNotFound(new SessionNotFoundException(SESSION_ID));
            final ProblemDetail stranger = handler.handlePlayerNotInSession(new PlayerNotInSessionException(SESSION_ID));

            assertThat(stranger)
                    .as("two 404s with different titles or details would be as good an oracle as a 403")
                    .isEqualTo(missing);
            assertThat(stranger.getStatus()).isEqualTo(missing.getStatus());
            assertThat(stranger.getTitle()).isEqualTo(missing.getTitle());
            assertThat(stranger.getDetail()).isEqualTo(missing.getDetail());
            assertThat(stranger.getType()).isEqualTo(missing.getType());
            assertThat(stranger.getProperties()).isEqualTo(missing.getProperties());
        }

        @Test
        @DisplayName("the two exceptions carry identical messages, which is what keeps the two details equal")
        void shouldCarryTheSameMessageAsAMissingSession() {
            assertThat(new PlayerNotInSessionException(SESSION_ID).getMessage())
                    .as("both handlers use getMessage() as the detail, so parity is decided here")
                    .isEqualTo(new SessionNotFoundException(SESSION_ID).getMessage());
        }

        @Test
        @DisplayName("no field says why the lookup failed")
        void shouldNameNeitherPlayerNorSeatNorMembership() {
            final ProblemDetail problem = handler.handlePlayerNotInSession(new PlayerNotInSessionException(SESSION_ID));

            assertThat(problem.getTitle() + " " + problem.getDetail())
                    .doesNotContainIgnoringCase("player")
                    .doesNotContainIgnoringCase("seat")
                    .doesNotContainIgnoringCase("member")
                    .doesNotContainIgnoringCase("authoris")
                    .doesNotContainIgnoringCase("forbidden");
        }
    }

    @Nested
    @DisplayName("rate limiting a caller")
    class RateLimiting {

        @Test
        @DisplayName("rate limited is a 429 whose Retry-After states the wait in seconds")
        void shouldMapRateLimitedTo429WithRetryAfter() {
            final ResponseEntity<ProblemDetail> response =
                    handler.handleRateLimited(new RateLimitedException(Duration.ofSeconds(37)));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("37");
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            assertThat(response.getBody().getTitle()).isEqualTo("Too many requests");
            assertThat(response.getBody().getDetail()).contains("retry after 37 seconds");
        }

        @Test
        @DisplayName("Retry-After is a whole number of seconds, because the header admits nothing finer")
        void shouldRoundRetryAfterDownToWholeSeconds() {
            final ResponseEntity<ProblemDetail> response =
                    handler.handleRateLimited(new RateLimitedException(Duration.ofMillis(2500)));

            assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
        }
    }

    @Nested
    @DisplayName("throttling a guesser")
    class Throttling {

        @Test
        @DisplayName("too many join attempts is a 429 whose Retry-After states the wait in seconds")
        void shouldMapTooManyJoinAttemptsTo429WithRetryAfter() {
            final ResponseEntity<ProblemDetail> response =
                    handler.handleTooManyJoinAttempts(new TooManyJoinAttemptsException(Duration.ofSeconds(45)));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("45");
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            assertThat(response.getBody().getTitle()).isEqualTo("Too many join attempts");
            assertThat(response.getBody().getDetail()).contains("retry after 45 seconds");
        }

        @Test
        @DisplayName("Retry-After is a whole number of seconds, because the header admits nothing finer")
        void shouldRoundRetryAfterDownToWholeSeconds() {
            final ResponseEntity<ProblemDetail> response =
                    handler.handleTooManyJoinAttempts(new TooManyJoinAttemptsException(Duration.ofMillis(1500)));

            assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        }
    }

    @Nested
    @DisplayName("capping SSE subscribers")
    class SubscriberCap {

        @Test
        @DisplayName("too many subscribers is a 429 with no Retry-After header")
        void shouldMapTooManySubscribersTo429() {
            final ProblemDetail problem =
                    handler.handleTooManySubscribers(
                            new TooManySubscribersException("session already has 12 subscribers"));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            assertThat(problem.getTitle()).isEqualTo("Too many subscribers");
            assertThat(problem.getDetail()).contains("12 subscribers");
        }
    }

    /**
     * The two exhaustion paths, which used to fall through to {@code handleUnexpected} and answer 500.
     *
     * <p>These tests carry a second obligation beyond the status code. Both acceptance scenarios require that neither
     * exception logs a stack trace at ERROR, because a 500 with a full trace on every occurrence was half the defect:
     * a caller holding a valid join code can provoke seat contention at will, and each occurrence wrote a trace. A
     * status assertion alone would pass even if the handler still logged at ERROR, so these tests attach a Logback
     * appender to this handler's own logger and assert on what was emitted.</p>
     */
    @Nested
    @DisplayName("exhausting a retry budget")
    class Contention {

        private static final int SEAT_ORDER = 4;

        private final Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);

        private final ListAppender<ILoggingEvent> emitted = new ListAppender<>();

        private Level originalLevel;

        @BeforeEach
        void captureLogging() {
            originalLevel = logger.getLevel();
            logger.setLevel(Level.DEBUG);
            emitted.start();
            logger.addAppender(emitted);
        }

        @AfterEach
        void releaseLogging() {
            logger.detachAppender(emitted);
            emitted.stop();
            logger.setLevel(originalLevel);
        }

        @Test
        @DisplayName("a seat contested on every attempt is a 409, because the same request could succeed later")
        void shouldMapSeatAlreadyTakenTo409() {
            final ProblemDetail problem = handler.handleSeatAlreadyTaken(new SeatAlreadyTakenException(SESSION_ID, SEAT_ORDER));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
            assertThat(problem.getTitle()).isEqualTo("The lobby filled while you were joining");
            assertThat(problem.getDetail()).isEqualTo("Another player took the seat on every attempt. Read the session and try again.");
        }

        @Test
        @DisplayName("the 409 names neither the session nor the seat, which the caller never held")
        void shouldNotDiscloseTheContestedSeat() {
            final ProblemDetail problem = handler.handleSeatAlreadyTaken(new SeatAlreadyTakenException(SESSION_ID, SEAT_ORDER));

            assertThat(problem.getDetail()).as("a joining caller supplies only a join code, so the session id is not theirs to learn")
                    .doesNotContain(SESSION_ID.toString())
                    .doesNotContain(Integer.toString(SEAT_ORDER));
        }

        @Test
        @DisplayName("seat contention logs at debug without a trace, so a caller cannot flood the log by provoking it")
        void shouldLogSeatContentionAtDebugWithoutATrace() {
            handler.handleSeatAlreadyTaken(new SeatAlreadyTakenException(SESSION_ID, SEAT_ORDER));

            assertThat(emitted.list).hasSize(1);
            assertThat(emitted.list.getFirst().getLevel()).isEqualTo(Level.DEBUG);
            assertThat(emitted.list.getFirst().getThrowableProxy()).as("the trace is the flood; the message alone is the diagnosis")
                    .isNull();
        }

        @Test
        @DisplayName("an exhausted join-code budget is a 503 whose Retry-After invites the caller back")
        void shouldMapJoinCodeUnavailableTo503WithRetryAfter() {
            final ResponseEntity<ProblemDetail> response = handler.handleJoinCodeUnavailable(new JoinCodeUnavailableException());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
            assertThat(response.getBody().getTitle()).isEqualTo("No lobby could be opened");
            assertThat(response.getBody().getDetail()).isEqualTo("The service could not open a new lobby. Try again in a few seconds.");
        }

        @Test
        @DisplayName("the 503 says nothing about join codes, which are our generator's business and not the caller's")
        void shouldNotDiscloseTheJoinCodeCollision() {
            final ResponseEntity<ProblemDetail> response = handler.handleJoinCodeUnavailable(new JoinCodeUnavailableException());

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getDetail()).doesNotContainIgnoringCase("join code");
        }

        @Test
        @DisplayName("an exhausted join-code budget logs at warn, not error, because nothing malfunctioned")
        void shouldLogJoinCodeExhaustionAtWarnRatherThanError() {
            handler.handleJoinCodeUnavailable(new JoinCodeUnavailableException());

            assertThat(emitted.list).hasSize(1);
            assertThat(emitted.list.getFirst().getLevel()).as("capacity evidence an operator should see, but not a fault")
                    .isEqualTo(Level.WARN);
            assertThat(emitted.list.getFirst().getThrowableProxy())
                    .as("the trace is the point here: this path is not caller-provokable, so each occurrence is worth diagnosing")
                    .isNotNull();
        }

        @Test
        @DisplayName("neither exhaustion path logs at error, which is what made every occurrence write a stack trace")
        void shouldLogNeitherExhaustionPathAtError() {
            handler.handleSeatAlreadyTaken(new SeatAlreadyTakenException(SESSION_ID, SEAT_ORDER));
            handler.handleJoinCodeUnavailable(new JoinCodeUnavailableException());

            assertThat(emitted.list).noneMatch(event -> event.getLevel() == Level.ERROR);
        }
    }

    @Test
    @DisplayName("no refusal echoes a credential, in plaintext or as a digest")
    void shouldNeverLeakACredential() {
        final String plaintext = "grace-plaintext-token";
        final String digest = IdentityTokenHash.of(plaintext).value();
        final ProblemDetail unavailable = requireNonNull(handler.handleJoinCodeUnavailable(new JoinCodeUnavailableException()).getBody());

        final List<String> details = List.of(
                handler.handleSessionNotFound(new SessionNotFoundException(SESSION_ID)).getDetail(),
                handler.handleUnknownJoinCode().getDetail(),
                handler.handleSessionNotJoinable(new SessionNotJoinableException(SESSION_ID, SessionStatus.LOBBY)).getDetail(),
                handler.handleSessionFull(new SessionFullException(SESSION_ID, 6)).getDetail(),
                handler.handleTooFewPlayers(new TooFewPlayersException(SESSION_ID, 2, 3)).getDetail(),
                handler.handlePlayerNotRecognised(new PlayerNotRecognisedException(SESSION_ID)).getDetail(),
                handler.handleNotFacilitator(new NotFacilitatorException(SESSION_ID, PLAYER_ID)).getDetail(),
                handler.handleHandNotDealt(new HandNotDealtException(SESSION_ID)).getDetail(),
                handler.handleHandAlreadyDealt(new HandAlreadyDealtException(SESSION_ID)).getDetail(),
                handler.handleHandComplete(new HandCompleteException(SESSION_ID)).getDetail(),
                handler.handleSeatAlreadyTaken(new SeatAlreadyTakenException(SESSION_ID, 4)).getDetail(),
                handler.handleSessionExpired().getDetail(),
                unavailable.getDetail());

        assertThat(details).noneMatch(detail -> detail.contains(plaintext))
                .noneMatch(detail -> detail.contains(digest))
                .noneMatch(detail -> detail.matches(".*\\b[0-9a-f]{64}\\b.*"));
    }

    @Nested
    @DisplayName("refuses a play without saying more than the refusal needs to")
    class TrickPlayFailures {

        @Test
        @DisplayName("a play claiming a seat the caller does not occupy is 403, and names both seats")
        void shouldMapNotYourSeatToForbidden() {
            final ProblemDetail problem = handler.handleNotYourSeat(new NotYourSeatException(2, 1));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
            assertThat(problem.getDetail()).contains("2").contains("1");
        }

        @Test
        @DisplayName("a play naming someone who does not hold the seat is 403, and discloses neither player")
        void shouldMapPlayerMismatchToForbiddenWithoutNamingEitherPlayer() {
            final UUID occupant = UUID.randomUUID();
            final UUID named = UUID.randomUUID();
            final PlayerMismatchException exception = new PlayerMismatchException(1, occupant, named);

            final ProblemDetail problem = handler.handlePlayerMismatch();

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
            assertThat(problem.getDetail())
                    .doesNotContain(occupant.toString())
                    .doesNotContain(named.toString());
            assertThat(exception.getMessage())
                    .doesNotContain(occupant.toString())
                    .doesNotContain(named.toString());
            assertThat(exception.occupant()).isEqualTo(occupant);
            assertThat(exception.namedPlayer()).isEqualTo(named);
        }

        @Test
        @DisplayName("a play out of turn is 409, and says whose turn it is so no second request is needed")
        void shouldMapOutOfTurnToConflict() {
            final ProblemDetail problem = handler.handleOutOfTurn(new OutOfTurnException(1, 2));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
            assertThat(problem.getDetail()).contains("1");
        }

        @Test
        @DisplayName("a refusal to follow suit is 422, and names both suits because both are face up")
        void shouldMapMustFollowSuitToUnprocessable() {
            final ProblemDetail problem = handler.handleMustFollowSuit(
                    new MustFollowSuitException(StrideCategory.SPOOFING, StrideCategory.TAMPERING));

            assertThat(problem.getStatus()).isEqualTo(422);
            assertThat(problem.getDetail()).contains("SPOOFING").contains("TAMPERING");
        }

        @Test
        @DisplayName("a card the hand does not hold is 422, naming the card and never the hand")
        void shouldMapCardNotInHandToUnprocessableWithoutTheHandIdentifier() {
            final UUID handId = UUID.randomUUID();
            final UUID cardId = UUID.randomUUID();

            final ProblemDetail problem = handler.handleCardNotInHand(new CardNotInHandException(handId, cardId));

            assertThat(problem.getStatus()).isEqualTo(422);
            assertThat(problem.getDetail())
                    .contains(cardId.toString())
                    .doesNotContain(handId.toString());
        }

        @Test
        @DisplayName("a deck holding no tampering card is a server fault, 500, and says nothing about the deck")
        void shouldMapNoTamperingCardDealtToServerError() {
            final ProblemDetail problem =
                    handler.handleNoTamperingCardDealt(new NoTamperingCardDealtException(68));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
            assertThat(problem.getDetail()).isEqualTo("The request could not be completed.");
            assertThat(problem.getDetail()).doesNotContain("68");
        }

        /**
         * Asserts a score that cannot be derived is answered as a server fault that names nobody.
         *
         * <p>Every reason this exception carries means the stored game contradicts itself, so there
         * is nothing a caller can do differently and nothing it is owed beyond the fact that the
         * request failed. The body is the same one a deck holding no tampering card produces, which
         * is deliberate: two different server faults that a caller cannot act on should not be
         * distinguishable from outside. The reason and the identifiers reach the log instead.
         */
        @Test
        @DisplayName("a score that cannot be derived is a server fault, 500, and names no player")
        void shouldMapScoreNotDerivableToServerError() {
            final UUID absent = UUID.randomUUID();

            final ProblemDetail problem =
                    handler.handleScoreNotDerivable(ScoreNotDerivableException.playerNotSeated(absent));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
            assertThat(problem.getTitle()).isEqualTo("Internal server error");
            assertThat(problem.getDetail()).isEqualTo("The request could not be completed.");
            assertThat(problem.getDetail()).doesNotContain(absent.toString());
        }
    }

    @Nested
    @DisplayName("refuses a request that lost a race, without saying who won it")
    class TrickPlayConflicts {

        /** A trick identifier, which is an internal key no response should echo. */
        private static final UUID TRICK_ID = UUID.fromString("00000000-0000-7000-8000-0000000000b2");

        @Test
        @DisplayName("dealing twice is a 409 naming the session the caller supplied")
        void shouldMapHandAlreadyDealtToConflict() {
            final ProblemDetail problem = handler.handleHandAlreadyDealt(new HandAlreadyDealtException(SESSION_ID));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
            assertThat(problem.getTitle()).isEqualTo("Hands already dealt");
            assertThat(problem.getDetail()).contains(SESSION_ID.toString());
        }

        @Test
        @DisplayName("acting before the deal is a 409 naming the session the caller supplied")
        void shouldMapHandNotDealtToConflict() {
            final ProblemDetail problem = handler.handleHandNotDealt(new HandNotDealtException(SESSION_ID));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
            assertThat(problem.getTitle()).isEqualTo("Hands not dealt");
            assertThat(problem.getDetail()).contains(SESSION_ID.toString());
        }

        @Test
        @DisplayName("acting before the deal says nothing but the session, so it cannot report the seat it was refused for")
        void shouldNotLeakAnythingBeyondTheSessionWhenNoHandsAreDealt() {
            final ProblemDetail problem = handler.handleHandNotDealt(new HandNotDealtException(SESSION_ID));

            assertThat(problem.getDetail())
                    .as("the only identifier in the body is the one the caller sent")
                    .isEqualTo("No hands have been dealt in session " + SESSION_ID);
            assertThat(problem.getProperties()).isNull();
        }

        @Test
        @DisplayName("the two states of the deal are told apart, because one is over and the other has not begun")
        void shouldNotCollapseTheTwoDealConflicts() {
            final ProblemDetail alreadyDealt =
                    handler.handleHandAlreadyDealt(new HandAlreadyDealtException(SESSION_ID));
            final ProblemDetail notDealt = handler.handleHandNotDealt(new HandNotDealtException(SESSION_ID));

            assertThat(notDealt.getStatus()).isEqualTo(alreadyDealt.getStatus());
            assertThat(notDealt.getTitle())
                    .as("both are 409s on one column, so the title is the only thing that says which way it went")
                    .isNotEqualTo(alreadyDealt.getTitle());
            assertThat(notDealt.getDetail()).isNotEqualTo(alreadyDealt.getDetail());
        }

        @Test
        @DisplayName("playing after the last card is a 409 naming the session the caller supplied")
        void shouldMapHandCompleteToConflict() {
            final ProblemDetail problem = handler.handleHandComplete(new HandCompleteException(SESSION_ID));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
            assertThat(problem.getTitle()).isEqualTo("Hand complete");
            assertThat(problem.getDetail()).contains(SESSION_ID.toString());
        }

        /**
         * The cards running out is one of the ways play ends, and whether the result is
         * final is EOP-15's to say. A body that mentioned the score here would be a second
         * authority on it, and the one nobody would think to keep up to date.
         */
        @Test
        @DisplayName("a spent hand says the cards are gone and nothing about the score")
        void shouldNotMentionTheScoreWhenTheHandIsSpent() {
            final ProblemDetail problem = handler.handleHandComplete(new HandCompleteException(SESSION_ID));

            assertThat(problem.getDetail())
                    .as("the only identifier in the body is the one the caller sent")
                    .isEqualTo("Every card dealt in session " + SESSION_ID + " has been played");
            assertThat(problem.getTitle()).doesNotContainIgnoringCase("score");
            assertThat(problem.getProperties()).isNull();
        }

        /**
         * Three states of one column, and the third one is the reason
         * {@link HandCompleteException} exists at all: before EOP-14 Slice E a spent hand
         * reached the caller as "hands not dealt", which is false about a hand that was
         * dealt and finished. Two of these titles invite waiting and one never changes
         * back, so a client that cannot tell them apart cannot decide whether to retry.
         */
        @Test
        @DisplayName("the three states of a dealt hand are told apart, not dealt from dealt from played out")
        void shouldNotCollapseTheThreeDealStates() {
            final ProblemDetail notDealt = handler.handleHandNotDealt(new HandNotDealtException(SESSION_ID));
            final ProblemDetail alreadyDealt =
                    handler.handleHandAlreadyDealt(new HandAlreadyDealtException(SESSION_ID));
            final ProblemDetail complete = handler.handleHandComplete(new HandCompleteException(SESSION_ID));

            assertThat(complete.getStatus()).isEqualTo(notDealt.getStatus()).isEqualTo(alreadyDealt.getStatus());
            assertThat(List.of(notDealt.getTitle(), alreadyDealt.getTitle(), complete.getTitle()))
                    .as("three states of one column, three titles")
                    .doesNotHaveDuplicates();
            assertThat(List.of(notDealt.getDetail(), alreadyDealt.getDetail(), complete.getDetail()))
                    .doesNotHaveDuplicates();
        }

        /**
         * A hand with no cards left in it is refused before the hand is asked to resolve a
         * card, so the two never answer the same state. The distinction matters to a
         * client: a 422 says the request could never succeed as written and invites it to
         * name a different card, which is advice it cannot take when it holds none.
         */
        @Test
        @DisplayName("a spent hand is a conflict, not the unprocessable answer an empty hand would otherwise give")
        void shouldNotAnswerASpentHandAsAnUnplayableCard() {
            final ProblemDetail complete = handler.handleHandComplete(new HandCompleteException(SESSION_ID));
            final ProblemDetail notInHand =
                    handler.handleCardNotInHand(
                            new CardNotInHandException(UUID.randomUUID(), UUID.randomUUID()));

            assertThat(complete.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
            assertThat(notInHand.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
            assertThat(complete.getTitle()).isNotEqualTo(notInHand.getTitle());
        }

        @Test
        @DisplayName("resolving before anyone has led is a 409 that echoes no identifier at all")
        void shouldMapNoTrickToResolveToConflictWithoutEchoingTheSession() {
            final ProblemDetail problem = handler.handleNoTrickToResolve();

            assertThat(problem.getStatus())
                    .as("nothing the caller named is missing, so this is a conflict and not a 404")
                    .isEqualTo(HttpStatus.CONFLICT.value());
            assertThat(problem.getTitle()).isEqualTo("No trick to resolve");
            assertThat(problem.getDetail())
                    .as("the caller supplied the session, so repeating it back informs nobody")
                    .doesNotContain(SESSION_ID.toString());
            assertThat(problem.getProperties()).isNull();
        }

        @Test
        @DisplayName("resolving mid-trick is a 409 naming the seat still to play and never the trick")
        void shouldMapTrickNotCompleteToConflictNamingTheSeatOnly() {
            final ProblemDetail problem =
                    handler.handleTrickNotComplete(new TrickNotCompleteException(TRICK_ID, 2));

            assertThat(problem.getStatus())
                    .as("the card is expected, so re-reading fixes it: premature, not impossible")
                    .isEqualTo(HttpStatus.CONFLICT.value());
            assertThat(problem.getTitle()).isEqualTo("Trick not complete");
            assertThat(problem.getDetail())
                    .as("whose turn it is, is public at the table; the trick identifier is not")
                    .contains("2")
                    .doesNotContain(TRICK_ID.toString());
        }

        /**
         * The three ways a resolve request can be too early. All three are 409s on one
         * column, so the title is the only thing that tells a client whether to say
         * "waiting for the deal", "waiting for the lead" or "waiting for a player" —
         * and collapsing any two of them would make a client unable to say which.
         */
        @Test
        @DisplayName("the three too-early states are told apart, deal from lead from outstanding turn")
        void shouldNotCollapseTheThreeTooEarlyStates() {
            final ProblemDetail notDealt = handler.handleHandNotDealt(new HandNotDealtException(SESSION_ID));
            final ProblemDetail noTrick = handler.handleNoTrickToResolve();
            final ProblemDetail notComplete =
                    handler.handleTrickNotComplete(new TrickNotCompleteException(TRICK_ID, 2));

            assertThat(notDealt.getStatus()).isEqualTo(noTrick.getStatus()).isEqualTo(notComplete.getStatus());
            assertThat(List.of(notDealt.getTitle(), noTrick.getTitle(), notComplete.getTitle()))
                    .as("three retryable states, three titles")
                    .doesNotHaveDuplicates();
            assertThat(List.of(notDealt.getDetail(), noTrick.getDetail(), notComplete.getDetail()))
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("losing the race to open a trick is a 409 naming the sequence, because re-reading the session fixes it")
        void shouldMapTrickAlreadyOpenToConflict() {
            final ProblemDetail problem = handler.handleTrickAlreadyOpen(new TrickAlreadyOpenException(SESSION_ID, 3));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
            assertThat(problem.getTitle()).isEqualTo("Trick already open");
            assertThat(problem.getDetail()).contains("3");
        }

        /**
         * A security review found this case answering 500. The winner update is guarded by
         * {@code winner_play_id IS NULL}, and when the seat that led a trick also wins it
         * the leader-seat compare-and-set above becomes idempotent, so a replayed
         * resolution gets past the guard that is supposed to stop it and this statement is
         * the first to notice. That is a conflict the caller can understand and not a
         * fault of ours, so it is a 409.
         */
        @Test
        @DisplayName("a replayed resolution is a 409 naming the trick and never the winning seat")
        void shouldMapTrickAlreadyResolvedToConflictWithoutTheWinningSeat() {
            final ProblemDetail problem =
                    handler.handleTrickAlreadyResolved(new TrickAlreadyResolvedException(TRICK_ID));

            assertThat(problem.getStatus())
                    .as("a replay is a conflict, not the 500 this used to answer")
                    .isEqualTo(HttpStatus.CONFLICT.value());
            assertThat(problem.getTitle()).isEqualTo("Trick already resolved");
            assertThat(problem.getDetail()).contains(TRICK_ID.toString());
        }

        @Test
        @DisplayName("the two conflicts on a trick's lifecycle are told apart, open from resolved")
        void shouldNotCollapseTheOpenConflictIntoTheResolvedConflict() {
            final ProblemDetail openConflict =
                    handler.handleTrickAlreadyOpen(new TrickAlreadyOpenException(SESSION_ID, 3));
            final ProblemDetail resolvedConflict =
                    handler.handleTrickAlreadyResolved(new TrickAlreadyResolvedException(TRICK_ID));

            assertThat(openConflict.getStatus()).isEqualTo(resolvedConflict.getStatus());
            assertThat(openConflict.getTitle())
                    .as("one refuses opening a trick that exists, the other resolving one that is done")
                    .isNotEqualTo(resolvedConflict.getTitle());
            assertThat(openConflict.getDetail()).isNotEqualTo(resolvedConflict.getDetail());
        }

        @Test
        @DisplayName("a second play from one seat is a 409 naming the seat and never the trick")
        void shouldMapAlreadyPlayedInTrickToConflictWithoutTheTrickIdentifier() {
            final ProblemDetail problem =
                    handler.handleAlreadyPlayedInTrick(new AlreadyPlayedInTrickException(TRICK_ID, 2));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
            assertThat(problem.getTitle()).isEqualTo("Already played in this trick");
            assertThat(problem.getDetail()).contains("2").doesNotContain(TRICK_ID.toString());
        }

        @Test
        @DisplayName("a card played twice into one trick is a 409 naming the card and never the trick")
        void shouldMapCardAlreadyPlayedToConflictWithoutTheTrickIdentifier() {
            final UUID cardId = UUID.fromString("00000000-0000-7000-8000-0000000000c3");

            final ProblemDetail problem =
                    handler.handleCardAlreadyPlayed(new CardAlreadyPlayedException(TRICK_ID, cardId));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
            assertThat(problem.getTitle()).isEqualTo("Card already played");
            assertThat(problem.getDetail()).contains(cardId.toString()).doesNotContain(TRICK_ID.toString());
        }

        @Test
        @DisplayName("the two conflicts on a trick are told apart, so a caller learns which of them it hit")
        void shouldNotCollapseTheSeatConflictIntoTheCardConflict() {
            final UUID cardId = UUID.fromString("00000000-0000-7000-8000-0000000000c3");

            final ProblemDetail seatConflict =
                    handler.handleAlreadyPlayedInTrick(new AlreadyPlayedInTrickException(TRICK_ID, 2));
            final ProblemDetail cardConflict =
                    handler.handleCardAlreadyPlayed(new CardAlreadyPlayedException(TRICK_ID, cardId));

            assertThat(seatConflict.getStatus()).isEqualTo(cardConflict.getStatus());
            assertThat(seatConflict.getTitle())
                    .as("one refuses a second play from a seat, the other one card appearing twice")
                    .isNotEqualTo(cardConflict.getTitle());
            assertThat(seatConflict.getDetail()).isNotEqualTo(cardConflict.getDetail());
        }

        @Test
        @DisplayName("a winner from another trick is 422 and not 409, because no later state makes a retry work")
        void shouldMapWinningPlayNotInTrickToUnprocessableWithoutEitherIdentifier() {
            final UUID playId = UUID.fromString("00000000-0000-7000-8000-0000000000d4");

            final ProblemDetail problem =
                    handler.handleWinningPlayNotInTrick(new WinningPlayNotInTrickException(TRICK_ID, playId));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
            assertThat(problem.getStatus()).isNotEqualTo(HttpStatus.CONFLICT.value());
            assertThat(problem.getTitle()).isEqualTo("That play is not in this trick");
            assertThat(problem.getDetail())
                    .isEqualTo("The play named as the winner was not made into the trick being resolved.")
                    .doesNotContain(TRICK_ID.toString())
                    .doesNotContain(playId.toString());
        }

        @Test
        @DisplayName("ending a session that is not in progress is a 409 naming the session and its actual status")
        void shouldMapSessionNotInProgressToConflict() {
            final ProblemDetail problem =
                    handler.handleSessionNotInProgress(
                            new SessionNotInProgressException(SESSION_ID, SessionStatus.COMPLETED));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
            assertThat(problem.getTitle()).isEqualTo("Session is not in progress");
            assertThat(problem.getDetail())
                    .as("the caller supplied the session, so it must appear; the status tells them why it was refused")
                    .contains(SESSION_ID.toString())
                    .containsIgnoringCase("COMPLETED");
        }

        @Test
        @DisplayName("session-not-in-progress is a conflict, not the 404 a missing session would give")
        void shouldNotCollapseSessionNotInProgressIntoNotFound() {
            final ProblemDetail notInProgress =
                    handler.handleSessionNotInProgress(
                            new SessionNotInProgressException(SESSION_ID, SessionStatus.LOBBY));
            final ProblemDetail notFound = handler.handleSessionNotFound(new SessionNotFoundException(SESSION_ID));

            assertThat(notInProgress.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
            assertThat(notFound.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
            assertThat(notInProgress.getTitle()).isNotEqualTo(notFound.getTitle());
        }
    }

    @Nested
    @DisplayName("game-over failures")
    class GameOverFailures {

        @Test
        @DisplayName("requesting the leaderboard or new-game for a non-completed session is a 409")
        void shouldMapGameNotCompletedToConflict() {
            final ProblemDetail problem =
                    handler.handleGameNotCompleted(new GameNotCompletedException(SESSION_ID));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
            assertThat(problem.getTitle()).isEqualTo("Game not completed");
            assertThat(problem.getDetail())
                    .as("the session identifier must appear so the caller can correlate the error")
                    .contains(SESSION_ID.toString());
        }

        @Test
        @DisplayName("game-not-completed is a conflict, not the 404 a missing session would give")
        void shouldNotCollapseGameNotCompletedIntoNotFound() {
            final ProblemDetail notCompleted =
                    handler.handleGameNotCompleted(new GameNotCompletedException(SESSION_ID));
            final ProblemDetail notFound = handler.handleSessionNotFound(new SessionNotFoundException(SESSION_ID));

            assertThat(notCompleted.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
            assertThat(notFound.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
            assertThat(notCompleted.getTitle()).isNotEqualTo(notFound.getTitle());
        }

        @Test
        @DisplayName("a completed session with no recorded result is a 404 of its own")
        void shouldMapGameResultNotRecordedToNotFound() {
            final ProblemDetail problem =
                    handler.handleGameResultNotRecorded(new GameResultNotRecordedException(SESSION_ID));

            assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
            assertThat(problem.getTitle()).isEqualTo("Game result not recorded");
            assertThat(problem.getDetail())
                    .as("the session identifier must appear so the caller can correlate the error")
                    .contains(SESSION_ID.toString());
        }

        @Test
        @DisplayName("an unrecorded result does not claim the session is missing")
        void shouldNotCollapseGameResultNotRecordedIntoSessionNotFound() {
            final ProblemDetail notRecorded =
                    handler.handleGameResultNotRecorded(new GameResultNotRecordedException(SESSION_ID));
            final ProblemDetail notFound = handler.handleSessionNotFound(new SessionNotFoundException(SESSION_ID));

            assertThat(notRecorded.getStatus())
                    .as("the status is deliberately shared so the front end keeps its EOP-82 handling")
                    .isEqualTo(notFound.getStatus());
            assertThat(notRecorded.getTitle())
                    .as("the title is what tells a seated player the two conditions apart")
                    .isNotEqualTo(notFound.getTitle());
            assertThat(notRecorded.getDetail())
                    .as("a seated player must not be told their own session does not exist")
                    .isNotEqualTo(notFound.getDetail())
                    .doesNotContain("No session found");
        }
    }
}
