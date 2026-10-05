package org.maglez.eop.usecase;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.maglez.eop.entity.GameSession;
import org.maglez.eop.entity.Hands;
import org.maglez.eop.entity.NoTamperingCardDealtException;
import org.maglez.eop.entity.Player;
import org.maglez.eop.entity.TooFewPlayersException;

/**
 * Shuffles the deck, deals it to the seated players, records the opening lead and announces it.
 *
 * <p>This is the act of dealing and nothing else. It exists as a collaborator rather than as a step
 * inside a use case because two use cases need the identical act: {@link DealHandsUseCase} deals a
 * session that has just started, and {@link NewGameUseCase} deals a session it has just reset. Those
 * two carried a verbatim copy of this sequence each, which is the kind of duplication that stays
 * correct only for as long as nobody edits one copy.
 *
 * <p><strong>This class authorises nobody.</strong> It takes a {@link GameSession} that the caller
 * has already resolved and does not ask who requested the deal, so it must never be reached from an
 * adapter directly. Establishing that the requester is the facilitator of this table is the calling
 * use case's obligation, discharged as the first statement of its own {@code execute} &mdash; the
 * same obligation {@link HandRepository} places on its callers, and for the same reason: the refusals
 * underneath are informative by design, which is right for a member of the table and an oracle for
 * anybody else (ADR-024).
 *
 * <p>It is deliberately <em>not</em> gated on a feature flag. One of its callers,
 * {@link DealHandsUseCase}, sits behind {@code eop.features.trick-play}; the other,
 * {@link NewGameUseCase}, is ungated since its former {@code eop.features.game-over} gate was
 * removed once the feature reached permanent rollout. Gating this class on trick-play would make
 * the ungated caller silently require it. That reasoning is already recorded for
 * {@link DeckShuffler} in the configuration that wires these beans (ADR-013).
 *
 * <p>Only two things are read from the session: its identifier and its seats. The status is
 * <em>not</em> read, and that is what makes the class safe to hand a session object that was
 * resolved before the caller changed its status through a port &mdash; as {@link NewGameUseCase}
 * does, resetting a completed session to {@code IN_PROGRESS} and then dealing to the players it had
 * already read. Whether the session was playable at the moment of the write is decided by
 * {@link HandRepository#recordDeal} against the database, not by a status check up here that would
 * read the state, let go of it, and then write (ADR-020).
 *
 * <p>The seat count is checked here because this is where {@link Hands#deal} is called, and
 * {@code deal} answers too few players with an {@link IllegalArgumentException}: correct for a
 * programming error, but a 500 for what is really an ordinary "wait for one more player".
 * {@link TooFewPlayersException} says so with a 409. A caller with destructive work to do before the
 * deal is expected to check the same condition earlier as well, so that it refuses before it
 * destroys anything rather than after.
 *
 * <p>Nothing is returned. A result carrying every hand is exactly the shape that leaks private
 * information &mdash; the reason {@link Hands#toString()} names no card &mdash; and the announcement
 * carries no more: {@link SessionEvent} is a type, a session and an instant, so {@code hand-dealt}
 * tells every subscriber that the deal happened without telling anybody what anybody got, and each
 * player reads their own hand through their own authorised query (ADR-014). It is published after the
 * write returns, so a refused deal announces nothing.
 *
 * <p>Pure: no Spring, no Jakarta imports.
 */
public class HandDealer {

    private final CardRepository cardRepository;
    private final DeckShuffler deckShuffler;
    private final HandRepository handRepository;
    private final IdentifierGenerator identifierGenerator;
    private final SessionEventPublisher sessionEventPublisher;
    private final Clock clock;

    /**
     * Creates the dealer.
     *
     * @param cardRepository reads the whole deck
     * @param deckShuffler randomises the deck before it is dealt
     * @param handRepository records the deal and the opening lead
     * @param identifierGenerator mints one hand identifier per seat
     * @param sessionEventPublisher announces that the deal happened, naming no card
     * @param clock supplies the instant the deal is recorded at
     */
    public HandDealer(
            final CardRepository cardRepository,
            final DeckShuffler deckShuffler,
            final HandRepository handRepository,
            final IdentifierGenerator identifierGenerator,
            final SessionEventPublisher sessionEventPublisher,
            final Clock clock) {
        this.cardRepository = Objects.requireNonNull(cardRepository, "cardRepository is required");
        this.deckShuffler = Objects.requireNonNull(deckShuffler, "deckShuffler is required");
        this.handRepository = Objects.requireNonNull(handRepository, "handRepository is required");
        this.identifierGenerator =
                Objects.requireNonNull(identifierGenerator, "identifierGenerator is required");
        this.sessionEventPublisher =
                Objects.requireNonNull(sessionEventPublisher, "sessionEventPublisher is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * Deals a freshly shuffled deck to every seated player and records which seat leads.
     *
     * <p>The caller must have authorised the requester before calling this method.
     *
     * @param session the session to deal to, already resolved by the caller; only its identifier and
     *     its seats are read
     * @throws NullPointerException if session is null
     * @throws TooFewPlayersException if fewer than {@link GameSession#MINIMUM_PLAYERS_TO_START}
     *     players are seated
     * @throws org.maglez.eop.entity.SessionNotJoinableException if the session was not playable at
     *     the moment of the write, which includes a session that had not yet started
     * @throws org.maglez.eop.entity.HandAlreadyDealtException if this session has already been dealt
     * @throws org.maglez.eop.entity.PlayerNotInSessionException if a seat named in the deal is not
     *     seated in this session
     * @throws org.maglez.eop.entity.NotYourSeatException if a player named in the deal does not hold
     *     the seat the deal gives them
     * @throws NoTamperingCardDealtException if the shuffled deck contained no Tampering card, so no
     *     opening lead can be derived
     */
    public void deal(final GameSession session) {
        Objects.requireNonNull(session, "session is required");

        final var sessionId = session.sessionId();
        final var seated = session.players().size();
        if (seated < GameSession.MINIMUM_PLAYERS_TO_START) {
            throw new TooFewPlayersException(
                    sessionId, seated, GameSession.MINIMUM_PLAYERS_TO_START);
        }

        final List<Hands.Seat> seats =
                session.players().stream()
                        .sorted(Comparator.comparingInt(Player::seatOrder))
                        .map(
                                player ->
                                        new Hands.Seat(
                                                player.seatOrder(),
                                                player.playerId(),
                                                identifierGenerator.nextIdentifier()))
                        .toList();

        final var hands = Hands.deal(deckShuffler.shuffle(cardRepository.findWholeDeck()), seats);

        final var now = clock.instant();
        handRepository.recordDeal(sessionId, hands, hands.openingLeaderSeat(), now);
        sessionEventPublisher.publish(new SessionEvent(SessionEventType.HAND_DEALT, sessionId, now));
    }
}
