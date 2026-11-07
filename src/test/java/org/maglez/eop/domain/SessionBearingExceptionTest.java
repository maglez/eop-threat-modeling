package org.maglez.eop.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Covers the identifier accessors on the exception types that carry one.
 *
 * <p>These accessors had no test at all. A quality gate measured the four session-bearing
 * types at 75% instruction coverage and showed the gap was exactly the accessor body, so a
 * mutation replacing {@code return sessionId} with {@code return null} survived the whole
 * suite. That mutation is not cosmetic. The accessor is the only way a caller recovers the
 * identifier from a caught exception, and the {@code GlobalExceptionHandler} builds its
 * problem details from accessors rather than from {@code getMessage()} precisely so it can
 * choose which identifiers to disclose. An accessor returning null would put a literal
 * "null" in a response body, or throw inside the handler and turn a mapped 404 or 409 into
 * a 500.
 *
 * <p>These are innermost-layer types with no framework imports, so this test needs no
 * Spring context and runs in microseconds.
 */
@DisplayName("an exception carrying an identifier")
class SessionBearingExceptionTest {

    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-7000-8000-00000000a5e5");
    private static final UUID TRICK_ID = UUID.fromString("00000000-0000-7000-8000-0000000000b2");

    /**
     * The five types whose only state is the session identifier. Each entry pairs the
     * constructor with the accessor so that adding a sixth such type is one line.
     */
    private static List<Object[]> sessionBearingTypes() {
        return List.of(
                entry(
                        "HandAlreadyDealtException",
                        HandAlreadyDealtException::new,
                        HandAlreadyDealtException::sessionId),
                entry(
                        "HandNotDealtException",
                        HandNotDealtException::new,
                        HandNotDealtException::sessionId),
                entry(
                        "PlayerNotInSessionException",
                        PlayerNotInSessionException::new,
                        PlayerNotInSessionException::sessionId),
                entry(
                        "SessionNotFoundException",
                        SessionNotFoundException::new,
                        SessionNotFoundException::sessionId),
                entry(
                        "NoTrickToResolveException",
                        NoTrickToResolveException::new,
                        NoTrickToResolveException::sessionId),
                entry(
                        "SessionExpiredException",
                        SessionExpiredException::new,
                        SessionExpiredException::sessionId));
    }

    private static <E extends RuntimeException> Object[] entry(
            final String name,
            final Function<UUID, E> constructor,
            final Function<E, UUID> accessor) {
        final Function<UUID, UUID> roundTrip = sessionId -> accessor.apply(constructor.apply(sessionId));
        return new Object[] {name, roundTrip};
    }

    @ParameterizedTest(name = "{0} returns the identifier it was constructed with")
    @MethodSource("sessionBearingTypes")
    @DisplayName("returns the session identifier it was constructed with")
    void shouldReturnTheSessionIdentifier(final String name, final Function<UUID, UUID> roundTrip) {
        final UUID recovered = roundTrip.apply(SESSION_ID);

        assertThat(recovered)
                .as("%s must hand back the identifier a caller needs to act on the failure", name)
                .isEqualTo(SESSION_ID);
    }

    /**
     * Pins the accessor to the constructor argument rather than to a constant. Asserting
     * against one fixed identifier would pass against an accessor hard-coded to return
     * that identifier, so this asserts two distinct instances disagree.
     */
    @ParameterizedTest(name = "{0} does not share state between instances")
    @MethodSource("sessionBearingTypes")
    @DisplayName("keeps each instance's identifier to itself")
    void shouldNotShareStateBetweenInstances(
            final String name, final Function<UUID, UUID> roundTrip) {
        final UUID other = UUID.fromString("00000000-0000-7000-8000-00000000dead");

        assertThat(roundTrip.apply(SESSION_ID))
                .as("%s must not return a constant", name)
                .isNotEqualTo(roundTrip.apply(other));
    }

    @Test
    @DisplayName("names the session in its message, so a log line identifies the session")
    void shouldNameTheSessionInTheMessage() {
        assertThat(new HandAlreadyDealtException(SESSION_ID).getMessage())
                .contains(SESSION_ID.toString());
        assertThat(new HandNotDealtException(SESSION_ID).getMessage())
                .contains(SESSION_ID.toString());
        assertThat(new NoTrickToResolveException(SESSION_ID).getMessage())
                .contains(SESSION_ID.toString());
        assertThat(new SessionExpiredException(SESSION_ID).getMessage())
                .contains(SESSION_ID.toString());
    }

    /**
     * The 404 parity that hides membership behind absence. A stranger holding a guessed
     * identifier must be answered exactly as a caller naming a session that does not
     * exist, and the handler builds both details from {@code getMessage()}, so the parity
     * lives in these two constructors. {@code GlobalExceptionHandlerTest} asserts the two
     * problem details are equal; this asserts the messages they are built from are, which
     * is where a divergence would actually be introduced.
     */
    @Test
    @DisplayName("says nothing a missing session would not say, when the caller is a stranger")
    void shouldAnswerAStrangerAsAMissingSessionWouldAnswer() {
        assertThat(new PlayerNotInSessionException(SESSION_ID).getMessage())
                .as("a different sentence here is as good an oracle for membership as a 403")
                .isEqualTo(new SessionNotFoundException(SESSION_ID).getMessage());
    }

    @Test
    @DisplayName("returns the trick identifier it was constructed with, when it carries one")
    void shouldReturnTheTrickIdentifier() {
        final TrickAlreadyResolvedException exception = new TrickAlreadyResolvedException(TRICK_ID);

        assertThat(exception.trickId()).isEqualTo(TRICK_ID);
        assertThat(exception.getMessage()).contains(TRICK_ID.toString());
    }

    /**
     * The refusal that names a seat as well as a trick. The seat is the one field a client
     * needs in order to say who the table is waiting for, and
     * {@code GlobalExceptionHandler} builds the problem detail from the accessor rather
     * than from {@code getMessage()}, so an accessor returning a stale or constant seat
     * would name the wrong player in a response that looks entirely well formed.
     */
    @Test
    @DisplayName("returns the trick and the seat still to play, and keeps them apart")
    void shouldReturnTheTrickAndTheSeatStillToPlay() {
        final int seatStillToPlay = 2;
        final TrickNotCompleteException exception =
                new TrickNotCompleteException(TRICK_ID, seatStillToPlay);

        assertThat(exception.trickId()).isEqualTo(TRICK_ID);
        assertThat(exception.seatStillToPlay()).isEqualTo(seatStillToPlay);
        assertThat(exception.getMessage()).contains(TRICK_ID.toString());
        assertThat(new TrickNotCompleteException(TRICK_ID, 0).seatStillToPlay())
                .as("the seat must come from the constructor argument, not from a constant")
                .isNotEqualTo(seatStillToPlay);
    }

    /**
     * The session-lifecycle exceptions, which have the same untested-accessor gap the
     * trick-play ones had. They were not named by the gate that found the first four, but
     * they were sitting at 65–75% for exactly the same reason, and the fix is the same fix.
     * Each carries a second field beyond the session identifier, and it is the second field
     * that a handler needs in order to say anything useful — a capacity, a seated count, a
     * status — so an accessor returning a default would produce a refusal that names the
     * wrong number.
     */
    @Test
    @DisplayName("returns every field a session-lifecycle refusal was constructed with")
    void shouldReturnEverySessionLifecycleField() {
        final UUID playerId = UUID.fromString("00000000-0000-7000-8000-0000000000f1");

        final TooFewPlayersException tooFew = new TooFewPlayersException(SESSION_ID, 2, 3);
        assertThat(tooFew.sessionId()).isEqualTo(SESSION_ID);
        assertThat(tooFew.seated()).isEqualTo(2);
        assertThat(tooFew.required()).isEqualTo(3);
        assertThat(tooFew.getMessage()).contains(SESSION_ID.toString());

        final SessionFullException full = new SessionFullException(SESSION_ID, 6);
        assertThat(full.sessionId()).isEqualTo(SESSION_ID);
        assertThat(full.capacity()).isEqualTo(6);
        assertThat(full.getMessage()).contains(SESSION_ID.toString());

        final NotFacilitatorException notFacilitator =
                new NotFacilitatorException(SESSION_ID, playerId);
        assertThat(notFacilitator.sessionId()).isEqualTo(SESSION_ID);
        assertThat(notFacilitator.playerId()).isEqualTo(playerId);

        final SessionNotJoinableException notJoinable =
                new SessionNotJoinableException(SESSION_ID, SessionStatus.IN_PROGRESS);
        assertThat(notJoinable.sessionId()).isEqualTo(SESSION_ID);
        assertThat(notJoinable.status()).isEqualTo(SessionStatus.IN_PROGRESS);
        assertThat(notJoinable.getMessage()).contains(SESSION_ID.toString());

        final PlayerNotRecognisedException notRecognised =
                new PlayerNotRecognisedException(SESSION_ID);
        assertThat(notRecognised.sessionId()).isEqualTo(SESSION_ID);
    }

    /**
     * Pins what each refusal's own message carries, because a message is what the handler
     * returns and what a log line records, and asserting the accessors alone would let one
     * start echoing an identifier with no test noticing.
     *
     * <p>An earlier version of this comment claimed these were "two refusals that must not
     * name a player, because the handler for one of them returns a fixed sentence". Both
     * halves were false, and a code review caught it. Neither
     * {@code handlePlayerNotRecognised} nor {@code handleNotFacilitator} returns a fixed
     * sentence — both pass {@code exception.getMessage()} straight into the problem detail —
     * and the test body only ever asserted one of the two.
     *
     * <p>The truth is an asymmetry worth pinning rather than papering over.
     * {@link PlayerNotRecognisedException} takes only a session identifier, so it *cannot*
     * name a player; that is structural and the assertion below merely records it.
     * {@link NotFacilitatorException} does the opposite: it takes a player identifier and
     * puts it in its message, and the handler returns that message verbatim, so the player
     * identifier reaches the response body. That is deliberate — the caller is the player in
     * question, so it is their own identifier being handed back and no third party learns
     * anything — but it is the sort of deliberate choice that should fail a test if someone
     * changes it by accident, in either direction.
     */
    @Test
    @DisplayName("carries a player identifier in its message only where that is intended")
    void shouldCarryAPlayerIdentifierOnlyWhereIntended() {
        final UUID playerId = UUID.fromString("00000000-0000-7000-8000-0000000000f1");

        assertThat(new PlayerNotRecognisedException(SESSION_ID).getMessage())
                .as("this type takes no player identifier, so it cannot name one")
                .doesNotContain(playerId.toString());

        assertThat(new NotFacilitatorException(SESSION_ID, playerId).getMessage())
                .as("this one does name the requester's own identifier, and the handler returns it")
                .contains(playerId.toString());
    }
}
