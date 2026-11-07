package org.maglez.eop.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.maglez.eop.domain.GameSessionBuilder.aSession;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.domain.Card;
import org.maglez.eop.domain.DeckFixture;
import org.maglez.eop.domain.GameSession;
import org.maglez.eop.domain.Hand;
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
import org.maglez.eop.application.port.in.ReadOwnHandPort;
import org.maglez.eop.application.port.out.HandRepository;
import org.maglez.eop.application.port.out.InMemoryHandRepository;
import org.maglez.eop.application.port.out.InMemorySessionRepository;
import org.maglez.eop.domain.GameSessionBuilder;

/**
 * Exercises the one read that returns cards to the player holding them.
 *
 * <p>The assertion that earns its keep is that every seat gets its own hand and nobody else's. A
 * test that only ever read as seat zero would pass against a use case that ignored the token and
 * returned the leader's hand, or the first hand in the map, and the defect would be the worst kind
 * this game can have: a player seeing another player's cards. So the happy path is asserted once per
 * seat, and each assertion names the card that seat and only that seat was dealt.
 *
 * <p>Two refusals are asserted to happen <em>before</em> any hand is read, by checking that the
 * repository was never asked. The order matters because {@link HandRepository} authorises nobody
 * (ADR-024): if this use case read first and authorised second, the hands would already be in memory
 * at the moment of the refusal, and the next careless edit would return them.
 *
 * <p>Two states that look like faults are asserted to be ordinary answers instead — a hand emptied by
 * play, and a session that has finished. Both are deliberate, and without a test the obvious
 * "tidying" is to add a status check that hides a player's own cards from them.
 *
 * <p>The deck is a single suit and one card per seat, so the card a seat was dealt identifies the
 * hand it came from without the test having to restate any rule about ranks or trumps.
 */
@DisplayName("ReadOwnHandPort")
class ReadOwnHandServiceTest {

    private static final long HAND_PREFIX = 900L;

    private static final int SEATS = 3;

    private static final int LARGER_TABLE = 4;

    private static final int UNDEALT_SEAT = 3;

    private final List<String> order = new ArrayList<>();

    private final InMemoryHandRepository handRepository = new InMemoryHandRepository(order);

    /**
     * Seats three players at a table already in play.
     *
     * @return a session whose status admits a deal
     */
    private static GameSession seatedTable() {
        return aSession().withPlayerCount(SEATS).withStatus(SessionStatus.IN_PROGRESS).build();
    }

    /**
     * Deals a single-suit deck one card at a time round the table.
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
     * Assembles the use case against the double, with a real player resolver.
     *
     * @param session the session the resolver reads
     * @return the use case under test
     */
    private ReadOwnHandPort useCaseFor(final GameSession session) {
        return new ReadOwnHandService(
                new ResolvePlayerService(new InMemorySessionRepository(order, session),
                        java.time.Clock.systemUTC()), handRepository);
    }

    @Test
    @DisplayName("returns to each seat the hand that seat was dealt, and no other")
    void shouldReturnTheCallersOwnHand() {
        final GameSession session = seatedTable();
        final Hands dealt = dealTo(session, SEATS, Rank.TWO, Rank.THREE, Rank.FOUR);
        handRepository.seededWith(dealt, 0);
        final ReadOwnHandPort useCase = useCaseFor(session);
        final List<Rank> dealtInSeatOrder = List.of(Rank.TWO, Rank.THREE, Rank.FOUR);

        for (int seatOrder = 0; seatOrder < SEATS; seatOrder++) {
            final Hand hand = useCase.execute(session.sessionId(), tokenForSeat(seatOrder));

            assertThat(hand.handId())
                    .as("seat %d is given the hand identifier dealt to seat %d", seatOrder, seatOrder)
                    .isEqualTo(new UUID(HAND_PREFIX, seatOrder));
            assertThat(hand.cards())
                    .extracting(Card::rank)
                    .as("seat %d holds only the card dealt to it", seatOrder)
                    .containsExactly(dealtInSeatOrder.get(seatOrder));
        }

        assertThat(order).as("a read writes nothing").isEmpty();
    }

    @Test
    @DisplayName("names the player the token identifies, not the seat that leads")
    void shouldReportTheCallersOwnPlayerIdentifier() {
        final GameSession session = seatedTable();
        handRepository.seededWith(dealTo(session, SEATS, Rank.TWO, Rank.THREE, Rank.FOUR), 0);
        final Player second = session.players().stream()
                .filter(player -> player.seatOrder() == 1)
                .findFirst()
                .orElseThrow();

        final Hand hand = useCaseFor(session).execute(session.sessionId(), tokenForSeat(1));

        assertThat(hand.playerId()).isEqualTo(second.playerId());
    }

    /**
     * A hand emptied by play is still a hand.
     *
     * <p>The last trick of a deal is played out of hands that are about to be empty, and a player
     * whose final card is on the table has every reason to look. Answering that read with a refusal
     * would be a state problem where there is no problem, so the empty hand is returned.
     */
    @Test
    @DisplayName("returns an empty hand once the caller has played its last card")
    void shouldReturnAnEmptyHand() {
        final GameSession session = seatedTable();
        final Hands dealt = dealTo(session, SEATS, Rank.TWO, Rank.THREE, Rank.FOUR);
        final Card onlyCard = dealt.handOf(1).cards().get(0);
        handRepository.seededWith(dealt.withCardPlayed(1, onlyCard), 0);

        final Hand hand = useCaseFor(session).execute(session.sessionId(), tokenForSeat(1));

        assertThat(hand.isEmpty()).isTrue();
        assertThat(hand.cards()).isEmpty();
    }

    /**
     * The deliberate absence of a status check, pinned so it cannot be added as a tidy-up.
     *
     * <p>Nothing is disclosed to a caller reading its own cards after the game has finished that it
     * did not hold while the game was live, and refusing would hide a player's own hand from them at
     * the moment they most want to review it.
     */
    @Test
    @DisplayName("still returns the caller's hand after the session has finished")
    void shouldReadAHandInAFinishedSession() {
        final GameSession session = aSession()
                .withPlayerCount(SEATS)
                .withStatus(SessionStatus.COMPLETED)
                .build();
        handRepository.seededWith(dealTo(session, SEATS, Rank.TWO, Rank.THREE, Rank.FOUR), 0);

        final Hand hand = useCaseFor(session).execute(session.sessionId(), tokenForSeat(0));

        assertThat(hand.cards()).extracting(Card::rank).containsExactly(Rank.TWO);
    }

    @Test
    @DisplayName("refuses before the deck has been dealt")
    void shouldRefuseBeforeTheDeal() {
        final GameSession session = seatedTable();
        final ReadOwnHandPort useCase = useCaseFor(session);
        final UUID sessionId = session.sessionId();

        assertThatExceptionOfType(HandNotDealtException.class)
                .isThrownBy(() -> useCase.execute(sessionId, tokenForSeat(0)))
                .satisfies(thrown -> assertThat(thrown.sessionId()).isEqualTo(sessionId));
    }

    @Test
    @DisplayName("refuses a stranger before reading a single hand")
    void shouldRefuseAStranger() {
        final GameSession session = seatedTable();
        handRepository.seededWith(dealTo(session, SEATS, Rank.TWO, Rank.THREE, Rank.FOUR), 0);
        final ReadOwnHandPort useCase = useCaseFor(session);
        final UUID sessionId = session.sessionId();

        assertThatExceptionOfType(PlayerNotRecognisedException.class)
                .isThrownBy(() -> useCase.execute(sessionId, "not-a-seated-player"));

        assertThat(handRepository.sessionsAsked()).isEmpty();
    }

    @Test
    @DisplayName("refuses a caller presenting no token at all")
    void shouldRefuseAMissingToken() {
        final GameSession session = seatedTable();
        handRepository.seededWith(dealTo(session, SEATS, Rank.TWO, Rank.THREE, Rank.FOUR), 0);
        final ReadOwnHandPort useCase = useCaseFor(session);
        final UUID sessionId = session.sessionId();

        assertThatExceptionOfType(PlayerNotRecognisedException.class)
                .isThrownBy(() -> useCase.execute(sessionId, null));

        assertThat(handRepository.sessionsAsked()).isEmpty();
    }

    @Test
    @DisplayName("refuses an unknown session before reading a single hand")
    void shouldRefuseAnUnknownSession() {
        final GameSession session = seatedTable();
        handRepository.seededWith(dealTo(session, SEATS, Rank.TWO, Rank.THREE, Rank.FOUR), 0);
        final ReadOwnHandPort useCase = useCaseFor(session);
        final UUID stranger = UUID.fromString("00000000-0000-7000-8000-00000000dead");

        assertThatExceptionOfType(SessionNotFoundException.class)
                .isThrownBy(() -> useCase.execute(stranger, tokenForSeat(0)));

        assertThat(handRepository.sessionsAsked()).isEmpty();
    }

    /**
     * A seat the deal never reached.
     *
     * <p>No legal sequence of calls produces this: the deal writes every seat's hand in one
     * transaction. It is reachable only as a partial write, and the guard exists so that case comes
     * out as a refusal naming the session rather than as {@code Hands#handOf} throwing an
     * {@link IllegalArgumentException} that would surface as a server fault.
     *
     * <p>The table is one seat larger than the rest of this class uses, because {@link Hands#deal}
     * refuses to deal to fewer than three seats. Four players dealt to three is the smallest partial
     * deal the domain will construct at all.
     */
    @Test
    @DisplayName("refuses a seat that was never dealt a hand")
    void shouldRefuseASeatWithNoHand() {
        final GameSession session = aSession()
                .withPlayerCount(LARGER_TABLE)
                .withStatus(SessionStatus.IN_PROGRESS)
                .build();
        handRepository.seededWith(dealTo(session, UNDEALT_SEAT, Rank.TWO, Rank.THREE, Rank.FOUR), 0);
        final ReadOwnHandPort useCase = useCaseFor(session);
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
                .isThrownBy(() -> new ReadOwnHandService(null, handRepository))
                .withMessageContaining("resolvePlayerPort");
        assertThatNullPointerException()
                .isThrownBy(() -> new ReadOwnHandService(resolver, null))
                .withMessageContaining("handRepository");
    }
}
