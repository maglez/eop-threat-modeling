package org.maglez.eop.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.maglez.eop.domain.GameSessionBuilder.aSession;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.domain.Card;
import org.maglez.eop.domain.DeckFixture;
import org.maglez.eop.domain.GameSession;
import org.maglez.eop.domain.NotFacilitatorException;
import org.maglez.eop.domain.PlayerBuilder;
import org.maglez.eop.domain.PlayerNotRecognisedException;
import org.maglez.eop.domain.SessionStatus;
import org.maglez.eop.domain.TooFewPlayersException;
import org.maglez.eop.application.port.in.DealHandsPort;
import org.maglez.eop.application.port.in.SessionEventType;
import org.maglez.eop.application.port.out.InMemoryCardRepository;
import org.maglez.eop.application.port.out.InMemoryHandRepository;
import org.maglez.eop.application.port.out.InMemorySessionRepository;
import org.maglez.eop.application.port.out.QueuedIdentifierGenerator;
import org.maglez.eop.application.port.out.RecordingSessionEventPublisher;
import org.maglez.eop.domain.GameSessionBuilder;

/**
 * Tests {@link DealHandsPort}.
 *
 * <p>Three assertions here earn their keep beyond the happy path. The first is that a refusal writes
 * nothing: the port's conditional write is what makes a deal happen once, but it cannot help if the
 * use case calls it for a player who was never entitled to deal, so every refusal test checks the
 * call count as well as the exception. The second is that the dealt order is the shuffled one. The
 * shuffler double reverses the deck rather than handing it back, so a use case that read the deck and
 * forgot to shuffle would fail rather than quietly deal a canonical hand every game. The third is
 * that the announcement follows the write rather than preceding it, asserted through the shared
 * interaction log, because a client told to re-read its hand before the hand exists would read
 * nothing and have no second prompt to try again.
 *
 * <p>The deck is seeded in canonical order because that is what the port promises, and the hand
 * identifiers are queued rather than random so the test can state which one belongs to which seat.
 * Tokens are never written as literals here: the facilitator presents
 * {@link PlayerBuilder#DEFAULT_TOKEN} and a participant presents the same token suffixed with their
 * seat, which is how {@link PlayerBuilder} derives the digest it seats them with.
 */
@DisplayName("DealHandsPort")
class DealHandsServiceTest {

    private static final Instant NOW = Instant.parse("2026-02-01T09:45:00Z");

    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final UUID FIRST_HAND = UUID.fromString("00000000-0000-7000-8000-0000000000d0");

    private static final UUID SECOND_HAND = UUID.fromString("00000000-0000-7000-8000-0000000000d1");

    private static final UUID THIRD_HAND = UUID.fromString("00000000-0000-7000-8000-0000000000d2");

    private static final int SEATS = 3;

    /**
     * The whole printed deck is dealt and nothing is discarded (ADR-023 Decision 1, reinstated by
     * EOP-92), so with 68 cards and 3 seats the deal is 23, 23, 22 — the surplus falling to the lowest
     * seats as a consequence of the round-robin order.
     */
    private static final int DECK_SIZE = 68;

    private static final int CARDS_AT_FIRST_SEAT = 23;

    private static final int CARDS_AT_LAST_SEAT = 22;

    private static final int TWO_PLAYERS = 2;

    private final List<String> order = new ArrayList<>();

    private final InMemoryHandRepository handRepository = new InMemoryHandRepository(order);

    private final RecordingDeckShuffler shuffler = new RecordingDeckShuffler(order);

    private final InMemoryCardRepository cardRepository =
            new InMemoryCardRepository(DeckFixture.fullDeck().toArray(new Card[0]));

    private final QueuedIdentifierGenerator identifiers =
            new QueuedIdentifierGenerator(FIRST_HAND, SECOND_HAND, THIRD_HAND);

    private final RecordingSessionEventPublisher publisher = new RecordingSessionEventPublisher(order);

    @Test
    @DisplayName("deals the whole deck and records the leader the deal itself decided")
    void shouldDealTheWholeDeck() {
        final var session = seatedTable(SEATS);

        useCaseFor(session).execute(session.sessionId(), PlayerBuilder.DEFAULT_TOKEN);

        assertThat(handRepository.recordDealCalls()).isOne();
        assertThat(handRepository.recordedAt()).isEqualTo(NOW);
        assertThat(handRepository.recordedHands().totalCards()).isEqualTo(DECK_SIZE);
        assertThat(handRepository.recordedHands().seats()).containsExactly(0, 1, 2);
        assertThat(handRepository.recordedHands().handOf(0).size()).isEqualTo(CARDS_AT_FIRST_SEAT);
        assertThat(handRepository.recordedHands().handOf(2).size()).isEqualTo(CARDS_AT_LAST_SEAT);
        assertThat(handRepository.recordedLeaderSeat())
                .isEqualTo(handRepository.recordedHands().openingLeaderSeat());
        assertThat(order).containsExactly("shuffle", "recordDeal", "publish");
    }

    /**
     * A hand keeps its cards in canonical order rather than in the order they were dealt, so the
     * dealt sequence cannot be read back off one. What can be read back is <em>which</em> cards
     * landed at a seat, and that is enough to pin the whole permutation: the deal is round robin
     * over three seats, so seat zero takes every third card of whatever order it was handed. Under
     * the canonical order that is every index divisible by three; under the reversed order it is
     * every index two above a multiple of three. The two sets are disjoint, and asserting the
     * whole set rather than a card from each end also refuses a shuffle that moved only some of
     * the deck.
     *
     * <p>Every card is dealt (EOP-92), so the loop runs to the front of the canonical deck rather than
     * stopping short of a discarded tail. Seat zero takes reversed-deck indices 0, 3, 6 … which are
     * canonical indices 67, 64, 61 … 1 — the 23 indices congruent to 1 modulo 3. Canonical index 0 is
     * not among them, which is what makes the final assertion discriminate a deal of the unshuffled
     * order.
     */
    @Test
    @DisplayName("deals the shuffled order, not the order the deck was read in")
    void shouldDealTheShuffledOrder() {
        final var session = seatedTable(SEATS);
        final var canonical = DeckFixture.fullDeck();
        final var expectedAtSeatZero = new ArrayList<Card>();
        for (int index = canonical.size() - 1; index >= 0; index -= SEATS) {
            expectedAtSeatZero.add(canonical.get(index));
        }

        useCaseFor(session).execute(session.sessionId(), PlayerBuilder.DEFAULT_TOKEN);

        assertThat(shuffler.calls()).isOne();
        assertThat(shuffler.received()).isEqualTo(canonical);
        assertThat(expectedAtSeatZero)
                .as("the reversed deal gives the first seat its share of the whole deck (%d of %d cards)",
                        CARDS_AT_FIRST_SEAT, DECK_SIZE)
                .hasSize(CARDS_AT_FIRST_SEAT);
        assertThat(handRepository.recordedHands().handOf(0).cards())
                .as("every card at the first seat is one the reversed order would have put there")
                .containsExactlyInAnyOrderElementsOf(expectedAtSeatZero)
                .as("dealing the canonical order would have put the deck's first card there instead")
                .doesNotContain(canonical.get(0));
    }

    @Test
    @DisplayName("mints exactly one hand identifier per seat")
    void shouldMintOneHandIdentifierPerSeat() {
        final var session = seatedTable(SEATS);

        useCaseFor(session).execute(session.sessionId(), PlayerBuilder.DEFAULT_TOKEN);

        assertThat(identifiers.issued()).isEqualTo(SEATS);
        assertThat(handRepository.recordedHands().handsBySeat().values())
                .extracting(hand -> hand.handId())
                .containsExactly(FIRST_HAND, SECOND_HAND, THIRD_HAND);
    }

    @Test
    @DisplayName("refuses a participant, and writes nothing while refusing")
    void shouldRefuseAParticipant() {
        final var session = seatedTable(SEATS);
        final var useCase = useCaseFor(session);
        final var participant = tokenForSeat(1);

        assertThatExceptionOfType(NotFacilitatorException.class)
                .isThrownBy(() -> useCase.execute(session.sessionId(), participant));

        assertThat(handRepository.recordDealCalls()).isZero();
        assertThat(shuffler.calls()).isZero();
    }

    @Test
    @DisplayName("refuses a stranger, and writes nothing while refusing")
    void shouldRefuseAStranger() {
        final var session = seatedTable(SEATS);
        final var useCase = useCaseFor(session);

        assertThatExceptionOfType(PlayerNotRecognisedException.class)
                .isThrownBy(() -> useCase.execute(session.sessionId(), "not-a-seated-player"));

        assertThat(handRepository.recordDealCalls()).isZero();
        assertThat(shuffler.calls()).isZero();
    }

    @Test
    @DisplayName("refuses a table of two without so much as reading the deck")
    void shouldRefuseTooFewPlayers() {
        final var session = seatedTable(TWO_PLAYERS);
        final var useCase = useCaseFor(session);

        assertThatExceptionOfType(TooFewPlayersException.class)
                .isThrownBy(() -> useCase.execute(session.sessionId(), PlayerBuilder.DEFAULT_TOKEN))
                .satisfies(refusal -> {
                    assertThat(refusal.seated()).isEqualTo(TWO_PLAYERS);
                    assertThat(refusal.required()).isEqualTo(GameSession.MINIMUM_PLAYERS_TO_START);
                });

        assertThat(shuffler.calls()).isZero();
        assertThat(identifiers.issued()).isZero();
        assertThat(handRepository.recordDealCalls()).isZero();
    }

    @Test
    @DisplayName("announces the deal once the hands are written, and says nothing about them")
    void shouldAnnounceTheDealAfterTheWrite() {
        final var session = seatedTable(SEATS);

        useCaseFor(session).execute(session.sessionId(), PlayerBuilder.DEFAULT_TOKEN);

        assertThat(publisher.published()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(SessionEventType.HAND_DEALT);
            assertThat(event.sessionId()).isEqualTo(session.sessionId());
            assertThat(event.occurredAt()).isEqualTo(NOW);
        });
        assertThat(order).containsSubsequence("recordDeal", "publish");
    }

    @Test
    @DisplayName("announces nothing when the deal is refused")
    void shouldAnnounceNothingWhenTheDealIsRefused() {
        final var session = seatedTable(TWO_PLAYERS);
        final var useCase = useCaseFor(session);

        assertThatExceptionOfType(TooFewPlayersException.class)
                .isThrownBy(() -> useCase.execute(session.sessionId(), PlayerBuilder.DEFAULT_TOKEN));

        assertThat(publisher.published()).isEmpty();
    }

    private static GameSession seatedTable(final int players) {
        return aSession().withPlayerCount(players).withStatus(SessionStatus.IN_PROGRESS).build();
    }

    private static String tokenForSeat(final int seat) {
        return PlayerBuilder.DEFAULT_TOKEN + "-" + seat;
    }

    private DealHandsPort useCaseFor(final GameSession session) {
        final var sessionRepository = new InMemorySessionRepository(order, session);
        return new DealHandsService(
                new ResolvePlayerService(sessionRepository, java.time.Clock.systemUTC()),
                new HandDealer(
                        cardRepository, shuffler, handRepository, identifiers, publisher, FIXED));
    }
}
