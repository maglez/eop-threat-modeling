package org.maglez.eop.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.maglez.eop.domain.GameSessionBuilder.aSession;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.domain.Card;
import org.maglez.eop.domain.DeckFixture;
import org.maglez.eop.domain.GameSession;
import org.maglez.eop.domain.HandNotDealtException;
import org.maglez.eop.domain.Hands;
import org.maglez.eop.domain.Player;
import org.maglez.eop.domain.PlayerBuilder;
import org.maglez.eop.domain.PlayerNotInSessionException;
import org.maglez.eop.domain.PlayerNotRecognisedException;
import org.maglez.eop.domain.Rank;
import org.maglez.eop.domain.SessionNotFoundException;
import org.maglez.eop.domain.SessionStatus;
import org.maglez.eop.domain.StrideCategory;
import org.maglez.eop.domain.Trick;
import org.maglez.eop.domain.TrickPlay;
import org.maglez.eop.application.port.in.GetTrickStatePort;
import org.maglez.eop.application.port.in.TrickState;
import org.maglez.eop.application.port.out.HandRepository;
import org.maglez.eop.application.port.out.InMemoryHandRepository;
import org.maglez.eop.application.port.out.InMemorySessionRepository;
import org.maglez.eop.application.port.out.InMemoryTrickRepository;
import org.maglez.eop.domain.GameSessionBuilder;

/**
 * Exercises the only read that answers whose turn it is.
 *
 * <p>Every assertion here is about a moment, because the four answers this use case gives change
 * shape at each of them and nothing else in the system reports them. A test that only read a trick
 * under way would pass against an implementation that never consulted the seats holding cards, and
 * the table would then be told to wait for a card from a player who has none.
 *
 * <p>Five moments are pinned: after the deal but before the first lead, with a trick part played,
 * with a trick complete and waiting to be resolved, with a trick resolved and play continuing, and
 * with the hand played out. The interesting one is the last, because {@code seatToPlay} and
 * {@code handComplete} together are the only way a client can tell "nobody may play because the
 * trick is finished" from "nobody may play because the hand is finished", and an implementation
 * that reported the winner's seat in both cases would look correct in every other test.
 *
 * <p>{@code seatToPlay} and {@code nextLeaderSeat} come from different authorities on purpose — the
 * cards in the trick and the seat the session records as leading — so where both are present they
 * are asserted to agree rather than one being derived from the other. Reconciling them in the use
 * case would throw away the only check a client has on either.
 *
 * <p>The refusals are asserted to happen <em>before</em> any hand is read, by checking the
 * repository was never asked. {@link HandRepository} authorises nobody (ADR-024), so if this use
 * case read first and authorised second the whole table's hands would be in memory at the moment a
 * stranger was turned away.
 *
 * <p>The deck is a single suit throughout, so the highest rank played takes the trick without the
 * test having to restate any rule about trumps.
 */
@DisplayName("GetTrickStatePort")
class GetTrickStateServiceTest {

    private static final UUID TRICK_ID = UUID.fromString("00000000-0000-7000-8000-0000000000c0");

    private static final Instant PLAYED_AT = Instant.parse("2026-02-01T09:45:00Z");

    private static final long HAND_PREFIX = 900L;

    private static final long PLAY_PREFIX = 910L;

    private static final int SEATS = 3;

    private static final int LEADER_SEAT = 0;

    private static final int FIRST_SEQUENCE = 1;

    private static final int LARGER_TABLE = 4;

    private static final int UNDEALT_SEAT = 3;

    private final List<String> order = new ArrayList<>();

    private final InMemoryHandRepository handRepository = new InMemoryHandRepository(order);

    private final InMemoryTrickRepository trickRepository = new InMemoryTrickRepository(order);

    /**
     * Seats three players at a table already in play.
     *
     * @return a session whose hands can be dealt and played
     */
    private static GameSession seatedTable() {
        return aSession().withPlayerCount(SEATS).withStatus(SessionStatus.IN_PROGRESS).build();
    }

    /**
     * Deals a single-suit deck, one card at a time round the table.
     *
     * @param session the session whose players are dealt to
     * @param seatsToDeal how many seats, counted from zero, are given a hand
     * @param ranks the ranks to deal, in the order they should leave the deck
     * @return the hands as dealt
     */
    private static Hands dealTo(final GameSession session, final int seatsToDeal, final Rank... ranks) {
        final List<Hands.Seat> seats = session.players().stream()
                .sorted(Comparator.comparingInt(Player::seatOrder))
                .filter(player -> player.seatOrder() < seatsToDeal)
                .map(player -> new Hands.Seat(
                        player.seatOrder(), player.playerId(), new UUID(HAND_PREFIX, player.seatOrder())))
                .toList();

        return Hands.deal(DeckFixture.cards(StrideCategory.SPOOFING, ranks), seats);
    }

    /**
     * Finds the player seated at the given seat.
     *
     * @param session the session to look in
     * @param seatOrder the seat to look for
     * @return the player seated there
     */
    private static Player playerAt(final GameSession session, final int seatOrder) {
        return session.players().stream()
                .filter(player -> player.seatOrder() == seatOrder)
                .findFirst()
                .orElseThrow();
    }

    /**
     * Answers the plaintext token the player at the given seat presents.
     *
     * @param seatOrder the seat presenting the token
     * @return the plaintext behind that player's digest
     */
    private static String tokenForSeat(final int seatOrder) {
        return seatOrder == 0
                ? PlayerBuilder.DEFAULT_TOKEN
                : PlayerBuilder.DEFAULT_TOKEN + "-" + seatOrder;
    }

    /**
     * Plays the given seats into a trick in turn, each from the hands as they stood beforehand.
     *
     * @param session the session whose players are playing
     * @param dealt the hands as dealt
     * @param seatsToPlay the seats to play, in turn order
     * @return the trick and the hands after those plays
     */
    private static TrickUnderWay playInto(
            final GameSession session, final Hands dealt, final int... seatsToPlay) {
        Trick trick = Trick.open(TRICK_ID, FIRST_SEQUENCE, LEADER_SEAT);
        Hands remaining = dealt;

        for (final int seatOrder : seatsToPlay) {
            final Card card = remaining.handOf(seatOrder).cards().get(0);
            final TrickPlay candidate = new TrickPlay(
                    new UUID(PLAY_PREFIX, seatOrder),
                    playerAt(session, seatOrder).playerId(),
                    seatOrder,
                    card,
                    false,
                    List.of(),
                    null,
                    PLAYED_AT);

            trick = trick.acceptPlay(seatOrder, candidate, remaining);
            remaining = remaining.withCardPlayed(seatOrder, card);
        }

        return new TrickUnderWay(trick, remaining);
    }

    /**
     * A trick and the hands it left behind.
     *
     * @param trick the trick after the plays
     * @param remaining the hands after those cards were removed
     */
    private record TrickUnderWay(Trick trick, Hands remaining) {}

    /**
     * Assembles the use case against the doubles, with a real player resolver.
     *
     * @param session the session the resolver reads
     * @return the use case under test
     */
    private GetTrickStatePort useCaseFor(final GameSession session) {
        return new GetTrickStateService(
                new ResolvePlayerService(new InMemorySessionRepository(order, session), java.time.Clock.systemUTC()),
                handRepository,
                trickRepository);
    }

    /**
     * The moment after the deal, when there is no trick to describe but somebody still has to lead.
     *
     * <p>This is the only moment at which {@code seatToPlay} can come from nowhere but the session
     * row, because there are no cards on the table to derive it from. A client that could not read
     * it here would have no way to know the game had started.
     */
    @Test
    @DisplayName("names the opening leader before the first card is led")
    void shouldReportTheOpeningLeaderBeforeAnyTrick() {
        final GameSession session = seatedTable();
        handRepository.seededWith(dealTo(session, SEATS, Rank.TWO, Rank.THREE, Rank.FOUR), LEADER_SEAT);

        final TrickState state = useCaseFor(session).execute(session.sessionId(), tokenForSeat(1));

        assertThat(state.trick()).as("no card has been led, so there is no trick").isEmpty();
        assertThat(state.seatToPlay()).hasValue(LEADER_SEAT);
        assertThat(state.complete()).isFalse();
        assertThat(state.nextLeaderSeat()).isEmpty();
        assertThat(state.handComplete()).isFalse();
        assertThat(order).as("a read writes nothing").isEmpty();
    }

    @Test
    @DisplayName("names the seat still to play while a trick is under way")
    void shouldReportTheSeatStillToPlay() {
        final GameSession session = seatedTable();
        final Hands dealt = dealTo(session, SEATS, Rank.TWO, Rank.THREE, Rank.FOUR, Rank.FIVE, Rank.SIX, Rank.SEVEN);
        final TrickUnderWay underWay = playInto(session, dealt, LEADER_SEAT);
        handRepository.seededWith(underWay.remaining(), LEADER_SEAT);
        trickRepository.seededWith(underWay.trick());

        final TrickState state = useCaseFor(session).execute(session.sessionId(), tokenForSeat(2));

        assertThat(state.trick()).isPresent();
        assertThat(state.seatToPlay()).as("the lead has been played, so the next seat clockwise owes a card").hasValue(1);
        assertThat(state.complete()).isFalse();
        assertThat(state.nextLeaderSeat()).as("nothing leads next until this trick is resolved").isEmpty();
        assertThat(state.handComplete()).isFalse();
    }

    /**
     * A trick every seat has played into, and nobody has resolved.
     *
     * <p>Both seat answers are absent at once here, and that is the point: no card may be played
     * and no seat leads next, because the trick is waiting on a resolution rather than on a player.
     * A client shown a seat number at this moment would prompt somebody to play out of turn.
     */
    @Test
    @DisplayName("names no seat while a complete trick waits to be resolved")
    void shouldNameNoSeatWhileACompleteTrickIsUnresolved() {
        final GameSession session = seatedTable();
        final Hands dealt = dealTo(session, SEATS, Rank.TWO, Rank.THREE, Rank.FOUR, Rank.FIVE, Rank.SIX, Rank.SEVEN);
        final TrickUnderWay underWay = playInto(session, dealt, LEADER_SEAT, 1, 2);
        handRepository.seededWith(underWay.remaining(), LEADER_SEAT);
        trickRepository.seededWith(underWay.trick());

        final TrickState state = useCaseFor(session).execute(session.sessionId(), tokenForSeat(LEADER_SEAT));

        assertThat(state.complete()).isTrue();
        assertThat(state.seatToPlay()).as("every seat holding cards has played").isEmpty();
        assertThat(state.nextLeaderSeat()).as("the trick is not resolved, so no seat leads next yet").isEmpty();
        assertThat(state.handComplete()).as("each seat was dealt two cards and has played one").isFalse();
    }

    /**
     * A resolved trick, with the hand still going.
     *
     * <p>Both seat answers are present, and they are asserted to agree without either being derived
     * from the other: {@code seatToPlay} is read from the session row as the resolution left it,
     * {@code nextLeaderSeat} from the cards in the trick. A disagreement is a defect a client is
     * entitled to notice, which is only true while both are published.
     */
    @Test
    @DisplayName("names the seat that leads next once the trick is resolved, from both authorities")
    void shouldReportTheNextLeaderOnceResolved() {
        final GameSession session = seatedTable();
        final Hands dealt = dealTo(session, SEATS, Rank.TWO, Rank.THREE, Rank.FOUR, Rank.FIVE, Rank.SIX, Rank.SEVEN);
        final TrickUnderWay underWay = playInto(session, dealt, LEADER_SEAT, 1, 2);
        final Trick resolved = underWay.trick().resolved();
        handRepository.seededWith(underWay.remaining(), resolved.winningSeat());
        trickRepository.seededWith(resolved);

        final TrickState state = useCaseFor(session).execute(session.sessionId(), tokenForSeat(1));

        assertThat(resolved.winningSeat()).as("the highest rank of the led suit was dealt to seat two").isEqualTo(2);
        assertThat(state.complete()).isTrue();
        assertThat(state.nextLeaderSeat()).hasValue(resolved.winningSeat());
        assertThat(state.seatToPlay())
                .as("the session row and the trick agree on who leads")
                .isEqualTo(state.nextLeaderSeat());
        assertThat(state.handComplete()).isFalse();
    }

    /**
     * The hand played out, which is one of the three ways this game ends (PRD §3.3).
     *
     * <p>Three cards over three seats empties every hand in one trick, so this is the shortest hand
     * the domain will deal. Nothing may be played and no seat leads, and the only thing separating
     * that from a trick merely waiting on a resolution is {@code handComplete}. The session status
     * is deliberately still {@code IN_PROGRESS}: EOP-14 reports the spent hand and EOP-15 owns the
     * move to {@code COMPLETED}, which is reserved for a hand whose score is final.
     */
    @Test
    @DisplayName("reports the hand complete once no seat holds a card")
    void shouldReportAPlayedOutHand() {
        final GameSession session = seatedTable();
        final Hands dealt = dealTo(session, SEATS, Rank.TWO, Rank.THREE, Rank.FOUR);
        final TrickUnderWay underWay = playInto(session, dealt, LEADER_SEAT, 1, 2);
        final Trick resolved = underWay.trick().resolved();
        handRepository.seededWithNoLeader(underWay.remaining());
        trickRepository.seededWith(resolved);

        final TrickState state = useCaseFor(session).execute(session.sessionId(), tokenForSeat(2));

        assertThat(state.handComplete()).isTrue();
        assertThat(state.seatToPlay()).as("no seat holds a card, so no seat may play").isEmpty();
        assertThat(state.nextLeaderSeat()).as("no seat holds a card, so no seat leads next").isEmpty();
        assertThat(state.complete()).isTrue();
        assertThat(state.trick()).isPresent();
    }

    @Test
    @DisplayName("refuses before the deck has been dealt")
    void shouldRefuseBeforeTheDeal() {
        final GameSession session = seatedTable();
        final GetTrickStatePort useCase = useCaseFor(session);
        final UUID sessionId = session.sessionId();

        assertThatExceptionOfType(HandNotDealtException.class)
                .isThrownBy(() -> useCase.execute(sessionId, tokenForSeat(LEADER_SEAT)))
                .satisfies(thrown -> assertThat(thrown.sessionId()).isEqualTo(sessionId));
    }

    @Test
    @DisplayName("refuses a stranger before reading a single hand")
    void shouldRefuseAStranger() {
        final GameSession session = seatedTable();
        handRepository.seededWith(dealTo(session, SEATS, Rank.TWO, Rank.THREE, Rank.FOUR), LEADER_SEAT);
        final GetTrickStatePort useCase = useCaseFor(session);
        final UUID sessionId = session.sessionId();

        assertThatExceptionOfType(PlayerNotRecognisedException.class)
                .isThrownBy(() -> useCase.execute(sessionId, "not-a-seated-player"));

        assertThat(handRepository.sessionsAsked()).isEmpty();
    }

    @Test
    @DisplayName("refuses a caller presenting no token at all")
    void shouldRefuseAMissingToken() {
        final GameSession session = seatedTable();
        handRepository.seededWith(dealTo(session, SEATS, Rank.TWO, Rank.THREE, Rank.FOUR), LEADER_SEAT);
        final GetTrickStatePort useCase = useCaseFor(session);
        final UUID sessionId = session.sessionId();

        assertThatExceptionOfType(PlayerNotRecognisedException.class)
                .isThrownBy(() -> useCase.execute(sessionId, null));

        assertThat(handRepository.sessionsAsked()).isEmpty();
    }

    @Test
    @DisplayName("refuses an unknown session before reading a single hand")
    void shouldRefuseAnUnknownSession() {
        final GameSession session = seatedTable();
        handRepository.seededWith(dealTo(session, SEATS, Rank.TWO, Rank.THREE, Rank.FOUR), LEADER_SEAT);
        final GetTrickStatePort useCase = useCaseFor(session);
        final UUID stranger = UUID.fromString("00000000-0000-7000-8000-00000000dead");

        assertThatExceptionOfType(SessionNotFoundException.class)
                .isThrownBy(() -> useCase.execute(stranger, tokenForSeat(LEADER_SEAT)));

        assertThat(handRepository.sessionsAsked()).isEmpty();
    }

    /**
     * A seat the deal never reached.
     *
     * <p>No legal sequence of calls produces this, since the deal writes every seat's hand in one
     * transaction. The guard exists so a partial write comes out as a refusal naming the session
     * rather than as an out-of-range read further down.
     */
    @Test
    @DisplayName("refuses a seat that was never dealt a hand")
    void shouldRefuseASeatWithNoHand() {
        final GameSession session = aSession()
                .withPlayerCount(LARGER_TABLE)
                .withStatus(SessionStatus.IN_PROGRESS)
                .build();
        handRepository.seededWith(dealTo(session, UNDEALT_SEAT, Rank.TWO, Rank.THREE, Rank.FOUR), LEADER_SEAT);
        final GetTrickStatePort useCase = useCaseFor(session);
        final UUID sessionId = session.sessionId();

        assertThatExceptionOfType(PlayerNotInSessionException.class)
                .isThrownBy(() -> useCase.execute(sessionId, tokenForSeat(UNDEALT_SEAT)))
                .satisfies(thrown -> assertThat(thrown.sessionId()).isEqualTo(sessionId));
    }

    @Test
    @DisplayName("refuses to be built without its collaborators")
    void shouldRequireItsCollaborators() {
        final var resolver = new ResolvePlayerService(new InMemorySessionRepository(order, seatedTable()), java.time.Clock.systemUTC());

        assertThatNullPointerException()
                .isThrownBy(() -> new GetTrickStateService(null, handRepository, trickRepository))
                .withMessageContaining("resolvePlayerPort");
        assertThatNullPointerException()
                .isThrownBy(() -> new GetTrickStateService(resolver, null, trickRepository))
                .withMessageContaining("handRepository");
        assertThatNullPointerException()
                .isThrownBy(() -> new GetTrickStateService(resolver, handRepository, null))
                .withMessageContaining("trickRepository");
    }

    /**
     * The three parts of the state that can be absent are held as {@link Optional} and
     * {@link OptionalInt} rather than as nullable fields, which is only worth anything if a
     * missing one is refused rather than stored. Every other test builds the record through
     * the use case, where all three arrive present, so without this one a change that deleted
     * the guards would leave the whole suite green and hand a later caller a state whose
     * absent parts are indistinguishable from a null nobody checked.
     */
    @Test
    @DisplayName("refuses to describe the state of play with a missing part")
    void shouldRequireEveryPartOfTheState() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TrickState(null, OptionalInt.empty(), false, OptionalInt.empty(), false))
                .withMessage("trick is required");
        assertThatNullPointerException()
                .isThrownBy(() -> new TrickState(Optional.empty(), null, false, OptionalInt.empty(), false))
                .withMessage("seatToPlay is required");
        assertThatNullPointerException()
                .isThrownBy(() -> new TrickState(Optional.empty(), OptionalInt.empty(), false, null, false))
                .withMessage("nextLeaderSeat is required");
    }

}
