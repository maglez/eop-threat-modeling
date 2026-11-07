package org.maglez.eop.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.maglez.eop.domain.GameSessionBuilder.aSession;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.domain.Card;
import org.maglez.eop.domain.CardNotFoundException;
import org.maglez.eop.domain.CardNotInHandException;
import org.maglez.eop.domain.DeckFixture;
import org.maglez.eop.domain.GameSession;
import org.maglez.eop.domain.HandCompleteException;
import org.maglez.eop.domain.HandNotDealtException;
import org.maglez.eop.domain.Hands;
import org.maglez.eop.domain.OutOfTurnException;
import org.maglez.eop.domain.Player;
import org.maglez.eop.domain.PlayerBuilder;
import org.maglez.eop.domain.PlayerNotInSessionException;
import org.maglez.eop.domain.PlayerNotRecognisedException;
import org.maglez.eop.domain.SessionStatus;
import org.maglez.eop.domain.Trick;
import org.maglez.eop.domain.TrickPlay;
import org.maglez.eop.application.port.in.PlayCardCommand;
import org.maglez.eop.application.port.in.PlayCardPort;
import org.maglez.eop.application.port.in.SessionEventType;
import org.maglez.eop.application.port.out.IdentifierGenerator;
import org.maglez.eop.application.port.out.InMemoryCardRepository;
import org.maglez.eop.application.port.out.InMemoryHandRepository;
import org.maglez.eop.application.port.out.InMemorySessionRepository;
import org.maglez.eop.application.port.out.InMemoryTrickRepository;
import org.maglez.eop.application.port.out.QueuedIdentifierGenerator;
import org.maglez.eop.application.port.out.RecordingSessionEventPublisher;
import org.maglez.eop.domain.GameSessionBuilder;

/**
 * Exercises {@link PlayCardPort} against hand written doubles for both trick play ports.
 *
 * <p>Four groups of assertion earn their keep here. The first is that a refusal writes nothing,
 * and in particular that it opens no trick: this use case is the one place that opens a trick, it
 * does so before the play is accepted, and an open trick with no plays in it is a state no caller
 * asked for and no later slice knows how to clear. That is why every refusal test asserts on
 * {@code opened()} being empty rather than only on the exception type.
 *
 * <p>The second is that the compare-and-set witness handed to both writes is the leader seat this
 * use case <em>read</em>, never a value derived from the command. The command cannot carry one, so
 * the assertion is really about the use case not inventing one; passing the acting seat instead
 * would let a follower's write masquerade as the leader's (ADR-020).
 *
 * <p>The third is that the play which reaches the port carries the card <em>as dealt</em>. The
 * command names only an identifier, the deck supplies suit and rank, and the hand confirms
 * possession; a play persisted from the command rather than from
 * {@link Trick#acceptPlay(int, TrickPlay, Hands)} would be the card forgery defect returning.
 *
 * <p>The fourth is that the announcement follows the write and carries none of the play. A
 * subscriber prompted to re-read the table before the play was stored would read the table it
 * already had and would get no second prompt, so the shared interaction log is asserted rather
 * than only the event; and a refused play announces nothing at all, because a client that re-reads
 * on every notification would otherwise be sent looking for a change that never happened.
 *
 * <p>The opening leader is derived from the deal rather than written down, because which seat holds
 * the lowest Tampering card is a property of the deck and the seat count, and a hard coded seat
 * would silently stop meaning anything if either changed.
 */
@DisplayName("PlayCardPort")
class PlayCardServiceTest {

    private static final Instant NOW = Instant.parse("2026-02-01T09:45:00Z");

    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final UUID TRICK_ID = UUID.fromString("00000000-0000-7000-8000-0000000000e0");

    private static final UUID PLAY_ID = UUID.fromString("00000000-0000-7000-8000-0000000000e1");

    private static final UUID SECOND_PLAY_ID = UUID.fromString("00000000-0000-7000-8000-0000000000e2");

    private static final UUID SECOND_TRICK_ID = UUID.fromString("00000000-0000-7000-8000-0000000000e3");

    private static final long HAND_PREFIX = 900L;

    private static final long PLAY_PREFIX = 910L;

    private static final int SEATS = 3;

    private static final int FIRST_SEQUENCE = 1;

    private static final int SECOND_SEQUENCE = 2;

    private final List<String> order = new ArrayList<>();

    private final InMemoryHandRepository handRepository = new InMemoryHandRepository(order);

    private final InMemoryTrickRepository trickRepository = new InMemoryTrickRepository(order);

    private final InMemoryCardRepository cardRepository =
            new InMemoryCardRepository(DeckFixture.fullDeck().toArray(new Card[0]));

    private final QueuedIdentifierGenerator identifiers = new QueuedIdentifierGenerator(TRICK_ID, PLAY_ID);

    private final RecordingSessionEventPublisher publisher = new RecordingSessionEventPublisher(order);

    @Test
    @DisplayName("opens the first trick on the recorded leader seat and appends the lead")
    void shouldOpenTheFirstTrickAndAppendTheLead() {
        final var session = seatedTable(SEATS);
        final var hands = dealTo(session, SEATS);
        final var leaderSeat = hands.openingLeaderSeat();
        final var leader = playerAt(session, leaderSeat);
        final var lead = hands.handOf(leaderSeat).cards().getFirst();
        handRepository.seededWith(hands, leaderSeat);

        final var played = useCaseFor(session)
                .execute(commandFor(session, leaderSeat, lead.cardId()));

        assertThat(order).containsExactly("openTrick", "appendPlay", "publish");
        assertThat(trickRepository.opened()).hasSize(1);
        assertThat(trickRepository.opened().getFirst().trick().sequence()).isEqualTo(FIRST_SEQUENCE);
        assertThat(trickRepository.opened().getFirst().trick().leaderSeat()).isEqualTo(leaderSeat);
        assertThat(trickRepository.opened().getFirst().expectedLeaderSeat()).isEqualTo(leaderSeat);
        assertThat(trickRepository.opened().getFirst().occurredAt()).isEqualTo(NOW);

        assertThat(trickRepository.appended()).hasSize(1);
        final var appended = trickRepository.appended().getFirst();
        assertThat(appended.trickId()).isEqualTo(TRICK_ID);
        assertThat(appended.expectedLeaderSeat()).isEqualTo(leaderSeat);
        assertThat(appended.play().trickPlayId()).isEqualTo(PLAY_ID);
        assertThat(appended.play().seatOrder()).isEqualTo(leaderSeat);
        assertThat(appended.play().playerId()).isEqualTo(leader.playerId());
        assertThat(appended.play().card()).isEqualTo(lead);
        assertThat(appended.play().playedAt()).isEqualTo(NOW);

        assertThat(played.plays()).hasSize(1);
        assertThat(played.ledSuit()).contains(lead.suit());
    }

    @Test
    @DisplayName("appends to a trick already under way without opening a second one")
    void shouldAppendToATrickAlreadyUnderWay() {
        final var session = seatedTable(SEATS);
        final var dealt = dealTo(session, SEATS);
        final var leaderSeat = dealt.openingLeaderSeat();
        final var leader = playerAt(session, leaderSeat);
        final var lead = dealt.handOf(leaderSeat).cards().getFirst();

        final var underWay = Trick.open(TRICK_ID, FIRST_SEQUENCE, leaderSeat)
                .acceptPlay(leaderSeat, playOf(PLAY_ID, leader, lead), dealt);
        final var remaining = dealt.withCardPlayed(leaderSeat, lead);
        final var followerSeat = underWay.seatToPlay(remaining.seatsHoldingCards()).orElseThrow();
        final var follow = remaining.handOf(followerSeat).lowestOf(lead.suit()).orElseThrow();
        handRepository.seededWith(remaining, leaderSeat);
        trickRepository.seededWith(underWay);

        final var played = useCaseWith(session, new QueuedIdentifierGenerator(SECOND_PLAY_ID))
                .execute(commandFor(session, followerSeat, follow.cardId()));

        assertThat(order).containsExactly("appendPlay", "publish");
        assertThat(trickRepository.opened()).isEmpty();
        assertThat(trickRepository.appended()).hasSize(1);
        final var appended = trickRepository.appended().getFirst();
        assertThat(appended.trickId()).isEqualTo(TRICK_ID);
        assertThat(appended.expectedLeaderSeat()).isEqualTo(leaderSeat);
        assertThat(appended.play().trickPlayId()).isEqualTo(SECOND_PLAY_ID);
        assertThat(appended.play().seatOrder()).isEqualTo(followerSeat);
        assertThat(appended.play().card()).isEqualTo(follow);
        assertThat(played.plays()).hasSize(2);
    }

    /**
     * The trick after the first one, which is where the sequence number stops being a constant.
     *
     * <p>Every other test here plays into the opening trick, where the sequence is one whether it
     * was derived or hard coded. This one resolves a complete first trick and has its winner lead
     * again, so the number written to the second trick can only be right if it was taken from the
     * first. An implementation that always wrote one would leave two tricks claiming to be the
     * opening trick, which the unique constraint on sequence would then refuse at the database, a
     * failure no test above the port would explain.
     *
     * <p>It also pins the seat the next trick opens on. The recorded leader seat has moved to the
     * winner by the time this runs, so passing it back as the compare-and-set witness is what lets
     * the write succeed; a use case that reached for the old leader would be refused by the port
     * for a reason that has nothing to do with the caller.
     */
    @Test
    @DisplayName("opens the next trick on the seat that won the last one, numbered after it")
    void shouldOpenTheNextTrickAfterTheFirstIsResolved() {
        final var session = seatedTable(SEATS);
        final var dealt = dealTo(session, SEATS);
        final var openingSeat = dealt.openingLeaderSeat();
        final var lead = dealt.handOf(openingSeat).cards().getFirst();

        var trick = Trick.open(TRICK_ID, FIRST_SEQUENCE, openingSeat)
                .acceptPlay(openingSeat, playOf(PLAY_ID, playerAt(session, openingSeat), lead), dealt);
        var remaining = dealt.withCardPlayed(openingSeat, lead);
        while (!trick.isComplete(remaining.seatsHoldingCards())) {
            final var seat = trick.seatToPlay(remaining.seatsHoldingCards()).orElseThrow();
            final var follow = remaining.handOf(seat).lowestOf(lead.suit()).orElseThrow();
            final var play = playOf(new UUID(PLAY_PREFIX, seat), playerAt(session, seat), follow);
            trick = trick.acceptPlay(seat, play, remaining);
            remaining = remaining.withCardPlayed(seat, follow);
        }

        final var resolved = trick.resolved();
        final var winnerSeat = resolved.winningSeat();
        final var nextLead = remaining.handOf(winnerSeat).cards().getFirst();
        handRepository.seededWith(remaining, winnerSeat);
        trickRepository.seededWith(resolved);

        final var played = useCaseWith(session, new QueuedIdentifierGenerator(SECOND_TRICK_ID, SECOND_PLAY_ID))
                .execute(commandFor(session, winnerSeat, nextLead.cardId()));

        assertThat(order).containsExactly("openTrick", "appendPlay", "publish");
        assertThat(trickRepository.opened()).hasSize(1);

        final var opened = trickRepository.opened().getFirst();
        assertThat(opened.trick().sequence())
                .as("the second trick is numbered from the first, not from a constant")
                .isEqualTo(SECOND_SEQUENCE);
        assertThat(opened.trick().trickId()).isEqualTo(SECOND_TRICK_ID);
        assertThat(opened.trick().leaderSeat())
                .as("the winner of the last trick leads this one")
                .isEqualTo(winnerSeat);
        assertThat(opened.expectedLeaderSeat())
                .as("the witness is the leader seat as recorded after the resolution")
                .isEqualTo(winnerSeat);
        assertThat(trickRepository.appended()).hasSize(1);
        assertThat(trickRepository.appended().getFirst().trickId()).isEqualTo(SECOND_TRICK_ID);
        assertThat(played.plays()).hasSize(1);
    }

    @Test
    @DisplayName("refuses a card the deck does not know, and opens no trick")
    void shouldRefuseACardTheDeckDoesNotKnow() {
        final var session = seatedTable(SEATS);
        final var hands = dealTo(session, SEATS);
        final var leaderSeat = hands.openingLeaderSeat();
        handRepository.seededWith(hands, leaderSeat);
        final var useCase = useCaseFor(session);
        final var unknown = UUID.fromString("00000000-0000-7000-8000-00000000dead");

        assertThatExceptionOfType(CardNotFoundException.class)
                .isThrownBy(() -> useCase.execute(commandFor(session, leaderSeat, unknown)));

        assertThat(trickRepository.opened()).isEmpty();
        assertThat(trickRepository.appended()).isEmpty();
        assertThat(identifiers.issued()).isZero();
    }

    @Test
    @DisplayName("refuses a card held by another seat, and opens no trick while refusing")
    void shouldRefuseACardHeldByAnotherSeat() {
        final var session = seatedTable(SEATS);
        final var hands = dealTo(session, SEATS);
        final var leaderSeat = hands.openingLeaderSeat();
        final var elsewhere = hands.handOf(nextSeat(leaderSeat)).cards().getFirst();
        handRepository.seededWith(hands, leaderSeat);
        final var useCase = useCaseFor(session);

        assertThatExceptionOfType(CardNotInHandException.class)
                .isThrownBy(() -> useCase.execute(commandFor(session, leaderSeat, elsewhere.cardId())));

        assertThat(order).isEmpty();
        assertThat(trickRepository.opened()).isEmpty();
        assertThat(trickRepository.appended()).isEmpty();
    }

    @Test
    @DisplayName("refuses a player who is not the one to lead, and opens no trick while refusing")
    void shouldRefuseAPlayerWhoIsNotTheOneToLead() {
        final var session = seatedTable(SEATS);
        final var hands = dealTo(session, SEATS);
        final var leaderSeat = hands.openingLeaderSeat();
        final var impatientSeat = nextSeat(leaderSeat);
        final var own = hands.handOf(impatientSeat).cards().getFirst();
        handRepository.seededWith(hands, leaderSeat);
        final var useCase = useCaseFor(session);

        assertThatExceptionOfType(OutOfTurnException.class)
                .isThrownBy(() -> useCase.execute(commandFor(session, impatientSeat, own.cardId())))
                .satisfies(refusal -> {
                    assertThat(refusal.expectedSeat()).isEqualTo(leaderSeat);
                    assertThat(refusal.attemptedSeat()).isEqualTo(impatientSeat);
                });

        assertThat(order).isEmpty();
        assertThat(trickRepository.opened()).isEmpty();
        assertThat(identifiers.issued()).isZero();
    }

    @Test
    @DisplayName("refuses a seated player who was dealt no hand")
    void shouldRefuseASeatedPlayerWithNoHand() {
        final var session = seatedTable(SEATS + 1);
        final var hands = dealTo(session, SEATS);
        final var unseatedInTheDeal = SEATS;
        final var someCard = hands.handOf(hands.openingLeaderSeat()).cards().getFirst();
        handRepository.seededWith(hands, hands.openingLeaderSeat());
        final var useCase = useCaseFor(session);

        assertThatExceptionOfType(PlayerNotInSessionException.class)
                .isThrownBy(() -> useCase.execute(commandFor(session, unseatedInTheDeal, someCard.cardId())));

        assertThat(trickRepository.opened()).isEmpty();
        assertThat(trickRepository.appended()).isEmpty();
    }

    @Test
    @DisplayName("refuses a play before the deal has happened")
    void shouldRefuseAPlayBeforeTheDeal() {
        final var session = seatedTable(SEATS);
        final var someCard = DeckFixture.fullDeck().getFirst();
        final var useCase = useCaseFor(session);

        assertThatExceptionOfType(HandNotDealtException.class)
                .isThrownBy(() -> useCase.execute(commandFor(session, 0, someCard.cardId())));

        assertThat(trickRepository.opened()).isEmpty();
        assertThat(trickRepository.appended()).isEmpty();
    }

    /**
     * The guard on a state the deal cannot leave behind.
     *
     * <p>{@code recordDeal} writes the hands and the opening leader seat in one transaction, so a
     * session holding hands with no leader recorded is not reachable through any sequence of legal
     * calls. The use case still refuses it rather than unwrapping an empty optional, because the
     * alternative is a failure inside the domain with nothing to say about which of the two reads
     * disagreed. Seeded here through a double that can express the state the database cannot.
     */
    @Test
    @DisplayName("refuses a play when hands exist but no leader seat is recorded")
    void shouldRefuseWhenNoLeaderSeatIsRecorded() {
        final var session = seatedTable(SEATS);
        final var dealt = dealTo(session, SEATS);
        final var seat = dealt.openingLeaderSeat();
        final var card = dealt.handOf(seat).cards().getFirst();
        handRepository.seededWithNoLeader(dealt);
        final var useCase = useCaseFor(session);
        final var command = commandFor(session, seat, card.cardId());

        assertThatExceptionOfType(HandNotDealtException.class)
                .isThrownBy(() -> useCase.execute(command));

        assertThat(trickRepository.opened())
                .as("a refusal must not leave an open trick behind")
                .isEmpty();
        assertThat(trickRepository.appended()).isEmpty();
    }

    @Test
    @DisplayName("refuses a stranger without reading a single hand")
    void shouldRefuseAStranger() {
        final var session = seatedTable(SEATS);
        final var hands = dealTo(session, SEATS);
        handRepository.seededWith(hands, hands.openingLeaderSeat());
        final var someCard = hands.handOf(hands.openingLeaderSeat()).cards().getFirst();
        final var useCase = useCaseFor(session);
        final var command = new PlayCardCommand(
                session.sessionId(), "not-a-seated-player", someCard.cardId(), false, List.of(), null);

        assertThatExceptionOfType(PlayerNotRecognisedException.class).isThrownBy(() -> useCase.execute(command));

        assertThat(handRepository.sessionsAsked()).isEmpty();
        assertThat(trickRepository.opened()).isEmpty();
        assertThat(trickRepository.appended()).isEmpty();
    }

    /**
     * Pins the order of the two statements a security review found the wrong way round.
     *
     * <p>The bounds on caller supplied text live in the play, not in the command: at most twenty
     * components, two hundred characters each, two thousand characters of notes, and no control or
     * bidirectional formatting characters. Nothing measures them until the play is constructed, so
     * the only thing standing between an over-long note and a committed trick row is which of those
     * two statements runs first. It used to be the write, which meant a note one character too long
     * left behind an open trick with no plays in it and then answered the caller with a refusal.
     *
     * <p>The note is the field asserted here because it is the largest, but the guarantee is about
     * the ordering rather than about notes, so any bound the play grows later is covered too.
     */
    @Test
    @DisplayName("refuses an over-long note without opening a trick")
    void shouldRefuseAnOverLongNoteWithoutOpeningATrick() {
        final var session = seatedTable(SEATS);
        final var dealt = dealTo(session, SEATS);
        final var leaderSeat = dealt.openingLeaderSeat();
        final var lead = dealt.handOf(leaderSeat).cards().getFirst();
        handRepository.seededWith(dealt, leaderSeat);

        final var overLong =
                new PlayCardCommand(
                        session.sessionId(),
                        tokenForSeat(leaderSeat),
                        lead.cardId(),
                        false,
                        List.of(),
                        "x".repeat(TrickPlay.MAX_NOTES_LENGTH + 1));

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> useCaseFor(session).execute(overLong));

        assertThat(trickRepository.opened())
                .as("a refusal must not leave an open trick nobody asked for")
                .isEmpty();
        assertThat(trickRepository.appended()).isEmpty();
        assertThat(order).isEmpty();
    }

    @Test
    @DisplayName("announces the play once it is appended, and says nothing about the card")
    void shouldAnnounceThePlayAfterTheWrite() {
        final var session = seatedTable(SEATS);
        final var hands = dealTo(session, SEATS);
        final var leaderSeat = hands.openingLeaderSeat();
        final var lead = hands.handOf(leaderSeat).cards().getFirst();
        handRepository.seededWith(hands, leaderSeat);

        useCaseFor(session).execute(commandFor(session, leaderSeat, lead.cardId()));

        assertThat(publisher.published())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.type()).isEqualTo(SessionEventType.CARD_PLAYED);
                    assertThat(event.sessionId()).isEqualTo(session.sessionId());
                    assertThat(event.occurredAt()).isEqualTo(NOW);
                });
        assertThat(order)
                .as("a subscriber told to re-read before the play is stored would read the table it already had")
                .containsSubsequence("appendPlay", "publish");
    }

    @Test
    @DisplayName("announces nothing when the play is refused")
    void shouldAnnounceNothingWhenThePlayIsRefused() {
        final var session = seatedTable(SEATS);
        final var hands = dealTo(session, SEATS);
        final var leaderSeat = hands.openingLeaderSeat();
        final var impatientSeat = nextSeat(leaderSeat);
        final var own = hands.handOf(impatientSeat).cards().getFirst();
        handRepository.seededWith(hands, leaderSeat);
        final var useCase = useCaseFor(session);

        assertThatExceptionOfType(OutOfTurnException.class)
                .isThrownBy(() -> useCase.execute(commandFor(session, impatientSeat, own.cardId())));

        assertThat(publisher.published()).isEmpty();
    }

    @Test
    @DisplayName("refuses a play into a hand that has been played out, by name")
    void shouldRefuseAPlayIntoASpentHand() {
        final var session = seatedTable(SEATS);
        final var dealt = dealTo(session, SEATS);
        final var leaderSeat = dealt.openingLeaderSeat();
        final var lead = dealt.handOf(leaderSeat).cards().getFirst();
        handRepository.seededWith(playedOut(dealt), leaderSeat);
        final var useCase = useCaseFor(session);

        assertThatExceptionOfType(HandCompleteException.class)
                .isThrownBy(() -> useCase.execute(commandFor(session, leaderSeat, lead.cardId())))
                .satisfies(refusal -> assertThat(refusal.sessionId()).isEqualTo(session.sessionId()));

        assertThat(trickRepository.opened()).isEmpty();
        assertThat(trickRepository.appended()).isEmpty();
        assertThat(publisher.published()).isEmpty();
        assertThat(identifiers.issued()).isZero();
    }

    @Test
    @DisplayName("auto-resolves the trick when the last card is played")
    void shouldAutoResolveWhenLastCardIsPlayed() {
        final var session = seatedTable(SEATS);
        final var dealt = dealTo(session, SEATS);
        final var openingSeat = dealt.openingLeaderSeat();
        final var lead = dealt.handOf(openingSeat).cards().getFirst();

        // Build a trick with all but the last play already in it
        var trick = Trick.open(TRICK_ID, FIRST_SEQUENCE, openingSeat)
                .acceptPlay(openingSeat, playOf(PLAY_ID, playerAt(session, openingSeat), lead), dealt);
        var remaining = dealt.withCardPlayed(openingSeat, lead);
        // Play all seats except the last one
        while (trick.seatToPlay(remaining.seatsHoldingCards()).isPresent()) {
            final var nextSeat = trick.seatToPlay(remaining.seatsHoldingCards()).orElseThrow();
            final var nextCard = remaining.handOf(nextSeat).lowestOf(lead.suit()).orElseThrow();
            final var nextPlay = playOf(new UUID(PLAY_PREFIX, nextSeat), playerAt(session, nextSeat), nextCard);
            final var afterPlay = trick.acceptPlay(nextSeat, nextPlay, remaining);
            remaining = remaining.withCardPlayed(nextSeat, nextCard);
            if (afterPlay.isComplete(remaining.seatsHoldingCards())) {
                // This is the last play — seed the trick one play before this
                break;
            }
            trick = afterPlay;
        }

        // Seed the trick with all-but-last plays, and the hands with all-but-last cards removed
        final var lastSeat = trick.seatToPlay(remaining.seatsHoldingCards()).orElseThrow();
        final var lastCard = remaining.handOf(lastSeat).lowestOf(lead.suit()).orElseThrow();
        handRepository.seededWith(remaining, openingSeat);
        trickRepository.seededWith(trick);

        useCaseWith(session, new QueuedIdentifierGenerator(new UUID(PLAY_PREFIX, lastSeat)))
                .execute(commandFor(session, lastSeat, lastCard.cardId()));

        assertThat(order).containsSubsequence("appendPlay", "publish", "recordResolution", "publish");
        assertThat(trickRepository.resolutions()).hasSize(1);
        final var resolution = trickRepository.resolutions().getFirst();
        assertThat(resolution.trick().winner()).isPresent();
        // TRICK_RESOLVED must follow CARD_PLAYED
        final var publishedTypes = publisher.published().stream()
                .map(e -> e.type())
                .toList();
        assertThat(publishedTypes).containsExactly(SessionEventType.CARD_PLAYED, SessionEventType.TRICK_RESOLVED);
    }

    @Test
    @DisplayName("auto-resolves and marks session completed when the last trick is played out")
    void shouldAutoResolveAndCompleteSessionWhenLastTrickIsPlayed() {
        // Build a session where only one card remains per seat (last trick of the hand)
        final var session = seatedTable(SEATS);
        final var dealt = dealTo(session, SEATS);
        final var openingSeat = dealt.openingLeaderSeat();

        // Play out all cards except one per seat (simulate last trick scenario)
        // For simplicity: leave exactly one card per seat in the hands
        var remaining = dealt;
        for (final int seat : dealt.seats()) {
            final var cards = dealt.handOf(seat).cards();
            // Remove all but the last card from each seat
            for (int i = 0; i < cards.size() - 1; i++) {
                remaining = remaining.withCardPlayed(seat, cards.get(i));
            }
        }

        // The opening leader for this last trick is the opening seat
        final var lead = remaining.handOf(openingSeat).cards().getFirst();
        var trick = Trick.open(TRICK_ID, FIRST_SEQUENCE, openingSeat)
                .acceptPlay(openingSeat, playOf(PLAY_ID, playerAt(session, openingSeat), lead), remaining);
        remaining = remaining.withCardPlayed(openingSeat, lead);

        // Play all but the last seat. Stop when only one seat remains to play (the last card).
        // We do NOT update remaining/trick when the next play would complete the trick, so that
        // trick.seatToPlay(remaining) still names the last seat after the loop.
        while (true) {
            final var nextSeat = trick.seatToPlay(remaining.seatsHoldingCards()).orElseThrow();
            final var nextCard = remaining.handOf(nextSeat).cards().getFirst();
            final var nextPlay = playOf(new UUID(PLAY_PREFIX, nextSeat), playerAt(session, nextSeat), nextCard);
            final var afterPlay = trick.acceptPlay(nextSeat, nextPlay, remaining);
            final var afterRemaining = remaining.withCardPlayed(nextSeat, nextCard);
            if (afterPlay.isComplete(afterRemaining.seatsHoldingCards())) {
                // nextSeat is the last seat — leave trick/remaining pointing at the pre-last-play state
                break;
            }
            trick = afterPlay;
            remaining = afterRemaining;
        }

        final var lastSeat = trick.seatToPlay(remaining.seatsHoldingCards()).orElseThrow();
        final var lastCard = remaining.handOf(lastSeat).cards().getFirst();
        handRepository.seededWith(remaining, openingSeat);
        trickRepository.seededWith(trick);

        useCaseWith(session, new QueuedIdentifierGenerator(new UUID(PLAY_PREFIX, lastSeat)))
                .execute(commandFor(session, lastSeat, lastCard.cardId()));

        assertThat(trickRepository.resolutions()).hasSize(1);
        // Session must be marked completed (nextLeaderSeat is empty when no cards remain)
        final var publishedTypes = publisher.published().stream()
                .map(e -> e.type())
                .toList();
        assertThat(publishedTypes).contains(SessionEventType.TRICK_RESOLVED);
        // recordCompleted is called on the session repository
        assertThat(order).containsSubsequence("recordResolution", "publish");
    }

    /**
     * Plays every dealt card, leaving a table whose hands are all empty.
     *
     * <p>The end of a hand is not something the doubles can be told about, because no port records
     * it: it is the absence of cards. Reaching it by playing the deck out rather than by handing
     * the double an empty map is deliberate, since a hand emptied one card at a time is the only
     * arrangement a real session can produce.
     *
     * @param dealt the hands as dealt
     * @return the same seats, holding nothing
     */
    private static Hands playedOut(final Hands dealt) {
        var remaining = dealt;
        for (final int seat : dealt.seats()) {
            for (final var card : dealt.handOf(seat).cards()) {
                remaining = remaining.withCardPlayed(seat, card);
            }
        }
        return remaining;
    }

    /**
     * Builds a table whose lobby has already closed, since a card cannot be played into a lobby.
     *
     * @param players how many seats to fill
     * @return a session in play
     */
    private static GameSession seatedTable(final int players) {
        return aSession().withPlayerCount(players).withStatus(SessionStatus.IN_PROGRESS).build();
    }

    /**
     * Deals the canonical deck to the first {@code seats} players of the session.
     *
     * <p>The deal is unshuffled on purpose: this use case never shuffles, and a predictable
     * distribution is what lets a test name a card that a particular seat is known to hold.
     *
     * @param session the table whose roster supplies the player identifiers
     * @param seats how many of its seats take part in the deal
     * @return the hands as the deal left them
     */
    private static Hands dealTo(final GameSession session, final int seats) {
        final var dealtSeats = session.players().stream()
                .sorted(Comparator.comparingInt(Player::seatOrder))
                .limit(seats)
                .map(player -> new Hands.Seat(
                        player.seatOrder(), player.playerId(), new UUID(HAND_PREFIX, player.seatOrder())))
                .toList();
        return Hands.deal(DeckFixture.fullDeck(), dealtSeats);
    }

    /**
     * Finds the player sitting at a seat.
     *
     * @param session the table
     * @param seatOrder the seat
     * @return the player seated there
     */
    private static Player playerAt(final GameSession session, final int seatOrder) {
        return session.players().stream()
                .filter(player -> player.seatOrder() == seatOrder)
                .findFirst()
                .orElseThrow();
    }

    /**
     * The next seat clockwise around a three handed table.
     *
     * @param seatOrder the seat to move on from
     * @return the seat to its left
     */
    private static int nextSeat(final int seatOrder) {
        return (seatOrder + 1) % SEATS;
    }

    /**
     * The plaintext token the player at a seat presents.
     *
     * <p>Seat zero is the facilitator, whose token the builder does not suffix; every other seat is
     * a participant whose token the builder derives from the seat number.
     *
     * @param seatOrder the seat presenting the token
     * @return the plaintext to put in a command
     */
    private static String tokenForSeat(final int seatOrder) {
        return seatOrder == 0
                ? PlayerBuilder.DEFAULT_TOKEN
                : PlayerBuilder.DEFAULT_TOKEN + "-" + seatOrder;
    }

    /**
     * Assembles the command a seat would send to play one card, linking no threat.
     *
     * @param session the table
     * @param seatOrder the seat playing
     * @param cardId the card it names
     * @return the command
     */
    private static PlayCardCommand commandFor(final GameSession session, final int seatOrder, final UUID cardId) {
        return new PlayCardCommand(session.sessionId(), tokenForSeat(seatOrder), cardId, false, List.of(), null);
    }

    /**
     * Builds a candidate play, used only to seed a trick that is already under way.
     *
     * @param playId the identifier the play carries
     * @param player the player making it
     * @param card the card played
     * @return the candidate
     */
    private static TrickPlay playOf(final UUID playId, final Player player, final Card card) {
        return new TrickPlay(playId, player.playerId(), player.seatOrder(), card, false, List.of(), null, NOW);
    }

    /**
     * Assembles the subject with the queued identifier generator this class holds.
     *
     * @param session the table the resolver will read from
     * @return the use case under test
     */
    private PlayCardPort useCaseFor(final GameSession session) {
        return useCaseWith(session, identifiers);
    }

    /**
     * Assembles the subject with a caller supplied identifier generator.
     *
     * <p>A test that expects only one identifier to be minted seeds a queue of one, so that a
     * second call fails loudly rather than quietly consuming a value meant for something else.
     *
     * @param session the table the resolver will read from
     * @param generator the identifiers the use case may mint
     * @return the use case under test
     */
    private PlayCardPort useCaseWith(final GameSession session, final IdentifierGenerator generator) {
        return new PlayCardService(
                new ResolvePlayerService(new InMemorySessionRepository(order, session), java.time.Clock.systemUTC()),
                handRepository,
                cardRepository,
                generator,
                FIXED,
                new TrickJournal(
                        trickRepository,
                        new InMemorySessionRepository(order, session),
                        publisher,
                        Optional.empty()));
    }
}
