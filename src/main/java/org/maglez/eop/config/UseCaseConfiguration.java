package org.maglez.eop.config;

import java.time.Clock;
import java.util.Optional;
import org.maglez.eop.usecase.CardRepository;
import org.maglez.eop.usecase.CreateSessionUseCase;
import org.maglez.eop.usecase.DealHandsUseCase;
import org.maglez.eop.usecase.DeckShuffler;
import org.maglez.eop.usecase.EndSessionUseCase;
import org.maglez.eop.usecase.GameResultRepository;
import org.maglez.eop.usecase.GetCardUseCase;
import org.maglez.eop.usecase.GetLeaderboardUseCase;
import org.maglez.eop.usecase.GetScoreUseCase;
import org.maglez.eop.usecase.GetSessionStateUseCase;
import org.maglez.eop.usecase.GetTrickStateUseCase;
import org.maglez.eop.usecase.HandDealer;
import org.maglez.eop.usecase.HandRepository;
import org.maglez.eop.usecase.IdentifierGenerator;
import org.maglez.eop.usecase.IdentityTokenGenerator;
import org.maglez.eop.usecase.JoinAttemptLimiter;
import org.maglez.eop.usecase.JoinCodeGenerator;
import org.maglez.eop.usecase.JoinSessionUseCase;
import org.maglez.eop.usecase.ListCardsUseCase;
import org.maglez.eop.usecase.NewGameUseCase;
import org.maglez.eop.usecase.PersistGameResultUseCase;
import org.maglez.eop.usecase.PlayCardUseCase;
import org.maglez.eop.usecase.ReadOwnHandUseCase;
import org.maglez.eop.usecase.ResolvePlayerUseCase;
import org.maglez.eop.usecase.ResolveTrickUseCase;
import org.maglez.eop.usecase.SessionEventPublisher;
import org.maglez.eop.usecase.SessionRepository;
import org.maglez.eop.usecase.StartSessionUseCase;
import org.maglez.eop.usecase.SweepExpiredSessionsUseCase;
import org.maglez.eop.usecase.TrickJournal;
import org.maglez.eop.usecase.TrickRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the use case layer into the Spring context.
 *
 * <p>This class exists so that the use cases do not have to. Annotating them with
 * {@code @Service} would be simpler by one file and would put a Spring import
 * into the layer that AGENTS.md requires to have none, which is the whole point
 * of the boundary: the use cases can be constructed and tested with {@code new},
 * and the container is a delivery detail declared out here.
 */
@Configuration
public class UseCaseConfiguration {

    /**
     * Creates the configuration class Spring instantiates reflectively while building the context.
     *
     * <p>Written out rather than left implicit only so that it can be documented. The class holds no
     * state of its own: every use case it declares is built inside a {@code @Bean} method from the
     * ports Spring hands in, so there is nothing for a constructor to initialise.
     */
    public UseCaseConfiguration() {
        // No state: the beans below are assembled from injected ports.
    }

    /**
     * Declares the clock the session use cases read the current instant from.
     *
     * <p>A clock is a dependency rather than a call to {@code Instant.now()} so that
     * a test can decide what time it is. Every timestamp this story writes — when a
     * lobby opened, when a player took a seat, when play began — comes from here, so
     * a fixed clock makes those assertions exact instead of approximate.
     *
     * <p>UTC, not the system zone: the database columns are {@code TIMESTAMP WITH TIME
     * ZONE} and the deployment target's zone is not a property anything should depend on.
     *
     * @return a UTC clock reading the system time
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Declares the list-cards use case.
     *
     * @param cardRepository the port implementation supplied by the persistence adapter
     * @return the list-cards use case
     */
    @Bean
    public ListCardsUseCase listCardsUseCase(final CardRepository cardRepository) {
        return new ListCardsUseCase(cardRepository);
    }

    /**
     * Declares the get-card use case.
     *
     * @param cardRepository the port implementation supplied by the persistence adapter
     * @return the get-card use case
     */
    @Bean
    public GetCardUseCase getCardUseCase(final CardRepository cardRepository) {
        return new GetCardUseCase(cardRepository);
    }

    /**
     * Declares the create-session use case, behind {@code eop.features.session-lifecycle}.
     *
     * <p>Gated as well as the controller, and the reason is that the controller alone was
     * never enough. Until EOP-48 the four lifecycle use cases were registered
     * unconditionally, so a mistake in the flag's spelling had nothing to fail against:
     * the beans that write a session existed regardless, and only the route was withheld.
     * Withholding every caller of {@link SessionRepository} that opens or mutates a
     * session is what makes the flag's off position a property of the context rather than
     * of the request mapping — the same arrangement the trick-play use cases below use.
     *
     * @param sessionRepository the port implementation supplied by the persistence adapter
     * @param identifierGenerator the UUID version 7 generator (ADR-018)
     * @param joinCodeGenerator the cryptographically secure join code generator
     * @param identityTokenGenerator the cryptographically secure identity token generator
     * @param clock the clock the created timestamps are read from
     * @return the create-session use case
     */
    @Bean
    @ConditionalOnProperty(name = "eop.features.session-lifecycle", havingValue = "true")
    public CreateSessionUseCase createSessionUseCase(
            final SessionRepository sessionRepository,
            final IdentifierGenerator identifierGenerator,
            final JoinCodeGenerator joinCodeGenerator,
            final IdentityTokenGenerator identityTokenGenerator,
            final Clock clock) {
        return new CreateSessionUseCase(
                sessionRepository, identifierGenerator, joinCodeGenerator, identityTokenGenerator, clock);
    }

    /**
     * Declares the join-session use case, behind {@code eop.features.session-lifecycle}.
     *
     * @param sessionRepository the port implementation supplied by the persistence adapter
     * @param identifierGenerator the UUID version 7 generator (ADR-018)
     * @param identityTokenGenerator the cryptographically secure identity token generator
     * @param joinAttemptLimiter the rate limiter that bounds the guess rate against a join code (ADR-019)
     * @param sessionEventPublisher the transport that announces a change to connected clients
     * @param clock the clock the joined timestamp is read from
     * @return the join-session use case
     */
    @Bean
    @ConditionalOnProperty(name = "eop.features.session-lifecycle", havingValue = "true")
    public JoinSessionUseCase joinSessionUseCase(
            final SessionRepository sessionRepository,
            final IdentifierGenerator identifierGenerator,
            final IdentityTokenGenerator identityTokenGenerator,
            final JoinAttemptLimiter joinAttemptLimiter,
            final SessionEventPublisher sessionEventPublisher,
            final Clock clock) {
        return new JoinSessionUseCase(
                sessionRepository,
                identifierGenerator,
                identityTokenGenerator,
                joinAttemptLimiter,
                sessionEventPublisher,
                clock);
    }

    /**
     * Declares the resolve-player use case, deliberately ungated.
     *
     * <p>Every other lifecycle use case here is behind {@code eop.features.session-lifecycle};
     * this one is not, and the exception is load-bearing. It is a pure lookup — a token in, a
     * named player out, nothing written — and the five trick-play use cases below depend on it
     * too. Gating it on the lifecycle flag would make trick play unsatisfiable whenever the
     * lobby flag were off and trick play on, turning a withheld feature into a context that
     * will not start. Same reasoning as the ungated {@link DeckShuffler}: a dependency shared
     * across two flags belongs to neither.
     *
     * @param sessionRepository the port implementation supplied by the persistence adapter
     * @param clock the clock used to evaluate session expiry
     * @return the resolve-player use case
     */
    @Bean
    public ResolvePlayerUseCase resolvePlayerUseCase(final SessionRepository sessionRepository, final Clock clock) {
        return new ResolvePlayerUseCase(sessionRepository, clock);
    }

    /**
     * Declares the get-session-state use case, behind {@code eop.features.session-lifecycle}.
     *
     * @param resolvePlayerUseCase the use case that turns a token into a named player
     * @return the get-session-state use case
     */
    @Bean
    @ConditionalOnProperty(name = "eop.features.session-lifecycle", havingValue = "true")
    public GetSessionStateUseCase getSessionStateUseCase(final ResolvePlayerUseCase resolvePlayerUseCase) {
        return new GetSessionStateUseCase(resolvePlayerUseCase);
    }

    /**
     * Declares the start-session use case, behind {@code eop.features.session-lifecycle}.
     *
     * @param sessionRepository the port implementation supplied by the persistence adapter
     * @param resolvePlayerUseCase the use case that turns a token into a named player
     * @param sessionEventPublisher the transport that announces a change to connected clients
     * @param clock the clock the started timestamp is read from
     * @return the start-session use case
     */
    @Bean
    @ConditionalOnProperty(name = "eop.features.session-lifecycle", havingValue = "true")
    public StartSessionUseCase startSessionUseCase(
            final SessionRepository sessionRepository,
            final ResolvePlayerUseCase resolvePlayerUseCase,
            final SessionEventPublisher sessionEventPublisher,
            final Clock clock) {
        return new StartSessionUseCase(sessionRepository, resolvePlayerUseCase, sessionEventPublisher, clock);
    }

    /**
     * Declares the hand dealer, deliberately ungated.
     *
     * <p>It is the act of dealing, shared by {@link DealHandsUseCase} and {@link NewGameUseCase}.
     * Gating this bean on a feature flag would make one flag implicitly require another, so a
     * {@code trick-play=false} configuration in which a caller sat behind a flag of its own would
     * fail to start with an unsatisfied dependency naming a class that has nothing to do with the
     * cause. This is the same reasoning already recorded for {@link DeckShuffler} below, and it is
     * what allows the deal to live in one place instead of two (ADR-013).
     *
     * <p>Ungated does not mean unguarded. The dealer reaches the hand tables, so the containment
     * claim for {@code eop.features.trick-play} rests on both of its callers being gated rather than
     * on the dealer itself: with the flag off, nothing injects it. What the dealer notably does
     * <em>not</em> do is authorise anybody &mdash; that stays with each calling use case, as
     * {@link HandRepository} requires (ADR-024).
     *
     * @param cardRepository the port the whole deck is read through
     * @param deckShuffler the port that randomises the deck before it is dealt
     * @param handRepository the port the deal is recorded through
     * @param identifierGenerator the source of the hand identifiers
     * @param sessionEventPublisher the transport that announces the deal to connected clients
     * @param clock the clock the dealt timestamp is read from
     * @return the hand dealer
     */
    @Bean
    public HandDealer handDealer(
            final CardRepository cardRepository,
            final DeckShuffler deckShuffler,
            final HandRepository handRepository,
            final IdentifierGenerator identifierGenerator,
            final SessionEventPublisher sessionEventPublisher,
            final Clock clock) {
        return new HandDealer(
                cardRepository,
                deckShuffler,
                handRepository,
                identifierGenerator,
                sessionEventPublisher,
                clock);
    }

    /**
     * Declares the deal-hands use case, behind {@code eop.features.trick-play}.
     *
     * <p>The flag is on the bean rather than on a controller because this slice ships
     * no route. The persistence slice before it left five trick-play tables reachable
     * by anything that injects {@link HandRepository} or {@link TrickRepository}, and
     * this is the slice that adds the first such beans, so gating them here is what
     * makes the containment claim true rather than merely intended: with the flag off,
     * no bean exists that calls the ports which write a hand, a trick or a
     * play. The adapter implementing those ports is an unconditional {@code @Repository} and is
     * created either way; what the flag withholds is every caller of it. The route Slice D adds
     * is gated on the same flag (ADR-013).
     *
     * <p>{@link DeckShuffler} is deliberately not gated. It is stateless, reaches no
     * table, and gating it would only turn a missing feature into an unsatisfied
     * dependency somewhere further away from the cause. Since EOP-190 {@link HandDealer} is ungated
     * for a related but stronger reason: it <em>does</em> reach the hand tables, so it is the gating
     * of both its callers that withholds it, and gating the dealer itself would couple two
     * independent flags.
     *
     * @param resolvePlayerUseCase the use case that turns a token into a named player
     * @param handDealer the collaborator that shuffles, deals and announces the deal
     * @return the deal-hands use case
     */
    @Bean
    @ConditionalOnProperty(name = "eop.features.trick-play", havingValue = "true")
    public DealHandsUseCase dealHandsUseCase(
            final ResolvePlayerUseCase resolvePlayerUseCase, final HandDealer handDealer) {
        return new DealHandsUseCase(resolvePlayerUseCase, handDealer);
    }

    /**
     * Declares the read-own-hand use case, behind {@code eop.features.trick-play}.
     *
     * <p>Gated with the writers even though it only reads. The flag is meant to withhold trick play
     * entirely, and a read of a hand is trick play: with the flag off there are no hands to read, so
     * the bean would exist only to answer 409 to every caller.
     *
     * @param resolvePlayerUseCase the use case that turns a token into a named player
     * @param handRepository the port the caller's own hand is read through
     * @return the read-own-hand use case
     */
    @Bean
    @ConditionalOnProperty(name = "eop.features.trick-play", havingValue = "true")
    public ReadOwnHandUseCase readOwnHandUseCase(
            final ResolvePlayerUseCase resolvePlayerUseCase, final HandRepository handRepository) {
        return new ReadOwnHandUseCase(resolvePlayerUseCase, handRepository);
    }

    /**
     * Declares the trick journal, the collaborator every trick write goes through.
     *
     * <p>Extracted by EOP-190 from {@code PlayCardUseCase} and {@code ResolveTrickUseCase}, which
     * held the same completion cascade twice — record the resolution, announce it, and when the last
     * card is gone complete the session, persist the result best-effort and announce the game over.
     * Two copies of a cascade ending in a durable write is the shape where the copies drift, and here
     * a drift decides whether a finished game is ever scoreable.
     *
     * <p><strong>Deliberately not gated.</strong> Both of its callers are behind
     * {@code eop.features.trick-play} today, so gating the collaborator as well would withhold
     * nothing that gating them has not already withheld, while turning any future caller on a
     * different flag into an unsatisfied dependency far from its cause. That is the reasoning already
     * recorded for {@link DeckShuffler} and {@link HandDealer} above (ADR-013). Ungated is not
     * unguarded: containment rests on both callers being gated, and on this class authorising nobody
     * — establishing that the caller may play or resolve stays the calling use case's first statement
     * (ADR-024).
     *
     * @param trickRepository the port tricks are opened, appended to and resolved through
     * @param sessionRepository the port the session is completed through when the last trick resolves
     * @param sessionEventPublisher the transport each write is announced on, after it has landed
     * @param persistGameResultUseCase writes the final standings; the Optional is retained although
     *     its former {@code eop.features.game-over} gate was removed, so it is always present
     * @return the trick journal
     */
    @Bean
    public TrickJournal trickJournal(
            final TrickRepository trickRepository,
            final SessionRepository sessionRepository,
            final SessionEventPublisher sessionEventPublisher,
            final Optional<PersistGameResultUseCase> persistGameResultUseCase) {
        return new TrickJournal(trickRepository, sessionRepository, sessionEventPublisher, persistGameResultUseCase);
    }

    /**
     * Declares the play-card use case, behind {@code eop.features.trick-play}.
     *
     * <p>When the last card of a trick is played, this use case resolves the trick inline and
     * publishes {@code trick-resolved} (and {@code game-completed} if no cards remain). The
     * {@link ResolveTrickUseCase} endpoint remains available for reconnect and edge cases. Both
     * reach that cascade through the same {@link TrickJournal} since EOP-190, so the two routes
     * cannot drift apart.
     *
     * @param resolvePlayerUseCase the use case that turns a token into a named player
     * @param handRepository the port the hands and the current leader seat are read through
     * @param cardRepository the port the played card is resolved through
     * @param identifierGenerator the source of the trick and play identifiers
     * @param clock the clock the played timestamp is read from
     * @param trickJournal writes the trick, announces each write, and completes the game when the
     *     last trick resolves
     * @return the play-card use case
     */
    @Bean
    @ConditionalOnProperty(name = "eop.features.trick-play", havingValue = "true")
    public PlayCardUseCase playCardUseCase(
            final ResolvePlayerUseCase resolvePlayerUseCase,
            final HandRepository handRepository,
            final CardRepository cardRepository,
            final IdentifierGenerator identifierGenerator,
            final Clock clock,
            final TrickJournal trickJournal) {
        return new PlayCardUseCase(
                resolvePlayerUseCase, handRepository, cardRepository, identifierGenerator, clock, trickJournal);
    }

    /**
     * Declares the trick-state use case, behind {@code eop.features.trick-play}.
     *
     * <p>Reads two ports because the answer needs both: the trick in front of the players, and the
     * hands that decide which seats still hold cards. A use case is where two aggregates are put
     * together, so the web adapter never has to know how they relate.
     *
     * @param resolvePlayerUseCase the use case that turns a token into a named player
     * @param handRepository the port the hands and the current leader seat are read through
     * @param trickRepository the port the current trick is read through
     * @return the trick-state use case
     */
    @Bean
    @ConditionalOnProperty(name = "eop.features.trick-play", havingValue = "true")
    public GetTrickStateUseCase getTrickStateUseCase(
            final ResolvePlayerUseCase resolvePlayerUseCase,
            final HandRepository handRepository,
            final TrickRepository trickRepository) {
        return new GetTrickStateUseCase(resolvePlayerUseCase, handRepository, trickRepository);
    }

    /**
     * Declares the resolve-trick use case, behind {@code eop.features.trick-play}.
     *
     * @param resolvePlayerUseCase the use case that turns a token into a named player
     * @param handRepository the port the hands are read through
     * @param clock the clock the resolved timestamp is read from
     * @param trickJournal reads the current trick, records the resolution, announces it, and
     *     completes the game when no seat still holds a card
     * @return the resolve-trick use case
     */
    @Bean
    @ConditionalOnProperty(name = "eop.features.trick-play", havingValue = "true")
    public ResolveTrickUseCase resolveTrickUseCase(
            final ResolvePlayerUseCase resolvePlayerUseCase,
            final HandRepository handRepository,
            final Clock clock,
            final TrickJournal trickJournal) {
        return new ResolveTrickUseCase(resolvePlayerUseCase, handRepository, clock, trickJournal);
    }

    /**
     * Declares the end-session use case, behind {@code eop.features.trick-play}.
     *
     * <p>Gated on the same flag as the other trick-play use cases. Ending a session
     * is only meaningful once play has started, and play requires the trick-play flag.
     * A facilitator calling end on a session that was never in progress reaches
     * {@link org.maglez.eop.entity.SessionNotInProgressException} rather than a
     * missing bean, because the session-lifecycle flag is separate and may be on
     * while trick-play is off.
     *
     * @param resolvePlayerUseCase the use case that turns a token into a named player
     * @param sessionRepository the port the session status is advanced through
     * @param sessionEventPublisher the transport that announces the completion to connected clients
     * @param clock the clock the completed timestamp is read from
     * @return the end-session use case
     */
    @Bean
    @ConditionalOnProperty(name = "eop.features.trick-play", havingValue = "true")
    public EndSessionUseCase endSessionUseCase(
            final ResolvePlayerUseCase resolvePlayerUseCase,
            final SessionRepository sessionRepository,
            final SessionEventPublisher sessionEventPublisher,
            final Clock clock) {
        return new EndSessionUseCase(sessionRepository, resolvePlayerUseCase, sessionEventPublisher, clock);
    }

    /**
     * Declares the score use case.
     *
     * <p>Ungated: the score is a pure read derived from tricks and players, and it is needed
     * by the trick-play score endpoint. The {@link org.maglez.eop.adapter.web.ScoreController}
     * that exposes the score endpoint is gated on {@code trick-play}, so the route is not
     * published while that flag is off.
     *
     * <p>Two collaborators, not three. Resolving the credential already yields the session, and a
     * session carries its own players, so the seated players arrive without a second read and the
     * only port here is the one holding the tricks.
     *
     * @param resolvePlayerUseCase the use case that turns a token into a named player and their session
     * @param trickRepository      the port the session's tricks are read through
     * @return the score use case
     */
    @Bean
    @ConditionalOnProperty(name = "eop.features.trick-play", havingValue = "true")
    public GetScoreUseCase getScoreUseCase(
            final ResolvePlayerUseCase resolvePlayerUseCase,
            final TrickRepository trickRepository) {
        return new GetScoreUseCase(resolvePlayerUseCase, trickRepository);
    }

    /**
     * Declares the sweep-expired-sessions use case, behind
     * {@code eop.features.session-lifecycle}.
     *
     * <p>The sweep is gated on the same flag as the session lifecycle endpoints so
     * that it is only active when sessions can be created. While the flag is
     * {@code false} neither the use case nor the scheduler bean exist.
     *
     * @param sessionRepository the port used to find and delete expired sessions
     * @param clock the clock used to determine the current instant
     * @return the sweep use case
     */
    @Bean
    @ConditionalOnProperty(name = "eop.features.session-lifecycle", havingValue = "true")
    public SweepExpiredSessionsUseCase sweepExpiredSessionsUseCase(
            final SessionRepository sessionRepository,
            final Clock clock) {
        return new SweepExpiredSessionsUseCase(sessionRepository, clock);
    }

    /**
     * Declares the get-leaderboard use case.
     *
     * @param resolvePlayerUseCase  resolves the acting player from the identity token
     * @param gameResultRepository  reads the persisted game result
     * @param trickRepository       reads the tricks for the STRIDE breakdown
     * @return the get-leaderboard use case
     */
    @Bean
    public GetLeaderboardUseCase getLeaderboardUseCase(
            final ResolvePlayerUseCase resolvePlayerUseCase,
            final GameResultRepository gameResultRepository,
            final TrickRepository trickRepository) {
        return new GetLeaderboardUseCase(resolvePlayerUseCase, gameResultRepository, trickRepository);
    }

    /**
     * Declares the persist-game-result use case.
     *
     * @param sessionRepository    reads the session and its players
     * @param trickRepository      reads the tricks for scoring
     * @param gameResultRepository persists the game result
     * @param identifierGenerator  mints the game result identifier
     * @param clock                supplies the finalised-at timestamp
     * @return the persist-game-result use case
     */
    @Bean
    public PersistGameResultUseCase persistGameResultUseCase(
            final SessionRepository sessionRepository,
            final TrickRepository trickRepository,
            final GameResultRepository gameResultRepository,
            final IdentifierGenerator identifierGenerator,
            final Clock clock) {
        return new PersistGameResultUseCase(
                sessionRepository, trickRepository, gameResultRepository, identifierGenerator, clock);
    }

    /**
     * Declares the new-game use case.
     *
     * <p>It reaches the deal through the ungated {@link HandDealer} rather than through
     * {@link DealHandsUseCase}, which is gated on {@code eop.features.trick-play}: injecting the
     * gated use case would make this ungated one silently require {@code trick-play} and fail the
     * context whenever that flag is off (ADR-013).
     *
     * @param resolvePlayerUseCase  resolves the acting player from the identity token
     * @param handRepository        clears the hands of the finished game
     * @param trickRepository       clears the tricks of the finished game
     * @param sessionRepository     resets session status
     * @param handDealer            deals the new game and records its opening lead
     * @param clock                 supplies timestamps
     * @return the new-game use case
     */
    @Bean
    public NewGameUseCase newGameUseCase(
            final ResolvePlayerUseCase resolvePlayerUseCase,
            final HandRepository handRepository,
            final TrickRepository trickRepository,
            final SessionRepository sessionRepository,
            final HandDealer handDealer,
            final Clock clock) {
        return new NewGameUseCase(
                resolvePlayerUseCase, handRepository, trickRepository, sessionRepository,
                handDealer, clock);
    }
}
