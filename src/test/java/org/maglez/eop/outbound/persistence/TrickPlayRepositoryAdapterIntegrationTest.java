package org.maglez.eop.outbound.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.maglez.eop.domain.PlayerBuilder.aPlayer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.maglez.eop.domain.AlreadyPlayedInTrickException;
import org.maglez.eop.domain.Card;
import org.maglez.eop.domain.CardAlreadyPlayedException;
import org.maglez.eop.domain.CardNotInHandException;
import org.maglez.eop.domain.GameSession;
import org.maglez.eop.domain.Hand;
import org.maglez.eop.domain.HandAlreadyDealtException;
import org.maglez.eop.domain.HandCompleteException;
import org.maglez.eop.domain.HandNotDealtException;
import org.maglez.eop.domain.Hands;
import org.maglez.eop.domain.JoinCode;
import org.maglez.eop.domain.NotYourSeatException;
import org.maglez.eop.domain.OutOfTurnException;
import org.maglez.eop.domain.Player;
import org.maglez.eop.domain.PlayerNotInSessionException;
import org.maglez.eop.domain.PlayerRole;
import org.maglez.eop.domain.SessionNotFoundException;
import org.maglez.eop.domain.SessionNotJoinableException;
import org.maglez.eop.domain.Trick;
import org.maglez.eop.domain.TrickAlreadyOpenException;
import org.maglez.eop.domain.TrickAlreadyResolvedException;
import org.maglez.eop.domain.TrickPlay;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import org.maglez.eop.domain.PlayerBuilder;

/**
 * Proves the trick-play adapter turns database outcomes into domain outcomes.
 *
 * <p>Every use-case test above this layer runs against a hand-written repository
 * which, by construction, agrees with the use cases about what a collision looks
 * like. This class is the only place where the real unique indexes and foreign
 * keys from {@code 004-trick-play-schema.xml} decide, so it is the only place
 * that can show the translation in {@link TrickPlayRepositoryAdapter} is right:
 * {@code uq_hand_session_seat} has to arrive as
 * {@link HandAlreadyDealtException}, {@code uq_trick_session_sequence} as
 * {@link TrickAlreadyOpenException}, {@code uq_trick_play_trick_seat} and
 * {@code uq_trick_play_trick_player} as the same
 * {@link AlreadyPlayedInTrickException}, and {@code uq_trick_play_trick_card} as
 * {@link CardAlreadyPlayedException}, while anything unrecognised must keep
 * travelling as a failure.
 *
 * <p>Three details of the setup are load-bearing.
 *
 * <p><strong>Cards come from the seeded catalogue, never from
 * {@code DeckFixture}.</strong> The fixture mints synthetic identifiers, and
 * {@code fk_hand_card_card} refuses them. {@link #seededDeck()} reads the deck
 * the migration wrote, which is also the only deck production ever deals.
 *
 * <p><strong>The class is not {@code @Transactional}.</strong> A test-managed
 * transaction would be marked rollback-only by the first constraint violation,
 * so every later assertion in the same test would be talking to a poisoned
 * transaction rather than to the database. Each adapter call therefore commits,
 * which is also how production runs.
 *
 * <p><strong>Nothing is cleaned up between tests.</strong> Because rows survive,
 * every test mints its own session, players, hands and tricks from a shared
 * counter — see {@link #startedTable()}. Sharing a seeded session would couple
 * the tests through {@code uq_player_identity_token_hash}, which is global
 * rather than per session.
 *
 * <p>Two paths are deliberately unreachable from here and are left to their
 * comments in the adapter: the missing-card fault in {@code resolve}, because
 * {@code fk_hand_card_card} and {@code fk_trick_play_card} make deleting a
 * referenced card impossible, and the {@code fk_hand_player_seat} and
 * {@code fk_trick_play_player_seat} backstops, because the adapter's own seat
 * check refuses first. Column bounds are pinned by
 * {@code SeatAndSequenceBoundsTest} and the mapping by
 * {@code MappedSchemaValidationIntegrationTest}.
 */
@SpringBootTest
@DisplayName("Trick play persistence")
class TrickPlayRepositoryAdapterIntegrationTest {

    /**
     * Distinguishes one test's rows from another's; see the class comment. It
     * starts high because {@code SessionRepositoryAdapterIntegrationTest} mints
     * identifiers from the same pattern out of a counter of its own, and the two
     * classes share one database.
     */
    private static final AtomicInteger SERIAL = new AtomicInteger(1000);

    private static final Instant CREATED_AT = Instant.parse("2026-03-01T09:00:00Z");

    private static final Instant SEATED_AT = Instant.parse("2026-03-01T09:05:00Z");

    private static final Instant STARTED_AT = Instant.parse("2026-03-01T09:10:00Z");

    private static final Instant DEALT_AT = Instant.parse("2026-03-01T09:15:00Z");

    private static final Instant PLAYED_AT = Instant.parse("2026-03-01T09:20:00Z");

    private static final Instant RESOLVED_AT = Instant.parse("2026-03-01T09:25:00Z");

    /**
     * Prefix for the plaintext credentials the seeded players are given. It
     * differs from the one in {@code SessionRepositoryAdapterIntegrationTest}
     * because {@code uq_player_identity_token_hash} is global rather than per
     * session.
     */
    private static final String TRICK_PLAY_CREDENTIAL_PREFIX = "eop-trick-play-plain-";

    /** The identifier slot the session itself occupies; players take 0 to 5. */
    private static final int SESSION_SLOT = 8;

    /** Slot base for the hand identifiers of the first deal at each table. */
    private static final int HAND_SLOT_BASE = 30;

    /** Slot base for a second, distinct set of hand identifiers. */
    private static final int RIVAL_HAND_SLOT_BASE = 50;

    /** Slot base for trick identifiers, offset by the trick's sequence. */
    private static final int TRICK_SLOT_BASE = 100;

    /** Slot base for play identifiers, offset by the seat that played. */
    private static final int PLAY_SLOT_BASE = 60;

    /** Slot of a player who was never seated at any table. */
    private static final int STRANGER_SLOT = 99;

    /** How many players every table in this class seats. */
    private static final int SEATED_PLAYERS = 3;

    @Autowired private TrickPlayRepositoryAdapter adapter;

    @Autowired private SessionRepositoryAdapter sessions;

    @Autowired private CardJpaRepository cardRows;

    @Autowired private HandJpaRepository handRows;

    @Autowired private JdbcTemplate jdbc;

    @Nested
    @DisplayName("a deal")
    class ADeal {

        @Test
        @DisplayName("reads back with every seat, hand and card the domain dealt")
        void roundTripsThroughTheDatabase() {
            final Table table = dealtTable();

            final Hands stored = adapter.findBySessionId(table.sessionId()).orElseThrow();

            assertThat(stored.handsBySeat()).containsOnlyKeys(0, 1, 2);
            assertThat(stored.totalCards()).isEqualTo(table.hands().totalCards());
            for (int seat = 0; seat < SEATED_PLAYERS; seat++) {
                final Hand expected = table.hands().handOf(seat);
                final Hand actual = stored.handOf(seat);
                assertThat(actual.handId()).isEqualTo(expected.handId());
                assertThat(actual.playerId()).isEqualTo(table.player(seat));
                assertThat(actual.cards()).containsExactlyInAnyOrderElementsOf(expected.cards());
            }
        }

        @Test
        @DisplayName("gives the seeded deck out round robin, so seat zero holds every third card")
        void dealsTheSeededDeckRoundRobin() {
            final Table table = dealtTable();

            final List<Card> expected = new ArrayList<>();
            final int keptSize = table.hands().totalCards();
            for (int index = 0; index < keptSize; index += SEATED_PLAYERS) {
                expected.add(table.deck().get(index));
            }

            final Hands stored = adapter.findBySessionId(table.sessionId()).orElseThrow();
            assertThat(stored.handOf(0).cards()).containsExactlyInAnyOrderElementsOf(expected);
        }

        @Test
        @DisplayName("publishes the opening leader seat once it lands")
        void publishesTheOpeningLeaderSeat() {
            final Table table = startedTable();

            assertThat(adapter.findCurrentLeaderSeat(table.sessionId())).isEmpty();

            adapter.recordDeal(
                    table.sessionId(), table.hands(), table.hands().openingLeaderSeat(), DEALT_AT);

            assertThat(adapter.findCurrentLeaderSeat(table.sessionId()))
                    .hasValue(table.hands().openingLeaderSeat());
        }

        @Test
        @DisplayName("is absent rather than an error for a session nobody opened")
        void readsAnUnknownSessionAsEmpty() {
            final UUID absent = UUID.randomUUID();

            assertThat(adapter.findBySessionId(absent)).isEmpty();
            assertThat(adapter.findCurrentLeaderSeat(absent)).isEmpty();
        }

        @Test
        @DisplayName("cannot be repeated, because the leader seat is claimed once")
        void refusesASecondDeal() {
            final Table table = dealtTable();

            assertThatExceptionOfType(HandAlreadyDealtException.class)
                    .isThrownBy(
                            () ->
                                    adapter.recordDeal(
                                            table.sessionId(),
                                            table.hands(),
                                            table.hands().openingLeaderSeat(),
                                            DEALT_AT));

            assertThat(adapter.findBySessionId(table.sessionId()).orElseThrow().totalCards())
                    .isEqualTo(table.hands().totalCards());
        }

        @Test
        @DisplayName("is refused by uq_hand_session_seat even when the leader seat is cleared")
        void refusesASecondDealAtTheIndex() {
            final Table table = dealtTable();

            // Reopens the claim the adapter uses as its deal-once gate, so the
            // next deal reaches the unique index rather than stopping above it.
            jdbc.update(
                    "UPDATE game_session SET current_leader_seat = NULL WHERE id = ?",
                    table.sessionId());

            // Fresh hand identifiers, because reusing the stored ones would make
            // saveAndFlush merge the existing rows and quietly succeed.
            final Hands rival = table.deal(RIVAL_HAND_SLOT_BASE);

            assertThatExceptionOfType(HandAlreadyDealtException.class)
                    .isThrownBy(
                            () ->
                                    adapter.recordDeal(
                                            table.sessionId(),
                                            rival,
                                            rival.openingLeaderSeat(),
                                            DEALT_AT));

            assertThat(handRows.findByGameSessionIdOrderBySeatOrderAsc(table.sessionId()))
                    .hasSize(SEATED_PLAYERS);
        }

        @Test
        @DisplayName("is refused while the session is still a lobby")
        void refusesADealBeforeTheGameStarts() {
            final Table table = lobbyTable();

            assertThatExceptionOfType(SessionNotJoinableException.class)
                    .isThrownBy(
                            () ->
                                    adapter.recordDeal(
                                            table.sessionId(),
                                            table.hands(),
                                            table.hands().openingLeaderSeat(),
                                            DEALT_AT));
        }

        @Test
        @DisplayName("is refused for a session that does not exist")
        void refusesADealIntoNothing() {
            final Table table = startedTable();

            assertThatExceptionOfType(SessionNotFoundException.class)
                    .isThrownBy(
                            () ->
                                    adapter.recordDeal(
                                            UUID.randomUUID(),
                                            table.hands(),
                                            table.hands().openingLeaderSeat(),
                                            DEALT_AT));
        }

        @Test
        @DisplayName("writes nothing when one hand names a player who was never seated")
        void refusesADealNamingAStranger() {
            final Table table = startedTable();
            final Hands withStranger =
                    Hands.deal(
                            table.deck(),
                            List.of(
                                    new Hands.Seat(
                                            0, table.player(0), identifier(table.serial(), 30)),
                                    new Hands.Seat(
                                            1, table.player(1), identifier(table.serial(), 31)),
                                    new Hands.Seat(
                                            2,
                                            identifier(table.serial(), STRANGER_SLOT),
                                            identifier(table.serial(), 32))));

            assertThatExceptionOfType(PlayerNotInSessionException.class)
                    .isThrownBy(
                            () ->
                                    adapter.recordDeal(
                                            table.sessionId(),
                                            withStranger,
                                            withStranger.openingLeaderSeat(),
                                            DEALT_AT));

            // The stranger is caught before the first hand is written, and the
            // leader seat the statement had already claimed rolls back with it.
            assertThat(handRows.findByGameSessionIdOrderBySeatOrderAsc(table.sessionId())).isEmpty();
            assertThat(adapter.findCurrentLeaderSeat(table.sessionId())).isEmpty();
        }

        @Test
        @DisplayName("is refused when a seat is out of range before any row is written")
        void refusesAnOpeningLeaderSeatOutOfRange() {
            final Table table = startedTable();

            assertThatExceptionOfType(DataAccessException.class)
                    .as("the CHECK would catch it, but only as an opaque violation "
                            + "several frames from the call that supplied the seat")
                    .isThrownBy(
                            () ->
                                    adapter.recordDeal(
                                            table.sessionId(), table.hands(), 9, DEALT_AT))
                    .withMessageContaining("openingLeaderSeat 9")
                    .isNotInstanceOf(IllegalArgumentException.class);

            assertThat(adapter.findCurrentLeaderSeat(table.sessionId()))
                    .as("refused at the boundary means refused before the claim")
                    .isEmpty();
            assertThat(adapter.findBySessionId(table.sessionId())).isEmpty();
        }

        @Test
        @DisplayName("is refused on the way back out when one card sits in two hands")
        void refusesReadingACardThatSitsInTwoHands() {
            final Table table = dealtTable();
            final Card contested = table.cardHeldAt(0, 0);

            // The one Hands invariant with no constraint behind it: pk_hand_card stops a
            // card appearing twice in one hand and uq_trick_play_trick_card stops it
            // being played twice into one trick, but nothing stops it being dealt to two
            // hands of a session. Its enforcement is entirely in the domain, and this is
            // the only test that walks that enforcement through the adapter's read.
            jdbc.update(
                    "INSERT INTO hand_card (hand_id, card_id) VALUES (?, ?)",
                    table.hands().handOf(1).handId(),
                    contested.cardId());

            // Not an IllegalArgumentException by the time it leaves, and that matters
            // more than the type it started as. Hands.reconstitute raises one, but this
            // adapter is a @Repository, so Spring rewrites it to
            // InvalidDataAccessApiUsageException on the way out — which reaches the
            // catch-all 500 rather than the handler that answers 400. A corrupt row is
            // our fault, and a 400 would bill the caller for it.
            assertThatExceptionOfType(DataAccessException.class)
                    .isThrownBy(() -> adapter.findBySessionId(table.sessionId()))
                    .withMessageContaining("The same card cannot be dealt to two seats")
                    .isNotInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("writes nothing when a seated player is dealt somebody else's seat")
        void refusesADealAtTheWrongSeat() {
            final Table table = startedTable();
            final Hands swapped =
                    Hands.deal(
                            table.deck(),
                            List.of(
                                    new Hands.Seat(
                                            0, table.player(0), identifier(table.serial(), 30)),
                                    new Hands.Seat(
                                            1, table.player(2), identifier(table.serial(), 31)),
                                    new Hands.Seat(
                                            2, table.player(1), identifier(table.serial(), 32))));

            assertThatExceptionOfType(NotYourSeatException.class)
                    .isThrownBy(
                            () ->
                                    adapter.recordDeal(
                                            table.sessionId(),
                                            swapped,
                                            swapped.openingLeaderSeat(),
                                            DEALT_AT));

            assertThat(handRows.findByGameSessionIdOrderBySeatOrderAsc(table.sessionId())).isEmpty();
        }
    }

    @Nested
    @DisplayName("an open trick")
    class AnOpenTrick {

        @Test
        @DisplayName("reads back as the current trick, with no plays and no winner")
        void roundTripsThroughTheDatabase() {
            final Table table = dealtTable();
            final Trick opened = table.openFirstTrick();

            final Trick stored = adapter.findCurrentTrick(table.sessionId()).orElseThrow();

            assertThat(stored.trickId()).isEqualTo(opened.trickId());
            assertThat(stored.sequence()).isEqualTo(opened.sequence());
            assertThat(stored.leaderSeat()).isEqualTo(opened.leaderSeat());
            assertThat(stored.plays()).isEmpty();
            assertThat(stored.winner()).isEmpty();
        }

        @Test
        @DisplayName("is absent before the first trick is opened")
        void readsAsEmptyBeforeAnyTrick() {
            final Table table = dealtTable();

            assertThat(adapter.findCurrentTrick(table.sessionId())).isEmpty();
            assertThat(adapter.findCurrentTrick(UUID.randomUUID())).isEmpty();
        }

        @Test
        @DisplayName("is the highest sequence, not the most recently written row")
        void readsTheHighestSequence() {
            final Table table = dealtTable();
            final int leader = table.leaderSeat();
            table.openFirstTrick();
            final Trick second = table.trick(2);

            adapter.openTrick(table.sessionId(), second, leader, PLAYED_AT);

            assertThat(adapter.findCurrentTrick(table.sessionId()).orElseThrow().trickId())
                    .isEqualTo(second.trickId());
        }

        @Test
        @DisplayName("cannot be opened twice at one sequence, because uq_trick_session_sequence refuses")
        void refusesASecondTrickAtTheSameSequence() {
            final Table table = dealtTable();
            final int leader = table.leaderSeat();
            final Trick first = table.openFirstTrick();
            // A different identifier at the same sequence, so the row is an
            // insert. Opening a trick leaves the leader seat where it was, so
            // this call clears the compare-and-set and reaches the index.
            final Trick rival = Trick.open(UUID.randomUUID(), first.sequence(), leader);

            assertThatExceptionOfType(TrickAlreadyOpenException.class)
                    .isThrownBy(() -> adapter.openTrick(table.sessionId(), rival, leader, PLAYED_AT))
                    .withMessageContaining(String.valueOf(first.sequence()));

            assertThat(adapter.findCurrentTrick(table.sessionId()).orElseThrow().trickId())
                    .isEqualTo(first.trickId());
        }

        @Test
        @DisplayName("is refused when the caller expects a leader seat the session has left")
        void refusesAnOutOfTurnOpen() {
            final Table table = dealtTable();
            final int notTheLeader = (table.leaderSeat() + 1) % SEATED_PLAYERS;

            assertThatExceptionOfType(OutOfTurnException.class)
                    .isThrownBy(
                            () ->
                                    adapter.openTrick(
                                            table.sessionId(),
                                            table.trick(1),
                                            notTheLeader,
                                            PLAYED_AT));
        }

        @Test
        @DisplayName("is refused before a deal has set any leader seat at all, and says so rather than blaming the status")
        void refusesAnOpenBeforeTheDeal() {
            final Table table = startedTable();
            // Built without consulting the session, which has no leader seat yet.
            final Trick first = Trick.open(table.trickId(1), 1, 0);

            // This branch used to answer SessionNotJoinableException carrying IN_PROGRESS, which
            // told the caller a session was not joinable while handing back the status saying it
            // was. The status was right and the explanation contradicted itself, so the fix was a
            // type that can name the state rather than a different status.
            assertThatExceptionOfType(HandNotDealtException.class)
                    .isThrownBy(() -> adapter.openTrick(table.sessionId(), first, 0, PLAYED_AT))
                    .withMessageContaining(table.sessionId().toString());
        }
    }

    @Nested
    @DisplayName("a played card")
    class APlayedCard {

        @Test
        @DisplayName("leaves the hand it came from and reads back inside the trick")
        void roundTripsThroughTheDatabase() {
            final Table table = dealtTable();
            table.openFirstTrick();
            final int leader = table.leaderSeat();
            final Card led = table.cardHeldAt(leader, 0);
            final TrickPlay play = table.play(leader, led, PLAYED_AT);

            adapter.appendPlay(table.sessionId(), table.trickId(1), leader, play);

            final Trick stored = adapter.findCurrentTrick(table.sessionId()).orElseThrow();
            assertThat(stored.plays()).hasSize(1);
            final TrickPlay readBack = stored.plays().get(0);
            assertThat(readBack.trickPlayId()).isEqualTo(play.trickPlayId());
            assertThat(readBack.playerId()).isEqualTo(play.playerId());
            assertThat(readBack.seatOrder()).isEqualTo(leader);
            assertThat(readBack.card()).isEqualTo(led);
            assertThat(readBack.threatLinked()).isEqualTo(play.threatLinked());
            assertThat(readBack.components()).containsExactlyElementsOf(play.components());
            assertThat(readBack.notes()).isEqualTo(play.notes());
            assertThat(readBack.playedAt()).isEqualTo(PLAYED_AT);

            final Hands remaining = adapter.findBySessionId(table.sessionId()).orElseThrow();
            assertThat(remaining.handOf(leader).holds(led)).isFalse();
            assertThat(remaining.handOf(leader).size())
                    .isEqualTo(table.hands().handOf(leader).size() - 1);
        }

        @Test
        @DisplayName("reads back in rotation from the leader, not in the order the clock saw")
        void ordersPlaysByRotationRatherThanTimestamp() {
            final Table table = dealtTable();
            table.openFirstTrick();
            final int leader = table.leaderSeat();

            // Every seat plays, and the clock and the insertion order both run in
            // the exact reverse of the rotation, so a sort by played_at, by
            // insertion or by raw seat number all disagree with it. Using the whole
            // table rather than the leader and one follower is load-bearing: with
            // two adjacent seats the seat numbers ascend, so inverting the rotation
            // arithmetic still happens to order them correctly and the defect this
            // test exists to catch survives.
            final List<TrickPlay> rotation = new ArrayList<>();
            for (int position = 0; position < SEATED_PLAYERS; position++) {
                final int seat = (leader + position) % SEATED_PLAYERS;
                rotation.add(table.play(
                        seat,
                        table.cardHeldAt(seat, 0),
                        PLAYED_AT.plusSeconds(30L * (SEATED_PLAYERS - position))));
            }
            for (int position = SEATED_PLAYERS - 1; position >= 0; position--) {
                adapter.appendPlay(
                        table.sessionId(), table.trickId(1), leader, rotation.get(position));
            }

            final Trick stored = adapter.findCurrentTrick(table.sessionId()).orElseThrow();
            assertThat(stored.plays())
                    .extracting(TrickPlay::seatOrder)
                    .containsExactlyElementsOf(rotation.stream().map(TrickPlay::seatOrder).toList());
            // The trick reads its led suit from the first play, so the order above
            // is what decides which card the rules measure against. A wrong order
            // may instead be refused by Trick.reconstitute, which checks the plays
            // run clockwise from the leader; either way this test goes red.
            assertThat(stored.plays().get(0).card()).isEqualTo(rotation.get(0).card());
        }

        @Test
        @DisplayName("is refused when the card belongs to another seat's hand")
        void refusesACardTheSeatDoesNotHold() {
            final Table table = dealtTable();
            table.openFirstTrick();
            final int leader = table.leaderSeat();
            final int other = (leader + 1) % SEATED_PLAYERS;
            final Card notMine = table.cardHeldAt(other, 0);

            assertThatExceptionOfType(CardNotInHandException.class)
                    .isThrownBy(
                            () ->
                                    adapter.appendPlay(
                                            table.sessionId(),
                                            table.trickId(1),
                                            leader,
                                            table.play(leader, notMine, PLAYED_AT)))
                    .withMessageContaining(notMine.cardId().toString());
        }

        @Test
        @DisplayName("cannot be played twice, because the row has already left the hand")
        void refusesACardAlreadyGone() {
            final Table table = dealtTable();
            table.openFirstTrick();
            final int leader = table.leaderSeat();
            final Card led = table.cardHeldAt(leader, 0);
            adapter.appendPlay(
                    table.sessionId(), table.trickId(1), leader, table.play(leader, led, PLAYED_AT));

            assertThatExceptionOfType(CardNotInHandException.class)
                    .isThrownBy(
                            () ->
                                    adapter.appendPlay(
                                            table.sessionId(),
                                            table.trickId(1),
                                            leader,
                                            table.play(
                                                    leader,
                                                    led,
                                                    PLAYED_AT,
                                                    identifier(table.serial(), 90))));
        }

        @Test
        @DisplayName("is refused a second time from one seat, from whichever index fires first")
        void refusesASecondPlayFromOneSeat() {
            final Table table = dealtTable();
            table.openFirstTrick();
            final int leader = table.leaderSeat();
            adapter.appendPlay(
                    table.sessionId(),
                    table.trickId(1),
                    leader,
                    table.play(leader, table.cardHeldAt(leader, 0), PLAYED_AT));

            // A different card, so the hand still holds it and the play reaches
            // the trick. Both uq_trick_play_trick_seat and
            // uq_trick_play_trick_player describe this row, they fire in
            // whichever order the engine chooses, and the adapter maps each to
            // the same refusal.
            final TrickPlay again =
                    table.play(
                            leader,
                            table.cardHeldAt(leader, 1),
                            PLAYED_AT.plusSeconds(10),
                            identifier(table.serial(), 91));

            assertThatExceptionOfType(AlreadyPlayedInTrickException.class)
                    .isThrownBy(
                            () ->
                                    adapter.appendPlay(
                                            table.sessionId(), table.trickId(1), leader, again));
        }

        @Test
        @DisplayName("is refused when the same card reaches one trick from two hands")
        void refusesACardAlreadyInTheTrick() {
            final Table table = dealtTable();
            table.openFirstTrick();
            final int leader = table.leaderSeat();
            final int other = (leader + 1) % SEATED_PLAYERS;
            final Card contested = table.cardHeldAt(leader, 0);
            adapter.appendPlay(
                    table.sessionId(),
                    table.trickId(1),
                    leader,
                    table.play(leader, contested, PLAYED_AT));

            // Nothing in the schema stops one card being dealt to two hands of a
            // session — the Hands invariant is the only guard — so the row is
            // injected here to leave uq_trick_play_trick_card as the single
            // constraint the next play can violate.
            jdbc.update(
                    "INSERT INTO hand_card (hand_id, card_id) VALUES (?, ?)",
                    table.hands().handOf(other).handId(),
                    contested.cardId());

            assertThatExceptionOfType(CardAlreadyPlayedException.class)
                    .isThrownBy(
                            () ->
                                    adapter.appendPlay(
                                            table.sessionId(),
                                            table.trickId(1),
                                            leader,
                                            table.play(
                                                    other,
                                                    contested,
                                                    PLAYED_AT.plusSeconds(10),
                                                    identifier(table.serial(), 92))))
                    .withMessageContaining(contested.cardId().toString());
        }

        @Test
        @DisplayName("is refused for a player who was never seated")
        void refusesAPlayFromAStranger() {
            final Table table = dealtTable();
            table.openFirstTrick();
            final int leader = table.leaderSeat();
            final TrickPlay stranger =
                    new TrickPlay(
                            identifier(table.serial(), 93),
                            identifier(table.serial(), STRANGER_SLOT),
                            leader,
                            table.cardHeldAt(leader, 0),
                            false,
                            List.of(),
                            null,
                            PLAYED_AT);

            assertThatExceptionOfType(PlayerNotInSessionException.class)
                    .isThrownBy(
                            () ->
                                    adapter.appendPlay(
                                            table.sessionId(), table.trickId(1), leader, stranger));
        }

        @Test
        @DisplayName("is refused when the caller expects a leader seat the session has left")
        void refusesAnOutOfTurnPlay() {
            final Table table = dealtTable();
            table.openFirstTrick();
            final int leader = table.leaderSeat();
            final int notTheLeader = (leader + 1) % SEATED_PLAYERS;

            assertThatExceptionOfType(OutOfTurnException.class)
                    .isThrownBy(
                            () ->
                                    adapter.appendPlay(
                                            table.sessionId(),
                                            table.trickId(1),
                                            notTheLeader,
                                            table.play(
                                                    leader,
                                                    table.cardHeldAt(leader, 0),
                                                    PLAYED_AT)));
        }
    }

    @Nested
    @DisplayName("a resolved trick")
    class AResolvedTrick {

        @Test
        @DisplayName("names its winner and hands the lead to the winning seat")
        void roundTripsThroughTheDatabase() {
            final Table table = dealtTable();
            final Trick resolved = table.playAndResolveFirstTrick();
            final int leader = table.leaderSeat();
            final int nextLeader = resolved.winningSeat();

            adapter.recordResolution(
                    table.sessionId(), resolved, leader, OptionalInt.of(nextLeader), RESOLVED_AT);

            final Trick stored = adapter.findCurrentTrick(table.sessionId()).orElseThrow();
            assertThat(stored.winner()).isPresent();
            assertThat(stored.winner().orElseThrow().trickPlayId())
                    .isEqualTo(resolved.winner().orElseThrow().trickPlayId());
            assertThat(stored.winningSeat()).isEqualTo(nextLeader);
            assertThat(adapter.findCurrentLeaderSeat(table.sessionId())).hasValue(nextLeader);
        }

        @Test
        @DisplayName("cannot be resolved a second time, because the lead has already moved")
        void refusesASecondResolution() {
            final Table table = dealtTable();
            final Trick resolved = table.playAndResolveFirstTrick();
            final int leader = table.leaderSeat();
            final int nextLeader = resolved.winningSeat();
            adapter.recordResolution(
                    table.sessionId(), resolved, leader, OptionalInt.of(nextLeader), RESOLVED_AT);

            assertThatExceptionOfType(OutOfTurnException.class)
                    .isThrownBy(
                            () ->
                                    adapter.recordResolution(
                                            table.sessionId(),
                                            resolved,
                                            leader,
                                            OptionalInt.of(nextLeader),
                                            RESOLVED_AT));
        }

        /**
         * The case {@link #refusesASecondResolution()} cannot reach, and the reason this
         * test exists. That test is only refused by the compare-and-set because its
         * fixture happens to produce a winner who is not the leader, so the second call
         * witnesses a leader seat that has already moved. When the seat that led the
         * trick also wins it the lead does not move: {@code advanceLeaderSeat} sets the
         * column to the very value it compared against, the statement is idempotent, and
         * a replay sails through the guard and returns one row. The winner update is then
         * the first statement to notice, and it matches nothing because
         * {@code winner_play_id} is no longer null.
         *
         * <p>A security review found that limb mapped to {@link IllegalStateException},
         * which meant an ordinary replay of a perfectly ordinary hand answered 500, ten
         * lines below a class comment promising that a zero row count is never rethrown
         * as a server fault. It now answers {@link TrickAlreadyResolvedException}, and so
         * 409.
         *
         * <p>Passing {@code leader} as the next leader is not a contrivance to reach the
         * branch. It is what the rule produces whenever the leader wins their own trick
         * and still holds a card, which is the common case rather than the exotic one.
         *
         * <p>The test cannot pass vacuously. If the guard were removed the second call
         * would succeed and no exception would be thrown; if the old mapping were
         * restored the thrown type would be Spring's
         * {@link org.springframework.dao.InvalidDataAccessApiUsageException} rather than
         * this one, because {@link IllegalStateException} is translated on the way out of
         * a {@code @Repository}. Both mutations fail here.
         */
        @Test
        @DisplayName(
                "refuses a replay with a conflict, not a fault, when its leader won it")
        void refusesAReplayWhenTheLeaderWonTheirOwnTrick() {
            final Table table = dealtTable();
            final Trick resolved = table.playAndResolveFirstTrick();
            final int leader = table.leaderSeat();

            adapter.recordResolution(
                    table.sessionId(), resolved, leader, OptionalInt.of(leader), RESOLVED_AT);
            assertThat(adapter.findCurrentLeaderSeat(table.sessionId()))
                    .as("the lead stays put, which is what makes the guard idempotent")
                    .hasValue(leader);

            assertThatExceptionOfType(TrickAlreadyResolvedException.class)
                    .isThrownBy(
                            () ->
                                    adapter.recordResolution(
                                            table.sessionId(),
                                            resolved,
                                            leader,
                                            OptionalInt.of(leader),
                                            RESOLVED_AT))
                    .withMessageContaining(resolved.trickId().toString());
        }

        /**
         * Note the exception type. The adapter raises {@link IllegalStateException} to
         * signal a server fault, but it is a {@code @Repository}, so Spring's
         * persistence exception translation rewrites the JPA-specification types
         * {@link IllegalStateException} and {@link IllegalArgumentException} into
         * {@link org.springframework.dao.InvalidDataAccessApiUsageException} on the way
         * out. The status code is unchanged — neither type has a handler, so both reach
         * the catch-all and answer 500 — and the assertion pins the half that could
         * change it: the translated type must not be an {@link IllegalArgumentException},
         * or the handler would answer 400 "Invalid request" and blame the caller for a
         * fault of ours. Every domain exception this adapter throws extends
         * {@link RuntimeException} directly, so none of them is caught by this
         * translation and none loses its mapped status.
         */
        @Test
        @DisplayName("is refused while the trick still has no winner")
        void refusesAnUnresolvedTrick() {
            final Table table = dealtTable();
            final Trick open = table.openFirstTrick();
            final int leader = table.leaderSeat();

            assertThatExceptionOfType(DataAccessException.class)
                    .isThrownBy(
                            () ->
                                    adapter.recordResolution(
                                            table.sessionId(),
                                            open,
                                            leader,
                                            OptionalInt.of(leader),
                                            RESOLVED_AT))
                    .withMessageContaining("is not resolved")
                    .isNotInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("refuses to reconstitute when the stored winner was played into another trick")
        void refusesAWinnerFromAnotherTrick() {
            final Table table = dealtTable();
            final int leader = table.leaderSeat();
            table.openFirstTrick();
            final TrickPlay played =
                    table.play(leader, table.cardHeldAt(leader, 0), PLAYED_AT);
            adapter.appendPlay(table.sessionId(), table.trickId(1), leader, played);
            // Opening a trick does not move the leader seat, so the second trick
            // becomes the current one while the first keeps the only play.
            adapter.openTrick(table.sessionId(), table.trick(2), leader, PLAYED_AT);

            // fk_trick_winner_play proves only that the winner is some play:
            // Slice B measured it accepting a play from a different trick and
            // even a different session, so the adapter has to check.
            jdbc.update(
                    "UPDATE trick SET winner_play_id = ? WHERE id = ?",
                    played.trickPlayId(),
                    table.trickId(2));

            assertThatExceptionOfType(DataAccessException.class)
                    .isThrownBy(() -> adapter.findCurrentTrick(table.sessionId()))
                    .withMessageContaining("names a winner that was not played into it")
                    .isNotInstanceOf(IllegalArgumentException.class);
        }

        /**
         * Deals, plays the hand out, then deals again.
         *
         * <p>This pins the invariant EOP-14 Slice E moved. Until Slice E, {@code claimDeal}'s
         * {@code current_leader_seat IS NULL} predicate was the whole deal-once gate. Now that a
         * played-out hand writes NULL back into that column the predicate matches again, so the
         * claim succeeds and what refuses the second deal is {@code uq_hand_session_seat}, one
         * statement later, inside the same transaction — which rolls the claim back with it. The
         * caller cannot tell the difference and gets the same refusal either way, which is exactly
         * why this needs a test: the mechanism changed underneath an unchanged answer, and nothing
         * else in this suite exercises the mechanism that now carries the weight.
         *
         * <p>It rests on hand rows never being deleted. Nothing in this codebase deletes one — the
         * only delete in the persistence layer takes a single card out of a single hand — so if
         * that ever changes, this is the test that will say so.
         *
         * <p>The second deal is handed a fresh set of hand identifiers, because that is what a real
         * deal brings: {@code DealHandsPort} mints one from the identifier generator per seat on
         * every call. That detail is load-bearing rather than incidental. Passing this table's
         * original {@code hands()} back in made the first version of this test fail: an entity whose
         * identifier already exists is merged rather than inserted, so the unique key on
         * {@code (game_session_id, seat_order)} was never offered a second row to refuse and the deal
         * silently rewrote the hands in place. The gate is therefore two things at once - the
         * constraint, and the fact that a deal never reuses a hand identifier.
         */
        @Test
        @DisplayName("refuses a second deal once the hand has been played out")
        void refusesASecondDealOnceTheHandIsSpent() {
            final Table table = dealtTable();
            final Trick resolved = table.playAndResolveFirstTrick();
            final int leader = table.leaderSeat();
            adapter.recordResolution(table.sessionId(), resolved, leader, OptionalInt.empty(), RESOLVED_AT);

            assertThat(adapter.findCurrentLeaderSeat(table.sessionId()))
                    .as("the hand is spent, so no seat leads and the claim predicate matches again")
                    .isEmpty();

            final Hands rival = table.deal(RIVAL_HAND_SLOT_BASE);

            assertThatExceptionOfType(HandAlreadyDealtException.class)
                    .isThrownBy(() -> adapter.recordDeal(
                            table.sessionId(), rival, rival.openingLeaderSeat(), DEALT_AT))
                    .withMessageContaining(table.sessionId().toString());
        }

        /**
         * The end of a hand reaches the database as the absence of a leading
         * seat, not as a seat that cannot lead. EOP-14 Slice D had no way to say
         * that: the port took an {@code int}, so the winning seat was written
         * even when it held nothing, and the row asserted that a seat might lead
         * while every hand was empty.
         *
         * <p>The winner is asserted as well as the missing lead, because both
         * halves of {@code recordResolution} write and only one of them is under
         * test here. A resolution that quietly failed would also leave no
         * leader, and this test would pass on it if it looked no further.
         */
        @Test
        @DisplayName("records no leading seat when no seat holds a card")
        void recordsNoLeaderWhenNoSeatHoldsACard() {
            final Table table = dealtTable();
            final Trick resolved = table.playAndResolveFirstTrick();
            final int leader = table.leaderSeat();

            adapter.recordResolution(
                    table.sessionId(), resolved, leader, OptionalInt.empty(), RESOLVED_AT);

            assertThat(adapter.findCurrentLeaderSeat(table.sessionId()))
                    .as("no seat leads once the hand is played out")
                    .isEmpty();
            assertThat(adapter.findCurrentTrick(table.sessionId()).orElseThrow().winner())
                    .as("the winner is still recorded, so the empty lead is not a failed write")
                    .isPresent();
        }

        /**
         * A null {@code current_leader_seat} carries two meanings since Slice E:
         * the deal has not happened, or it has happened and finished. The
         * adapter tells them apart by whether any hand row exists, and this is
         * the only test that reaches the second branch.
         *
         * <p>Nothing in the product can reach it today — for the last trick to
         * have been resolvable, every seat holding a card had already played, so
         * no play can be in flight behind it. It is reached here by asking for
         * the next trick after the lead was cleared, which is exactly the shape
         * a stale client would send.
         *
         * <p>The assertion is on the type rather than the message, because the
         * defect it guards against is the honest-looking lie: before Slice E
         * this limb answered {@code HandNotDealtException}, telling a caller its
         * cards were never dealt when they had been dealt and played out.
         */
        @Test
        @DisplayName("answers a played-out hand rather than an undealt one")
        void answersAPlayedOutHandRatherThanAnUndealtOne() {
            final Table table = dealtTable();
            final Trick resolved = table.playAndResolveFirstTrick();
            final int leader = table.leaderSeat();
            adapter.recordResolution(
                    table.sessionId(), resolved, leader, OptionalInt.empty(), RESOLVED_AT);

            final Trick next = Trick.open(table.trickId(2), 2, leader);

            assertThatExceptionOfType(HandCompleteException.class)
                    .isThrownBy(() -> adapter.openTrick(table.sessionId(), next, leader, PLAYED_AT))
                    .withMessageContaining(table.sessionId().toString());
        }
    }

    @Nested
    @DisplayName("the guards")
    class TheGuards {

        @Test
        @DisplayName("refuse a missing session identifier on every method")
        void refuseAMissingSessionId() {
            final Table table = dealtTable();
            final Trick trick = table.trick(1);
            final TrickPlay play =
                    table.play(table.leaderSeat(), table.cardHeldAt(table.leaderSeat(), 0), PLAYED_AT);

            assertThatNullPointerException().isThrownBy(() -> adapter.findBySessionId(null));
            assertThatNullPointerException().isThrownBy(() -> adapter.findCurrentLeaderSeat(null));
            assertThatNullPointerException().isThrownBy(() -> adapter.findCurrentTrick(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> adapter.recordDeal(null, table.hands(), 0, DEALT_AT));
            assertThatNullPointerException()
                    .isThrownBy(() -> adapter.openTrick(null, trick, 0, PLAYED_AT));
            assertThatNullPointerException()
                    .isThrownBy(() -> adapter.appendPlay(null, trick.trickId(), 0, play));
            assertThatNullPointerException()
                    .isThrownBy(
                            () ->
                                    adapter.recordResolution(
                                            null, trick, 0, OptionalInt.of(0), RESOLVED_AT));
        }

        @Test
        @DisplayName("refuse a missing payload or timestamp")
        void refuseAMissingPayload() {
            final Table table = dealtTable();
            final UUID sessionId = table.sessionId();
            final Trick trick = table.trick(1);

            assertThatNullPointerException()
                    .isThrownBy(() -> adapter.recordDeal(sessionId, null, 0, DEALT_AT));
            assertThatNullPointerException()
                    .isThrownBy(() -> adapter.recordDeal(sessionId, table.hands(), 0, null));
            assertThatNullPointerException()
                    .isThrownBy(() -> adapter.openTrick(sessionId, null, 0, PLAYED_AT));
            assertThatNullPointerException()
                    .isThrownBy(() -> adapter.openTrick(sessionId, trick, 0, null));
            assertThatNullPointerException()
                    .isThrownBy(() -> adapter.appendPlay(sessionId, null, 0, null));
            assertThatNullPointerException()
                    .isThrownBy(() -> adapter.appendPlay(sessionId, trick.trickId(), 0, null));
            assertThatNullPointerException()
                    .isThrownBy(
                            () ->
                                    adapter.recordResolution(
                                            sessionId, null, 0, OptionalInt.of(0), RESOLVED_AT));
            assertThatNullPointerException()
                    .isThrownBy(
                            () ->
                                    adapter.recordResolution(
                                            sessionId, trick, 0, OptionalInt.of(0), null));
            assertThatNullPointerException()
                    .isThrownBy(
                            () -> adapter.recordResolution(sessionId, trick, 0, null, RESOLVED_AT));
        }
    }

    /**
     * A session of three seated players, its identifiers and the deck it was
     * dealt from. Everything a test needs to address one table without touching
     * another test's rows.
     */
    private final class Table {

        private final int serial;

        private final List<Card> deck;

        private final Hands hands;

        private Table(final int serial, final List<Card> deck) {
            this.serial = serial;
            this.deck = deck;
            this.hands = deal(HAND_SLOT_BASE);
        }

        private int serial() {
            return serial;
        }

        private UUID sessionId() {
            return identifier(serial, SESSION_SLOT);
        }

        private List<Card> deck() {
            return deck;
        }

        private Hands hands() {
            return hands;
        }

        private UUID player(final int seat) {
            return identifier(serial, seat);
        }

        /** Deals the whole seeded deck to the three seats, from a given slot base. */
        private Hands deal(final int handSlotBase) {
            final List<Hands.Seat> seats = new ArrayList<>();
            for (int seat = 0; seat < SEATED_PLAYERS; seat++) {
                seats.add(new Hands.Seat(seat, player(seat), identifier(serial, handSlotBase + seat)));
            }
            return Hands.deal(deck, seats);
        }

        private int leaderSeat() {
            return adapter.findCurrentLeaderSeat(sessionId()).orElseThrow();
        }

        private UUID trickId(final int sequence) {
            return identifier(serial, TRICK_SLOT_BASE + sequence);
        }

        private Trick trick(final int sequence) {
            return Trick.open(trickId(sequence), sequence, leaderSeat());
        }

        private Trick openFirstTrick() {
            final Trick first = trick(1);
            adapter.openTrick(sessionId(), first, first.leaderSeat(), PLAYED_AT);
            return first;
        }

        private Card cardHeldAt(final int seat, final int index) {
            return hands.handOf(seat).cards().get(index);
        }

        private TrickPlay play(final int seat, final Card card, final Instant playedAt) {
            return play(seat, card, playedAt, identifier(serial, PLAY_SLOT_BASE + seat));
        }

        private TrickPlay play(
                final int seat, final Card card, final Instant playedAt, final UUID playId) {
            return new TrickPlay(
                    playId,
                    player(seat),
                    seat,
                    card,
                    true,
                    List.of("Spoofing the reviewer", "Tampering with the log"),
                    "Recorded by the persistence test",
                    playedAt);
        }

        /**
         * Plays one card from every seat into the first trick and lets the domain
         * pick the winner, so the resolution under test is the one the rules
         * would really produce.
         */
        private Trick playAndResolveFirstTrick() {
            final Trick first = openFirstTrick();
            final int leader = first.leaderSeat();
            final List<TrickPlay> plays = new ArrayList<>();
            for (int offset = 0; offset < SEATED_PLAYERS; offset++) {
                final int seat = (leader + offset) % SEATED_PLAYERS;
                final TrickPlay play =
                        play(seat, cardHeldAt(seat, 0), PLAYED_AT.plusSeconds(offset));
                adapter.appendPlay(sessionId(), first.trickId(), leader, play);
                plays.add(play);
            }
            return Trick.reconstitute(first.trickId(), first.sequence(), leader, plays, null)
                    .resolved();
        }
    }

    /** Opens a lobby of three players without starting the game. */
    private Table lobbyTable() {
        final int serial = SERIAL.incrementAndGet();
        final UUID sessionId = identifier(serial, SESSION_SLOT);
        final Player facilitator =
                aPlayer()
                        .withPlayerId(identifier(serial, 0))
                        .withSeatOrder(0)
                        .withToken(tokenFor(serial, 0))
                        .withJoinedAt(CREATED_AT)
                        .build();
        sessions.createLobby(
                GameSession.openLobby(sessionId, joinCodeFor(serial), facilitator, CREATED_AT));
        for (int seat = 1; seat < SEATED_PLAYERS; seat++) {
            sessions.seatPlayer(sessionId, participant(serial, seat), SEATED_AT);
        }
        return new Table(serial, seededDeck());
    }

    /** A lobby of three players that has been started, but not yet dealt. */
    private Table startedTable() {
        final Table table = lobbyTable();
        sessions.recordStarted(table.sessionId(), STARTED_AT);
        return table;
    }

    /** A started session whose hands have been dealt and committed. */
    private Table dealtTable() {
        final Table table = startedTable();
        adapter.recordDeal(
                table.sessionId(), table.hands(), table.hands().openingLeaderSeat(), DEALT_AT);
        return table;
    }

    /**
     * The deck the migration seeded, in deal order. {@code DeckFixture} cannot be
     * used here: it mints synthetic card identifiers, and
     * {@code fk_hand_card_card} refuses them.
     */
    private List<Card> seededDeck() {
        return cardRows.findAll().stream()
                .map(CardJpaEntity::toDomain)
                .sorted(
                        Comparator.comparingInt((Card card) -> card.suit().deckOrder())
                                .thenComparingInt(card -> card.rank().value()))
                .toList();
    }

    private static Player participant(final int serial, final int seat) {
        return aPlayer()
                .withPlayerId(identifier(serial, seat))
                .withDisplayName(new org.maglez.eop.domain.DisplayName("Player " + seat))
                .withSeatOrder(seat)
                .withRole(PlayerRole.PARTICIPANT)
                .withToken(tokenFor(serial, seat))
                .withJoinedAt(SEATED_AT)
                .build();
    }

    private static UUID identifier(final int serial, final int slot) {
        return UUID.fromString("00000000-0000-7000-8000-%08d%04d".formatted(serial, slot));
    }

    private static String tokenFor(final int serial, final int slot) {
        return TRICK_PLAY_CREDENTIAL_PREFIX + serial + "-" + slot;
    }

    private static JoinCode joinCodeFor(final int serial) {
        final int radix = JoinCode.ALPHABET.length();
        final StringBuilder code = new StringBuilder(JoinCode.LENGTH);
        int remaining = serial;
        for (int position = 0; position < JoinCode.LENGTH; position++) {
            code.append(JoinCode.ALPHABET.charAt(remaining % radix));
            remaining /= radix;
        }
        return new JoinCode(code.toString());
    }
}
