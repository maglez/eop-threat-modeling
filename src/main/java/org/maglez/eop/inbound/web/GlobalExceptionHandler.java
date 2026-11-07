package org.maglez.eop.inbound.web;

import org.maglez.eop.domain.AlreadyPlayedInTrickException;
import org.maglez.eop.domain.CardAlreadyPlayedException;
import org.maglez.eop.domain.CardNotFoundException;
import org.maglez.eop.domain.CardNotInHandException;
import org.maglez.eop.domain.GameNotCompletedException;
import org.maglez.eop.domain.HandAlreadyDealtException;
import org.maglez.eop.domain.HandCompleteException;
import org.maglez.eop.domain.HandNotDealtException;
import org.maglez.eop.domain.InvalidInputException;
import org.maglez.eop.domain.JoinCodeUnavailableException;
import org.maglez.eop.domain.MustFollowSuitException;
import org.maglez.eop.domain.NoTamperingCardDealtException;
import org.maglez.eop.domain.NoTrickToResolveException;
import org.maglez.eop.domain.NotFacilitatorException;
import org.maglez.eop.domain.NotYourSeatException;
import org.maglez.eop.domain.OutOfTurnException;
import org.maglez.eop.domain.PlayerMismatchException;
import org.maglez.eop.domain.PlayerNotInSessionException;
import org.maglez.eop.domain.PlayerNotRecognisedException;
import org.maglez.eop.domain.ScoreNotDerivableException;
import org.maglez.eop.domain.SessionExpiredException;
import org.maglez.eop.domain.SeatAlreadyTakenException;
import org.maglez.eop.domain.SessionFullException;
import org.maglez.eop.domain.SessionNotFoundException;
import org.maglez.eop.domain.SessionNotInProgressException;
import org.maglez.eop.domain.SessionNotJoinableException;
import org.maglez.eop.domain.TooFewPlayersException;
import org.maglez.eop.domain.TrickAlreadyOpenException;
import org.maglez.eop.domain.TrickAlreadyResolvedException;
import org.maglez.eop.domain.TrickNotCompleteException;
import org.maglez.eop.domain.UnknownJoinCodeException;
import org.maglez.eop.domain.WinningPlayNotInTrickException;
import org.maglez.eop.application.GameResultNotRecordedException;
import org.maglez.eop.application.RateLimitedException;
import org.maglez.eop.application.TooManyJoinAttemptsException;
import org.maglez.eop.application.TooManySubscribersException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;



/**
 * The application's single error handler.
 *
 * <p>Every 4xx and 5xx response the API produces is rendered here as RFC 9457
 * problem details (ADR-005). There is deliberately one handler and not one per
 * controller: a client should never have to discover that two endpoints report
 * failure in two different shapes.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} inherits Spring's handling
 * of framework-level failures — an unparseable body, an unsupported media type, a
 * path variable that is not a UUID — and those already emit problem details, so
 * they are not re-implemented below.
 *
 * <p>The mapping is deliberately narrow. Domain exceptions carry no HTTP
 * vocabulary, so this class is the only place that decides a status code, and
 * every mapping here has a unit test as the error handling rules require.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * How long a caller is asked to wait after the join-code generator has been
     * exhausted.
     *
     * <p>Five seconds, which is a guess and only has to be a defensible one. The
     * condition it follows is a run of collisions in the code space, so there is no
     * queue draining at a known rate to derive a number from; what matters is that
     * the value is short enough that a facilitator retries rather than gives up, and
     * long enough that a client honouring it does not simply re-run the same
     * exhausted loop immediately.
     */
    private static final int JOIN_CODE_RETRY_AFTER_SECONDS = 5;

    /**
     * Creates the advice Spring registers for the whole application.
     *
     * <p>Written out only so that it can be documented. The handler holds no state: each method
     * below builds a fresh {@link ProblemDetail} from the exception it is handed, so the single
     * instance Spring keeps is safe to share across request threads.
     */
    public GlobalExceptionHandler() {
        // No state: every response is built from the exception passed in.
    }

    /**
     * A named card does not exist.
     *
     * @param exception the domain exception
     * @return a 404 problem detail
     */
    @ExceptionHandler(CardNotFoundException.class)
    public ProblemDetail handleCardNotFound(final CardNotFoundException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Card not found");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /**
     * The request asked for something the domain refuses to represent — an
     * out-of-range page, a display name carrying control characters. The message
     * is safe to return because {@code InvalidInputException} is only ever raised
     * by our own guard clauses, with text written for a caller rather than for a
     * maintainer.
     *
     * <p>This deliberately does not map {@code IllegalArgumentException}, which an
     * earlier revision did. That type is thrown pervasively by the JDK, Hibernate,
     * Jackson and Spring — {@code NumberFormatException} extends it — so mapping it
     * echoed library-internal text to callers, and because the mapping was more
     * specific than the catch-all it also robbed those faults of the only
     * error-level log we have. An internal defect was billed to the caller as a 400
     * and left no record. A library {@code IllegalArgumentException} now falls
     * through to {@code #handleUnexpected}, which answers 500 and logs it.
     *
     * <p>Logged at warn rather than error: a caller sending a bad value is not a
     * fault of ours, but it is still worth a trace, so that nothing at all reaches
     * a caller unlogged. The message is ours by construction, so logging it leaks
     * nothing.
     *
     * @param exception the rejected input
     * @return a 400 problem detail
     */
    @ExceptionHandler(InvalidInputException.class)
    public ProblemDetail handleInvalidInput(final InvalidInputException exception) {
        LOG.warn("Rejected caller input: {}", exception.getMessage());
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Invalid request");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /**
     * A session identifier names no session.
     *
     * <p>Reported plainly, unlike an unknown join code. A session identifier is an
     * unguessable UUID, so concealing whether one exists protects nothing and only
     * makes a genuine bug harder to diagnose.
     *
     * <p>Scoped to existence. The argument above is about whether a session can be
     * found, not about who may see it, and it does not license candour on a
     * caller's membership: an identifier being unguessable says nothing about a
     * stranger who legitimately holds one. Membership is answered by
     * {@link #handlePlayerNotInSession}, whose body this one must match field for
     * field, so that a caller cannot tell a session it may not see from a session
     * that does not exist.
     *
     * @param exception the domain exception
     * @return a 404 problem detail
     */
    @ExceptionHandler(SessionNotFoundException.class)
    public ProblemDetail handleSessionNotFound(final SessionNotFoundException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Session not found");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /**
     * The caller is not a player in the session it is acting on.
     *
     * <p>Answered as a 404 that is indistinguishable from
     * {@link #handleSessionNotFound}, and not as a 403. A 403 would confirm that
     * the session exists, which tells a stranger holding a guessed or leaked
     * identifier that it guessed correctly — the one thing this response must not
     * reveal. A caller that is genuinely a member never reaches here, so nothing
     * legitimate is made harder to diagnose.
     *
     * <p>The parity is field for field and not merely status for status: the title
     * is the same fixed string and the detail is
     * {@link PlayerNotInSessionException}'s message, which that class constructs to
     * be identical to {@link SessionNotFoundException}'s for the same identifier.
     * No field may name a player, a seat, membership or authorisation, or vary with
     * why the lookup failed, because any such difference is the oracle the 404 was
     * chosen to deny. The unit test asserts equality of the two bodies rather than
     * equality of the two statuses, since two 404s with different titles would leak
     * just as effectively (ADR-023).
     *
     * <p>This follows {@link #handleUnknownJoinCode} and its reasoning rather than
     * minting a fresh shape, with one deliberate difference: that handler blanks the
     * identifier because a join code is short enough to be worth guessing at, whereas
     * the session identifier echoed here is a value the caller supplied and cannot
     * learn anything from.
     *
     * @param exception the refusal, carrying the session identifier and nothing else
     * @return a 404 problem detail equal to the one for a session that does not exist
     */
    @ExceptionHandler(PlayerNotInSessionException.class)
    public ProblemDetail handlePlayerNotInSession(final PlayerNotInSessionException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Session not found");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /**
     * A join code matched nothing.
     *
     * <p>The status, title and detail are fixed strings and the exception's own
     * message is deliberately not used, so that no field of the response depends on
     * why the lookup failed. A code that was mistyped, a code that never existed and
     * a code belonging to an abandoned session must be indistinguishable: at forty
     * bits of entropy, an endpoint that confirmed which codes are real would
     * be an oracle worth querying (ADR-019).
     *
     * <p>The bodies are not byte-identical, because Spring fills in {@code instance}
     * with the request URI when a handler leaves it null, and the request URI carries
     * the attempted code. That echoes the caller's own input back at it and so
     * discloses nothing, which is why it is left alone rather than blanked: an empty
     * {@code instance} on this one mapping and a populated one everywhere else would
     * itself be a signal, and it would cost a real diagnostic in the logs of every
     * client that reports problem details verbatim.
     *
     * @return a 404 problem detail whose status, title and detail are the same for
     *         every unusable code
     */
    @ExceptionHandler(UnknownJoinCodeException.class)
    public ProblemDetail handleUnknownJoinCode() {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("No such session");
        problem.setDetail("No session matches that join code.");
        return problem;
    }

    /**
     * The session has left the lobby, so it can be neither joined nor started.
     *
     * <p>The detail is a fixed string and the exception's own message is deliberately
     * not used. The exception carries the session identifier and the current
     * {@link org.maglez.eop.domain.SessionStatus}, but a joining caller supplied only
     * a join code and never held either value, so echoing the message would disclose
     * an internal key and an internal enum name it had no way to know (EOP-55). On
     * non-join paths (start, deal, play, resolve) the caller supplied the session
     * identifier themselves, so the UUID is not a disclosure — but the enum name still
     * is, and a single path-agnostic detail is simpler than branching on call site.
     * The diagnostic is preserved server-side at WARN level.
     *
     * @param exception the domain exception, used only for server-side diagnostics
     * @return a 409 problem detail whose detail is a fixed string
     */
    @ExceptionHandler(SessionNotJoinableException.class)
    public ProblemDetail handleSessionNotJoinable(final SessionNotJoinableException exception) {
        LOG.warn("Session state refused the request: {}", exception.getMessage());
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Session is not in the lobby");
        problem.setDetail("This session is no longer in the lobby.");
        return problem;
    }

    /**
     * The session is not in progress, so it cannot be ended.
     *
     * <p>A conflict rather than a bad request: the request was well formed and the
     * refusal is about the state of the session. A facilitator calling end on a
     * session that has already completed or was never started reaches this.
     *
     * @param exception the domain exception
     * @return a 409 problem detail
     */
    @ExceptionHandler(SessionNotInProgressException.class)
    public ProblemDetail handleSessionNotInProgress(final SessionNotInProgressException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Session is not in progress");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /**
     * Every seat at the table is taken.
     *
     * <p>The detail is a fixed string and the exception's own message is deliberately
     * not used. The exception carries the session identifier and the capacity, but a
     * joining caller supplied only a join code and never held either value, so echoing
     * the message would disclose an internal key and a capacity constant it had no way
     * to know (EOP-54). The diagnostic is preserved server-side at WARN level.
     *
     * @param exception the domain exception, used only for server-side diagnostics
     * @return a 409 problem detail whose detail is a fixed string
     */
    @ExceptionHandler(SessionFullException.class)
    public ProblemDetail handleSessionFull(final SessionFullException exception) {
        LOG.warn("Session state refused the request: {}", exception.getMessage());
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Session is full");
        problem.setDetail("This session has no available seats. Try a different join code.");
        return problem;
    }

    /**
     * The facilitator tried to start play with fewer than three players.
     *
     * <p>A conflict rather than a bad request: the request was well formed and the
     * refusal is about the state of the table, which the caller can change by
     * waiting for another player.
     *
     * @param exception the domain exception
     * @return a 409 problem detail
     */
    @ExceptionHandler(TooFewPlayersException.class)
    public ProblemDetail handleTooFewPlayers(final TooFewPlayersException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Not enough players to start");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /**
     * The request carried no recognisable player credential.
     *
     * <p>Forbidden rather than unauthorised. There is no authentication scheme
     * here — no realm, no challenge, nothing a client could retry differently — and
     * a 401 is required to carry a {@code WWW-Authenticate} header, so emitting one
     * would advertise a challenge that does not exist (ADR-019). The request was
     * understood and refused, which is what 403 means.
     *
     * @param exception the domain exception, which never carries the credential
     * @return a 403 problem detail
     */
    @ExceptionHandler(PlayerNotRecognisedException.class)
    public ProblemDetail handlePlayerNotRecognised(final PlayerNotRecognisedException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Player not recognised");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /**
     * The session the credential belongs to has expired.
     *
     * <p>Returned as 403 Forbidden, consistent with the rest of the API's refusal
     * responses (ADR-015, ADR-036). A 401 would require a {@code WWW-Authenticate}
     * challenge, and emitting one would advertise a scheme this API never reads —
     * the credential travels in a custom header, not {@code Authorization: Bearer}.
     *
     * <p>Distinct from {@link #handlePlayerNotRecognised}: that handler fires when
     * the credential was never valid; this one fires when it was valid but the
     * session it belonged to has since expired. The distinction matters to a client
     * that wants to tell a user "your session timed out" rather than "you are not
     * allowed here".
     *
     * <p>The session identifier is not echoed in the response body. The caller
     * supplied it, so repeating it discloses nothing, but the message in the
     * exception already names it and that message is deliberately not used here:
     * the detail is a fixed string so that every expired-session response is
     * indistinguishable, giving an attacker no signal about which identifiers
     * correspond to sessions that once existed.
     *
     * @return a 403 problem detail
     */
    @ExceptionHandler(SessionExpiredException.class)
    public ProblemDetail handleSessionExpired() {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Session expired");
        problem.setDetail("The session has expired. Please start a new session.");
        return problem;
    }

    /**
     * A seated participant tried to start play.
     *
     * @param exception the domain exception
     * @return a 403 problem detail
     */
    @ExceptionHandler(NotFacilitatorException.class)
    public ProblemDetail handleNotFacilitator(final NotFacilitatorException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Only the facilitator can start play");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /**
     * A play described a seat other than the one the requesting player occupies.
     *
     * <p>Forbidden rather than a bad request, and the distinction is the point. The
     * body was well formed; what was refused was an attempt to act as somebody else,
     * so this is an authorisation failure and it must not be reported as a validation
     * one. An earlier revision of the domain threw {@link IllegalArgumentException}
     * here and so rendered a 400, which told an attacker their request was merely
     * malformed and told the logs the same untruth.
     *
     * <p>The message is returned because it names only two seat numbers, and every
     * player already receives every seat number in the session state. Nothing in it
     * is a secret.
     *
     * @param exception the domain exception, carrying the occupied and claimed seats
     * @return a 403 problem detail
     */
    @ExceptionHandler(NotYourSeatException.class)
    public ProblemDetail handleNotYourSeat(final NotYourSeatException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("That is not your seat");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /**
     * A play named a player other than the one occupying the seat it was played from.
     *
     * <p>The sibling of {@link #handleNotYourSeat}, and forbidden for the same
     * reason. In ordinary play it is unreachable, because the seat is settled from
     * the credential before this is checked; it fires when a request names somebody
     * else, or when hands have been filed against the wrong seats, and in the second
     * case it is the difference between a loud refusal and two players quietly
     * playing each other's cards.
     *
     * <p>The detail is fixed and the exception's message deliberately unused. That
     * message names both player identifiers, which is what makes it worth logging and
     * exactly what makes it unfit to return; the identifiers stay reachable through
     * the exception's accessors for whoever handles the incident.
     *
     * @return a 403 problem detail that names no player
     */
    @ExceptionHandler(PlayerMismatchException.class)
    public ProblemDetail handlePlayerMismatch() {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("That play does not belong to you");
        problem.setDetail("The play does not match the requesting player's identity.");
        return problem;
    }

    /**
     * A player played out of turn.
     *
     * <p>A conflict rather than a bad request: the request was well formed and the
     * refusal is about the state of the trick, which the caller fixes by waiting.
     * The message names the seat that is to play, which saves the client a second
     * request and discloses nothing — whose turn it is, is public at the table.
     *
     * @param exception the domain exception, carrying the expected and attempted seats
     * @return a 409 problem detail
     */
    @ExceptionHandler(OutOfTurnException.class)
    public ProblemDetail handleOutOfTurn(final OutOfTurnException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Not your turn");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /**
     * A player holding the led suit played a different one.
     *
     * <p>Unprocessable rather than a bad request: every field parsed and every
     * identifier resolved, and the request was refused by a rule of the game. The
     * message names the led suit and the suit attempted, both of which are face up
     * the moment the card is played.
     *
     * @param exception the domain exception, carrying the led and attempted suits
     * @return a 422 problem detail
     */
    @ExceptionHandler(MustFollowSuitException.class)
    public ProblemDetail handleMustFollowSuit(final MustFollowSuitException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setTitle("You must follow suit");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /**
     * A play named a card the hand does not hold.
     *
     * <p>The detail is built from the card identifier alone rather than from the
     * exception's message, which also names the hand. A hand identifier is an
     * internal key the caller has no use for; echoing it would widen the response
     * for no benefit to whoever reads it.
     *
     * <p>This covers a card played twice, a card that belongs to somebody else, and
     * a request that named no card at all — the domain answers all three the same
     * way, so that a missing card field is a client error rather than a server one.
     *
     * @param exception the domain exception, carrying the hand and the card
     * @return a 422 problem detail naming only the card
     */
    @ExceptionHandler(CardNotInHandException.class)
    public ProblemDetail handleCardNotInHand(final CardNotInHandException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setTitle("Card not in hand");
        problem.setDetail("Your hand does not hold card " + exception.cardId() + ".");
        return problem;
    }

    /**
     * Hands have already been dealt in this session.
     *
     * <p>A conflict rather than a rejected argument: the request was well formed and
     * would have succeeded a moment earlier. Two facilitators dealing at once is the
     * ordinary way to reach this, and the loser is told the state of the world
     * changed rather than that it asked for something impossible.
     *
     * <p>The detail names the session, which the caller supplied, and nothing about
     * who dealt or when.
     *
     * @param exception the refusal, carrying the session identifier
     * @return a 409 problem detail
     */
    @ExceptionHandler(HandAlreadyDealtException.class)
    public ProblemDetail handleHandAlreadyDealt(final HandAlreadyDealtException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Hands already dealt");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /**
     * No hands have been dealt in this session yet.
     *
     * <p>The mirror of the refusal above, and a 409 for the same reason: the request
     * was well formed and the state is simply earlier than the caller believed. Asking
     * to open the first trick before dealing reaches this.
     *
     * <p>It has its own type and title rather than borrowing
     * {@code SessionNotJoinableException}, which is what it answered before EOP-14
     * Slice C1's architecture review. That answer carried the right status with an
     * explanation that contradicted itself — a session reported as not joinable, with
     * the status saying it was in progress — because nothing in the vocabulary could
     * name the state.
     *
     * <p>The detail names the session, which the caller supplied, and nothing about how
     * far along the session is otherwise.
     *
     * @param exception the refusal, carrying the session identifier
     * @return a 409 problem detail
     */
    @ExceptionHandler(HandNotDealtException.class)
    public ProblemDetail handleHandNotDealt(final HandNotDealtException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Hands not dealt");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /**
     * Every card dealt in this session has been played.
     *
     * <p>The third state of a dealt hand, after the two above: not dealt, dealt and in
     * progress, dealt and played out. A 409 for the same reason as its siblings — the
     * request was well formed and would have succeeded earlier in the hand — and it
     * needs its own title because the caller's next move is different. "Hands not
     * dealt" invites waiting; this one never changes back, and a client shown it
     * should stop asking to play and go and look at the score.
     *
     * <p>Like {@link #handleHandNotDealt} it exists because the honest answer was
     * otherwise unavailable. Without it the same state reaches the caller as
     * {@link #handleCardNotInHand}, a 422 saying the card named was wrong when no card
     * is left to name, or as "hands not dealt", which is false about a hand that was
     * dealt and finished.
     *
     * <p>The detail names the session, which the caller supplied, and says nothing
     * about the score. That the cards have run out is one of the ways play ends; whether
     * the result is final is not this response's to state.
     *
     * @param exception the refusal, carrying the session identifier
     * @return a 409 problem detail
     */
    @ExceptionHandler(HandCompleteException.class)
    public ProblemDetail handleHandComplete(final HandCompleteException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Hand complete");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /**
     * The session has no trick to resolve.
     *
     * <p>The second of three states a resolve request can be too early for, and the
     * middle one: {@link #handleHandNotDealt} is before the deal, this is after the
     * deal but before the first card, and {@link #handleTrickNotComplete} is after
     * the first card but before the last. Three states, three titles, because a
     * client that shows "waiting for the deal" rather than "waiting for the lead"
     * cannot tell them apart from one shared answer.
     *
     * <p>A 409 and pointedly not a 404. Nothing the caller named is missing — the
     * session exists and the caller is in it, which is how the request got this far.
     * A 404 would tell an honest client its session identifier was wrong, which is
     * the one thing it can be sure it is not, and would send it to re-join a table
     * it is already seated at.
     *
     * <p>The detail does not echo the session identifier. The caller supplied it, so
     * repeating it discloses nothing and informs nothing; what the caller needs to
     * know is that the answer will change once somebody leads.
     *
     * <p>Takes no argument, like {@link #handleUnknownJoinCode}, because there is
     * nothing in the refusal this response has any business echoing: the session
     * identifier came from the caller, and accepting a parameter it does not read
     * would invite a later edit to start reading it.
     *
     * @return a 409 problem detail
     */
    @ExceptionHandler(NoTrickToResolveException.class)
    public ProblemDetail handleNoTrickToResolve() {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("No trick to resolve");
        problem.setDetail("No trick has been led in this session yet.");
        return problem;
    }

    /**
     * The trick is not complete, so it cannot be resolved yet.
     *
     * <p>A 409 and not a 422. The request is premature, not impossible: the missing
     * card is expected, and re-reading once it arrives is exactly what the caller
     * should do. A 422 would say the request could never succeed and would push a
     * client into treating an ordinary wait as a defect.
     *
     * <p>Distinct from {@link #handleOutOfTurn} despite both naming a seat. That one
     * refuses a caller trying to <em>play</em> when it is not their turn; this one
     * refuses a caller trying to <em>resolve</em> while a turn is still outstanding,
     * and the caller here has done nothing wrong.
     *
     * <p>The detail names the seat still to play and nothing else. Whose turn it is
     * is public at the table — every player watches it — so it is not a leak, and it
     * is the one fact that lets a client say who it is waiting for. Nothing about any
     * card is carried, because the cards already played are the read model's to give
     * and the cards still held are nobody else's business.
     *
     * <p>Completeness is measured against the seats that still hold cards rather
     * than the number of players, per ADR-023: the last trick of a hand is short, so
     * counting players would leave it permanently unresolvable.
     *
     * @param exception the refusal, carrying the trick and the seat still to play
     * @return a 409 problem detail
     */
    @ExceptionHandler(TrickNotCompleteException.class)
    public ProblemDetail handleTrickNotComplete(final TrickNotCompleteException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Trick not complete");
        problem.setDetail("The trick is still waiting on seat " + exception.seatStillToPlay() + ".");
        return problem;
    }

    /**
     * A trick with this sequence number is already open in the session.
     *
     * <p>Reached when two requests race to open the next trick: both compute the
     * same sequence number and the loser collides. A 409 and not a 422, because a
     * different state resolves it — the winner's trick is now the open one, and
     * re-reading the session shows a trick to play into.
     *
     * <p>Distinct from {@link #handleAlreadyPlayedInTrick} even though both are
     * conflicts on the same table: no play is involved here, and telling the two
     * apart is the difference between somebody else having opened the trick and the
     * caller having already played into it.
     *
     * @param exception the refusal, carrying the session and the sequence number
     * @return a 409 problem detail
     */
    @ExceptionHandler(TrickAlreadyOpenException.class)
    public ProblemDetail handleTrickAlreadyOpen(final TrickAlreadyOpenException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Trick already open");
        problem.setDetail("Trick " + exception.sequence() + " is already open in this session.");
        return problem;
    }

    /**
     * This trick already has a winner recorded.
     *
     * <p>A 409 rather than a 500. The path is a replayed resolution: recording a
     * resolution advances the session's leader seat and then stamps the winner, and
     * when the seat that led the trick also won it the leader-seat update is
     * idempotent, so a repeat gets past it and the winner update is the first
     * statement to notice the work is done. Nothing is corrupt and nothing was
     * written twice, so billing the caller for a server fault was wrong; a security
     * review of EOP-14 Slice C1 found the adapter doing exactly that.
     *
     * <p>The detail names the trick and not the winner. A caller resolving a trick
     * supplied the trick identifier itself, so naming it discloses nothing, whereas
     * the seat that won is the answer to the question the caller was asking and is
     * for the read model to give once, not for a refusal to leak.
     *
     * @param exception the refusal, carrying the trick
     * @return a 409 problem detail
     */
    @ExceptionHandler(TrickAlreadyResolvedException.class)
    public ProblemDetail handleTrickAlreadyResolved(final TrickAlreadyResolvedException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Trick already resolved");
        problem.setDetail("Trick " + exception.trickId() + " already has a winner.");
        return problem;
    }

    /**
     * A seat has already played into this trick.
     *
     * <p>A 409, like {@link #handleOutOfTurn}, but for a different reason and with a
     * different title — an earlier version of this sentence claimed that one returned a
     * 422, which it never did. This is not a turn-order mistake: the seat's play is
     * already recorded, so the request lost a race with an identical one rather than
     * arriving in the wrong order. A retry cannot succeed, but re-reading the trick
     * shows the play the caller was trying to make.
     *
     * <p>The detail names the seat and not the trick. A seat number is something
     * every player at the table can already see; the trick identifier is an internal
     * key that would widen the response without helping whoever reads it, on the
     * same reasoning as {@link #handleCardNotInHand}.
     *
     * @param exception the refusal, carrying the trick and the seat
     * @return a 409 problem detail naming only the seat
     */
    @ExceptionHandler(AlreadyPlayedInTrickException.class)
    public ProblemDetail handleAlreadyPlayedInTrick(final AlreadyPlayedInTrickException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Already played in this trick");
        problem.setDetail("Seat " + exception.seatOrder() + " has already played into this trick.");
        return problem;
    }

    /**
     * The card named has already been played into this trick.
     *
     * <p>Separate from {@link #handleAlreadyPlayedInTrick} because the two answer
     * different questions: that one refuses a second play from one seat, this one
     * refuses one card appearing twice in a trick whichever seat played it.
     * Collapsing them would tell a caller that something conflicted without saying
     * whether its own earlier play or somebody else's is the reason.
     *
     * <p>The card identifier is echoed because the caller supplied it.
     *
     * @param exception the refusal, carrying the trick and the card
     * @return a 409 problem detail naming only the card
     */
    @ExceptionHandler(CardAlreadyPlayedException.class)
    public ProblemDetail handleCardAlreadyPlayed(final CardAlreadyPlayedException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Card already played");
        problem.setDetail("Card " + exception.cardId() + " has already been played into this trick.");
        return problem;
    }

    /**
     * The play named as the winner was not made into the trick being resolved.
     *
     * <p>A 422 and deliberately not a 409. A conflict entitles the caller to retry
     * once the state has moved on, and no state this application can reach makes a
     * play from one trick the winner of another — so a status that invites a retry
     * would be an invitation to a request that can never succeed.
     *
     * <p>Logged at warning level with both identifiers, because there is no storage
     * constraint behind this check and there is not going to be one: the composite
     * key that would provide it collides with the rule that lets a winning play be
     * deleted (ADR-023). This handler firing is therefore the only evidence that
     * will ever exist, and it means either a defect in resolution or a request that
     * reached the use case naming a play it had no business naming.
     *
     * <p>The response carries neither identifier. The caller cannot act on them, and
     * a trick or play identifier belonging to somebody else's session is exactly the
     * sort of internal key that should not be echoed back to whoever guessed it.
     *
     * @param exception the refusal, carrying the trick and the play
     * @return a 422 problem detail whose detail is a fixed string
     */
    @ExceptionHandler(WinningPlayNotInTrickException.class)
    public ProblemDetail handleWinningPlayNotInTrick(final WinningPlayNotInTrickException exception) {
        LOG.warn("Play {} was named as the winner of trick {}, which it was not played into",
                exception.playId(), exception.trickId(), exception);
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setTitle("That play is not in this trick");
        problem.setDetail("The play named as the winner was not made into the trick being resolved.");
        return problem;
    }

    /**
     * No Tampering card was dealt, so the opening leader could not be derived.
     *
     * <p>A server fault, not a client one, and the only mapping here that says so.
     * The deck is seeded reference data and every valid deck contains the suit, so
     * reaching this means the deck the application dealt from was not the deck it
     * ships with. The count is logged because it is the one number that identifies
     * which deck was dealt, and withheld from the response because a caller can do
     * nothing with it.
     *
     * @param exception the domain exception, carrying how many cards were dealt
     * @return a 500 problem detail with no internal information
     */
    @ExceptionHandler(NoTamperingCardDealtException.class)
    public ProblemDetail handleNoTamperingCardDealt(final NoTamperingCardDealtException exception) {
        LOG.error("No tampering card among the {} cards dealt, so no opening leader could be derived",
                exception.cardsDealt(), exception);
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle("Internal server error");
        problem.setDetail("The request could not be completed.");
        return problem;
    }

    /**
     * A rate limit was exceeded (e.g. too many session creations from one address).
     *
     * <p>Returned as a {@link ResponseEntity} rather than a bare problem detail so
     * it can carry {@code Retry-After}. The title and detail are neutral: they name
     * the rate-limit control without disclosing which specific operation was throttled
     * (ADR-033).
     *
     * @param exception the refusal, carrying how long to wait
     * @return a 429 problem detail with a {@code Retry-After} header
     */
    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<ProblemDetail> handleRateLimited(final RateLimitedException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        problem.setTitle("Too many requests");
        problem.setDetail(exception.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfter().toSeconds()))
                .body(problem);
    }

    /**
     * Too many failed join attempts from one source.
     *
     * <p>Returned as a {@link ResponseEntity} rather than a bare problem detail so
     * it can carry {@code Retry-After}. This is not politeness about load: the length
     * of a join code bounds a blind search but does not stop one, so this response is
     * a security control rather than a courtesy (ADR-019).
     *
     * @param exception the refusal, carrying how long to wait
     * @return a 429 problem detail with a {@code Retry-After} header
     */
    @ExceptionHandler(TooManyJoinAttemptsException.class)
    public ResponseEntity<ProblemDetail> handleTooManyJoinAttempts(final TooManyJoinAttemptsException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        problem.setTitle("Too many join attempts");
        problem.setDetail(exception.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfter().toSeconds()))
                .body(problem);
    }

    /**
     * Too many SSE subscribers for a session or globally.
     *
     * <p>Returned as a bare problem detail (no {@code Retry-After}) because
     * reconnection is not time-bounded: a subscriber slot frees up whenever a
     * connection closes, which is at the client's discretion rather than after a
     * fixed wait (EOP-20, ADR-034).
     *
     * @param exception the refusal, describing which cap was reached
     * @return a 429 problem detail
     */
    @ExceptionHandler(TooManySubscribersException.class)
    public ProblemDetail handleTooManySubscribers(final TooManySubscribersException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        problem.setTitle("Too many subscribers");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /**
     * Every attempt to claim a seat lost its race.
     *
     * <p>A 409 and not a 500. {@link org.maglez.eop.application.port.in.JoinSessionPort}
     * retries a contested seat up to its attempt budget and only rethrows when every
     * attempt was beaten to the row, so reaching here means the lobby was being
     * filled by other callers throughout — a conflict with the state of the session,
     * and one the same request could succeed at if a seat frees up. It belongs beside
     * {@link #handleSessionFull} rather than among the server faults: both say the
     * seats ran out, and they differ only in whether the domain saw the lobby full
     * before the write or the unique constraint said so during it.
     *
     * <p>Reaching here at all takes concurrency, because the attempt budget is larger
     * than the table: the use case allows two more attempts than a lobby has seats, so
     * a joiner losing races at an otherwise quiet lobby runs out of seats before it
     * runs out of attempts and leaves through {@link #handleSessionFull} instead. What
     * defeats that arithmetic is a stale read — the domain fullness check runs against
     * the snapshot fetched at the top of each attempt, so simultaneous joins can each
     * pass a check that was already out of date and lose on the constraint. The two
     * accounts are consistent rather than contradictory: unreachable by arithmetic at
     * a quiet lobby, and provokable on purpose under load.
     *
     * <p>Logged at debug level and without the throwable. A caller holding a valid
     * join code can provoke this at will by firing concurrent joins at one lobby, so
     * a warning with a stack trace per occurrence would hand that caller a
     * log-flooding amplifier — which is the defect this mapping exists to remove,
     * not something to relocate to a lower level. A stack trace of a retry loop that
     * behaved exactly as designed describes nothing a handler needs.
     *
     * <p>The response names neither the session nor the seat. The exception message
     * carries both, but a joining caller supplied only a join code and never held the
     * session identifier, so echoing the message would disclose an internal key it
     * had no way to know.
     *
     * @param exception the refusal, carrying the session and the seat that was lost
     * @return a 409 problem detail whose detail is a fixed string
     */
    @ExceptionHandler(SeatAlreadyTakenException.class)
    public ProblemDetail handleSeatAlreadyTaken(final SeatAlreadyTakenException exception) {
        LOG.debug("Seat {} in session {} was contested on every attempt", exception.seatOrder(), exception.sessionId());
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("The lobby filled while you were joining");
        problem.setDetail("Another player took the seat on every attempt. Read the session and try again.");
        return problem;
    }

    /**
     * No free join code could be minted within the attempt budget.
     *
     * <p>A 503 and not a 500. {@link org.maglez.eop.application.port.in.CreateSessionPort}
     * asks the generator for a code and only rethrows after every attempt in its
     * budget collided with a lobby that already holds one, which is a statement about
     * how much of the code space is presently occupied rather than about anything
     * being broken: the same request, sent later or once a lobby has closed, succeeds.
     * That is a capacity condition, so this is the one status that both says so and
     * carries the standard way of saying when to come back.
     *
     * <p>Logged at warning level, with the throwable, because unlike a contested seat
     * this is not something a caller can provoke at will. Two independent things hold
     * that, and it is worth being clear which one is load-bearing. The binding one is
     * a control: every request reaching the retry loop has already passed
     * {@link org.maglez.eop.application.port.out.SessionCreationLimiter}, which admits a configured
     * number of creations per client address per minute and refuses an unrecognised
     * address outright once its tracked-key table is full, so the rate at which this
     * handler can be driven is bounded by that allowance and that ceiling rather than
     * by how many addresses a caller commands. The other is arithmetic: a run of
     * independent collisions across the whole attempt budget stays vanishingly
     * unlikely while the occupied fraction of the forty-bit code space is small. The
     * arithmetic is why an occurrence is worth reading; the limiter is why logging one
     * with a trace cannot become a flood, and it is the half that survives a change to
     * the attempt budget, the alphabet or the code length. So each occurrence is still
     * real evidence that the number of live lobbies is approaching what the code space
     * will bear, and this handler is the only place that evidence will appear. It is
     * deliberately not an error — the request was refused correctly and nothing
     * malfunctioned — so it no longer reaches the catch-all that logs at error level.
     *
     * <p>The response says nothing about join codes. The exception's own message
     * describes our generator colliding with itself, which is an internal detail, and
     * the request named no code to be told about.
     *
     * @param exception the refusal from the last attempt in the budget
     * @return a 503 problem detail with a {@code Retry-After} header
     */
    @ExceptionHandler(JoinCodeUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleJoinCodeUnavailable(final JoinCodeUnavailableException exception) {
        LOG.warn("No free join code was available within the attempt budget", exception);
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setTitle("No lobby could be opened");
        problem.setDetail("The service could not open a new lobby. Try again in a few seconds.");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, Integer.toString(JOIN_CODE_RETRY_AFTER_SECONDS))
                .body(problem);
    }

    /**
     * Answers a score that cannot be derived with a 500.
     *
     * <p>A server fault, not a client one, and the second mapping here to say so. Every refusal this
     * exception carries means the stored game contradicts itself — a play attributed to a player who
     * holds no seat, two tricks claiming one place in the order, a player seated twice. None of them
     * is something a caller can reword a request to avoid, so none of them is a 4xx.
     *
     * <p>The reason is logged and withheld from the response, and so are the identifiers the message
     * names. A caller can do nothing with them, and a play or trick identifier belonging to a session
     * they may not even be able to see is exactly the sort of internal key that should not be echoed
     * back to whoever asked. The body is the same one every other server fault gets, so this one is
     * indistinguishable from the rest (ADR-031).
     *
     * @param exception the exception raised while deriving the score
     * @return a problem detail describing an internal error
     */
    @ExceptionHandler(ScoreNotDerivableException.class)
    public ProblemDetail handleScoreNotDerivable(final ScoreNotDerivableException exception) {
        LOG.error("A score could not be derived: {}", exception.reason(), exception);
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle("Internal server error");
        problem.setDetail("The request could not be completed.");
        return problem;
    }

    /**
     * The session is not yet completed — the leaderboard or new-game action was requested
     * before the last trick was resolved.
     *
     * @param exception the exception carrying the session identifier
     * @return a 409 problem detail
     */
    @ExceptionHandler(GameNotCompletedException.class)
    public ProblemDetail handleGameNotCompleted(
            final GameNotCompletedException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Game not completed");
        problem.setDetail("Session " + exception.sessionId() + " is not yet completed.");
        return problem;
    }

    /**
     * The session is completed but no result was ever recorded for it.
     *
     * <p>A 404, the same status a genuinely absent session gets, and deliberately so. The front end
     * stopped treating a leaderboard 404 as grounds for ejecting the player in EOP-82 and now ejects
     * only on 403 (ADR-042), so moving this to another status would invalidate that handling for no
     * gain. What changes is the title and the detail: this response no longer claims the session does
     * not exist, which was untrue and was read by a seated player standing next to a retry button.
     *
     * <p>The detail says what happened without promising it will resolve. Three causes reach here and
     * only one is transient — a read that landed between a session being marked completed and its
     * result being written. The other two are permanent: a facilitator who ended the session early,
     * which by design records no result, and a best-effort result write that failed and is not
     * retried. The wording therefore reports the state rather than inviting a retry, even though the
     * client still offers one.
     *
     * <p>Echoing the session identifier is safe and matches every other handler in this class. The
     * caller has already been proved a member of that session before this exception can be raised: a
     * stranger is stopped earlier with {@code PlayerNotRecognisedException} and gets a 403. So the
     * response discloses nothing the caller did not supply.
     *
     * @param exception the exception carrying the session identifier
     * @return a 404 problem detail distinguishing an unrecorded result from an absent session
     */
    @ExceptionHandler(GameResultNotRecordedException.class)
    public ProblemDetail handleGameResultNotRecorded(
            final GameResultNotRecordedException exception) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Game result not recorded");
        problem.setDetail("Session " + exception.sessionId()
                + " is completed but no result was recorded for it.");
        return problem;
    }

    /**
     * Anything unanticipated.
     *
     * <p>The cause is logged and deliberately not returned. An unexpected failure
     * is the one case where the message is most likely to describe internals, and
     * the application is deployed on a public address with no authentication, so
     * the response says nothing a caller could use to map the inside of the
     * system.
     *
     * @param exception the unexpected failure
     * @return a 500 problem detail with no internal information
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(final Exception exception) {
        LOG.error("Unhandled exception serving a request", exception);
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle("Internal server error");
        problem.setDetail("The request could not be completed.");
        return problem;
    }
}
