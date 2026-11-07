package org.maglez.eop.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.maglez.eop.domain.GameSessionBuilder.aSession;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.maglez.eop.domain.Card;
import org.maglez.eop.domain.DeckFixture;
import org.maglez.eop.domain.GameSession;
import org.maglez.eop.domain.SessionStatus;
import org.maglez.eop.domain.TooFewPlayersException;
import org.maglez.eop.application.port.in.NewGamePort;
import org.maglez.eop.application.port.in.ResolvePlayerPort;
import org.maglez.eop.application.port.out.HandRepository;
import org.maglez.eop.application.port.out.InMemoryCardRepository;
import org.maglez.eop.application.port.out.InMemoryHandRepository;
import org.maglez.eop.application.port.out.QueuedIdentifierGenerator;
import org.maglez.eop.application.port.out.RecordingSessionEventPublisher;
import org.maglez.eop.domain.GameSessionBuilder;

/**
 * Tests {@link HandDealer} directly, which is deliberately a narrower brief than it looks.
 *
 * <p>The shuffle order, the one-identifier-per-seat arithmetic, the card-free announcement and the
 * ordering of write before announcement are all asserted against {@link DealHandsServiceTest} and
 * {@link NewGameServiceTest}, which drive this class through their own ports. Repeating them here
 * would restate the same expectations against the same doubles, so this class asserts only what is
 * true of the dealer and of neither caller.
 *
 * <p>Two of those are load-bearing rather than incidental. The first is that the dealer can be
 * driven with no authorisation collaborator at all: it holds no {@link ResolvePlayerPort}, so a
 * test that deals without presenting a token proves the obligation really does sit with the caller
 * (ADR-024) rather than being quietly discharged in here. The second is that it never reads the
 * session's status. {@link NewGamePort} resets a completed session to in progress through a port
 * and then hands over the session object it resolved <em>before</em> that write, so that object
 * still reads {@link SessionStatus#COMPLETED}; if the dealer ever grew a status pre-check, the new
 * game would refuse itself. Asserting a deal from a completed session is what stops that being
 * introduced silently — the refusal it would replace lives in the database, where
 * {@link HandRepository#recordDeal} writes the opening leader seat only where none is recorded
 * (ADR-020).
 */
@DisplayName("HandDealer")
class HandDealerTest {

    private static final Instant NOW = Instant.parse("2026-02-01T09:45:00Z");

    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final UUID FIRST_HAND = UUID.fromString("00000000-0000-7000-8000-0000000000e0");

    private static final UUID SECOND_HAND = UUID.fromString("00000000-0000-7000-8000-0000000000e1");

    private static final UUID THIRD_HAND = UUID.fromString("00000000-0000-7000-8000-0000000000e2");

    private static final int SEATS = 3;

    /** The whole printed deck is dealt and nothing is discarded (ADR-023 Decision 1, EOP-92). */
    private static final int DECK_SIZE = 68;

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
    @DisplayName("deals a seated table without being handed anyone to authorise")
    void shouldDealWithoutAnAuthorisationCollaborator() {
        final var session = seatedTable(SEATS, SessionStatus.IN_PROGRESS);

        dealer().deal(session);

        assertThat(handRepository.recordDealCalls()).isOne();
        assertThat(handRepository.recordedAt()).isEqualTo(NOW);
        assertThat(handRepository.recordedHands().totalCards()).isEqualTo(DECK_SIZE);
        assertThat(order).containsExactly("shuffle", "recordDeal", "publish");
    }

    @Test
    @DisplayName("deals from a session that still reads COMPLETED, because it never reads the status")
    void shouldDealFromASessionThatStillReadsCompleted() {
        final var session = seatedTable(SEATS, SessionStatus.COMPLETED);

        dealer().deal(session);

        assertThat(handRepository.recordDealCalls())
                .as("a status pre-check here would refuse the new game NewGamePort has already reset")
                .isOne();
        assertThat(handRepository.recordedHands().totalCards()).isEqualTo(DECK_SIZE);
    }

    @Test
    @DisplayName("refuses a table of two without so much as reading the deck")
    void shouldRefuseTooFewPlayers() {
        final var session = seatedTable(TWO_PLAYERS, SessionStatus.IN_PROGRESS);
        final var dealer = dealer();

        assertThatExceptionOfType(TooFewPlayersException.class)
                .isThrownBy(() -> dealer.deal(session))
                .satisfies(refusal -> {
                    assertThat(refusal.seated()).isEqualTo(TWO_PLAYERS);
                    assertThat(refusal.required()).isEqualTo(GameSession.MINIMUM_PLAYERS_TO_START);
                });

        assertThat(shuffler.calls()).isZero();
        assertThat(identifiers.issued()).isZero();
        assertThat(handRepository.recordDealCalls()).isZero();
        assertThat(publisher.published()).isEmpty();
    }

    @Test
    @DisplayName("rejects a null session")
    void shouldRejectANullSession() {
        final var dealer = dealer();

        assertThatNullPointerException()
                .isThrownBy(() -> dealer.deal(null))
                .withMessageContaining("session");
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("rejects every null collaborator by name")
        void shouldRejectNullCollaborators() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new HandDealer(null, shuffler, handRepository, identifiers, publisher, FIXED))
                    .withMessageContaining("cardRepository");
            assertThatNullPointerException()
                    .isThrownBy(() -> new HandDealer(cardRepository, null, handRepository, identifiers, publisher, FIXED))
                    .withMessageContaining("deckShuffler");
            assertThatNullPointerException()
                    .isThrownBy(() -> new HandDealer(cardRepository, shuffler, null, identifiers, publisher, FIXED))
                    .withMessageContaining("handRepository");
            assertThatNullPointerException()
                    .isThrownBy(() -> new HandDealer(cardRepository, shuffler, handRepository, null, publisher, FIXED))
                    .withMessageContaining("identifierGenerator");
            assertThatNullPointerException()
                    .isThrownBy(() -> new HandDealer(cardRepository, shuffler, handRepository, identifiers, null, FIXED))
                    .withMessageContaining("sessionEventPublisher");
            assertThatNullPointerException()
                    .isThrownBy(() -> new HandDealer(cardRepository, shuffler, handRepository, identifiers, publisher, null))
                    .withMessageContaining("clock");
        }
    }

    private static GameSession seatedTable(final int players, final SessionStatus status) {
        return aSession().withPlayerCount(players).withStatus(status).build();
    }

    private HandDealer dealer() {
        return new HandDealer(cardRepository, shuffler, handRepository, identifiers, publisher, FIXED);
    }
}
