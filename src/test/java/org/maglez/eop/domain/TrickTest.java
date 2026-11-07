package org.maglez.eop.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.maglez.eop.domain.DeckFixture.card;
import static org.maglez.eop.domain.HandBuilder.aHand;
import static org.maglez.eop.domain.TrickBuilder.aTrick;
import static org.maglez.eop.domain.TrickPlayBuilder.aPlayBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Trick")
class TrickTest {

    private static final UUID TRICK_ID = new UUID(1000, 3);

    private static final Card SPOOFING_FOUR = card(StrideCategory.SPOOFING, Rank.FOUR);
    private static final Card SPOOFING_NINE = card(StrideCategory.SPOOFING, Rank.NINE);
    private static final Card SPOOFING_QUEEN = card(StrideCategory.SPOOFING, Rank.QUEEN);
    private static final Card SPOOFING_KING = card(StrideCategory.SPOOFING, Rank.KING);
    private static final Card TAMPERING_TWO = card(StrideCategory.TAMPERING, Rank.TWO);
    private static final Card REPUDIATION_KING = card(StrideCategory.REPUDIATION, Rank.KING);
    private static final Card DENIAL_KING = card(StrideCategory.DENIAL_OF_SERVICE, Rank.KING);
    private static final Card TRUMP_TWO = card(StrideCategory.ELEVATION_OF_PRIVILEGE, Rank.TWO);
    private static final Card TRUMP_FOUR = card(StrideCategory.ELEVATION_OF_PRIVILEGE, Rank.FOUR);
    private static final Card TRUMP_TEN = card(StrideCategory.ELEVATION_OF_PRIVILEGE, Rank.TEN);
    private static final Card TRUMP_JACK = card(StrideCategory.ELEVATION_OF_PRIVILEGE, Rank.JACK);

    private static final Set<Integer> FOUR_SEATS = Set.of(0, 1, 2, 3);
    private static final Set<Integer> THREE_SEATS = Set.of(0, 1, 2);

    @Nested
    @DisplayName("rejects a trick that could not have arisen from legal play")
    class Validation {

        @Test
        @DisplayName("a null identifier or a null list of plays")
        void shouldRejectNulls() {
            assertThatNullPointerException().isThrownBy(() -> aTrick().withTrickId(null).build());
            assertThatNullPointerException().isThrownBy(() -> aTrick().withPlays((List<TrickPlay>) null).build());
        }

        @Test
        @DisplayName("a sequence below one, because tricks are numbered from one")
        void shouldRejectSequenceBelowOne() {
            assertThatIllegalArgumentException().isThrownBy(() -> aTrick().withSequence(0).build());
        }

        @Test
        @DisplayName("a leading seat outside the table")
        void shouldRejectLeaderSeatOutsideTheTable() {
            assertThatIllegalArgumentException().isThrownBy(() -> aTrick().withLeaderSeat(-1).build());
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> aTrick().withLeaderSeat(GameSession.MAXIMUM_PLAYERS).build());
        }

        @Test
        @DisplayName("one seat playing twice in the same trick")
        void shouldRejectASeatPlayingTwice() {
            assertThatIllegalArgumentException().isThrownBy(() -> aTrick()
                    .withPlays(aPlayBy(0, SPOOFING_FOUR).build(), aPlayBy(0, SPOOFING_NINE).build())
                    .build());
        }

        @Test
        @DisplayName("the same card played twice in the same trick")
        void shouldRejectTheSameCardTwice() {
            assertThatIllegalArgumentException().isThrownBy(() -> aTrick()
                    .withPlays(aPlayBy(0, SPOOFING_FOUR).build(), aPlayBy(1, SPOOFING_FOUR).build())
                    .build());
        }

        @Test
        @DisplayName("a winning play that is not one of the plays in the trick")
        void shouldRejectAForeignWinner() {
            assertThatIllegalArgumentException().isThrownBy(() -> aTrick()
                    .withPlays(aPlayBy(0, SPOOFING_FOUR).build())
                    .withWinner(aPlayBy(1, SPOOFING_KING).build())
                    .build());
        }

        @Test
        @DisplayName("a first play that does not belong to the leading seat")
        void shouldRejectAFirstPlayFromTheWrongSeat() {
            assertThatIllegalArgumentException().isThrownBy(() -> aTrick()
                    .withLeaderSeat(0)
                    .withPlays(aPlayBy(1, SPOOFING_FOUR).build())
                    .build());
        }

        @Test
        @DisplayName("plays that do not run clockwise from the leading seat, which no legal trick can produce")
        void shouldRejectPlaysOutOfClockwiseOrder() {
            assertThatIllegalArgumentException().isThrownBy(() -> aTrick()
                    .withLeaderSeat(0)
                    .withPlays(
                            aPlayBy(0, SPOOFING_KING).build(),
                            aPlayBy(3, SPOOFING_NINE).build(),
                            aPlayBy(1, SPOOFING_FOUR).build())
                    .build())
                    .withMessageContaining("clockwise");
        }

        @Test
        @DisplayName("but accepts a clockwise run that skips a seat, because a seat out of cards is skipped")
        void shouldAcceptAClockwiseRunThatSkipsASeat() {
            final Trick trick = aTrick()
                    .withLeaderSeat(1)
                    .withPlays(
                            aPlayBy(1, SPOOFING_KING).build(),
                            aPlayBy(3, SPOOFING_NINE).build())
                    .build();

            assertThat(trick.plays()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("knows the suit that was led")
    class LedSuit {

        @Test
        @DisplayName("nothing is led until the first card is played")
        void shouldHaveNoLedSuitWhileOpen() {
            assertThat(Trick.open(TRICK_ID, 1, 2).ledSuit()).isEmpty();
        }

        @Test
        @DisplayName("the first card played sets the led suit")
        void shouldTakeTheLedSuitFromTheFirstPlay() {
            final Trick trick = Trick.open(TRICK_ID, 1, 0).play(aPlayBy(0, TAMPERING_TWO).build());

            assertThat(trick.ledSuit()).contains(StrideCategory.TAMPERING);
        }

        @Test
        @DisplayName("and a later card of another suit does not change it")
        void shouldNotChangeTheLedSuitOnALaterPlay() {
            final Trick trick = Trick.open(TRICK_ID, 1, 0)
                    .play(aPlayBy(0, TAMPERING_TWO).build())
                    .play(aPlayBy(1, TRUMP_TEN).build());

            assertThat(trick.ledSuit()).contains(StrideCategory.TAMPERING);
        }
    }

    @Nested
    @DisplayName("enforces follow-suit against the hand the player actually holds")
    class FollowingSuit {

        private final Trick spoofingLed =
                Trick.open(TRICK_ID, 1, 0).play(aPlayBy(0, SPOOFING_FOUR).build());

        @Test
        @DisplayName("refuses a card the hand does not hold, which is what stops a card being played twice")
        void shouldRefuseACardNotHeld() {
            final Hand hand = aHand().withCards(SPOOFING_NINE).build();

            assertThatExceptionOfType(CardNotInHandException.class)
                    .isThrownBy(() -> spoofingLed.assertLegalPlay(SPOOFING_KING, hand))
                    .satisfies(thrown -> {
                        assertThat(thrown.handId()).isEqualTo(hand.handId());
                        assertThat(thrown.cardId()).isEqualTo(SPOOFING_KING.cardId());
                    });
        }

        @Test
        @DisplayName("lets the leader play anything, because no suit has been led yet")
        void shouldLetTheLeaderPlayAnything() {
            final Hand hand = aHand().withCards(TRUMP_TWO, SPOOFING_NINE).build();

            Trick.open(TRICK_ID, 1, 0).assertLegalPlay(TRUMP_TWO, hand);
        }

        @Test
        @DisplayName("allows a card of the led suit")
        void shouldAllowFollowingSuit() {
            final Hand hand = aHand().withCards(SPOOFING_KING, TRUMP_TWO).build();

            spoofingLed.assertLegalPlay(SPOOFING_KING, hand);
        }

        @Test
        @DisplayName("refuses another suit while the hand can still follow, naming both suits")
        void shouldRefuseAnotherSuitWhenAbleToFollow() {
            final Hand hand = aHand().withCards(SPOOFING_KING, TRUMP_TWO).build();

            assertThatExceptionOfType(MustFollowSuitException.class)
                    .isThrownBy(() -> spoofingLed.assertLegalPlay(TRUMP_TWO, hand))
                    .satisfies(thrown -> {
                        assertThat(thrown.ledSuit()).isEqualTo(StrideCategory.SPOOFING);
                        assertThat(thrown.attemptedSuit()).isEqualTo(StrideCategory.ELEVATION_OF_PRIVILEGE);
                    });
        }

        @Test
        @DisplayName("allows any card once the hand is void of the led suit, trump included")
        void shouldAllowAnyCardWhenVoidOfTheLedSuit() {
            final Hand hand = aHand().withCards(TRUMP_TWO, REPUDIATION_KING).build();

            spoofingLed.assertLegalPlay(TRUMP_TWO, hand);
            spoofingLed.assertLegalPlay(REPUDIATION_KING, hand);
        }

        @Test
        @DisplayName("treats a null card as a card the hand does not hold, rather than as a programming error")
        void shouldRefuseANullCard() {
            final Hand hand = aHand().withCards(SPOOFING_KING).build();

            assertThatExceptionOfType(CardNotInHandException.class)
                    .isThrownBy(() -> spoofingLed.assertLegalPlay(null, hand))
                    .satisfies(thrown -> {
                        assertThat(thrown.handId()).isEqualTo(hand.handId());
                        assertThat(thrown.cardId()).isNull();
                    });
        }

        @Test
        @DisplayName("refuses a null hand, because there is then nothing to check the play against")
        void shouldRefuseANullHand() {
            assertThatNullPointerException()
                    .isThrownBy(() -> spoofingLed.assertLegalPlay(SPOOFING_KING, null))
                    .withMessageContaining("hand");
        }
    }

    @Nested
    @DisplayName("works out whose turn it is, clockwise, skipping seats out of cards")
    class TurnOrder {

        @Test
        @DisplayName("the leading seat plays first")
        void shouldStartWithTheLeader() {
            assertThat(Trick.open(TRICK_ID, 1, 2).seatToPlay(FOUR_SEATS)).hasValue(2);
        }

        @Test
        @DisplayName("nobody plays if the leading seat holds no cards")
        void shouldHaveNoSeatToPlayIfTheLeaderIsOutOfCards() {
            assertThat(Trick.open(TRICK_ID, 1, 3).seatToPlay(THREE_SEATS)).isEmpty();
        }

        @Test
        @DisplayName("play passes to the next seat clockwise")
        void shouldPassClockwise() {
            final Trick trick = Trick.open(TRICK_ID, 1, 0).play(aPlayBy(0, SPOOFING_FOUR).build());

            assertThat(trick.seatToPlay(FOUR_SEATS)).hasValue(1);
        }

        @Test
        @DisplayName("and wraps back to seat zero from the highest seat")
        void shouldWrapBackToSeatZero() {
            final Trick trick = Trick.open(TRICK_ID, 1, 3).play(aPlayBy(3, SPOOFING_FOUR).build());

            assertThat(trick.seatToPlay(FOUR_SEATS)).hasValue(0);
        }

        @Test
        @DisplayName("skips a seat that has run out of cards, which is what makes the last trick short")
        void shouldSkipASeatOutOfCards() {
            final Trick trick = Trick.open(TRICK_ID, 1, 0).play(aPlayBy(0, SPOOFING_FOUR).build());

            assertThat(trick.seatToPlay(Set.of(0, 2, 3))).hasValue(2);
        }

        @Test
        @DisplayName("skips seats that do not exist at this table, without being told the table size")
        void shouldSkipSeatsThatDoNotExist() {
            final Trick trick = Trick.open(TRICK_ID, 1, 2)
                    .play(aPlayBy(2, SPOOFING_FOUR).build());

            assertThat(trick.seatToPlay(THREE_SEATS)).hasValue(0);
        }

        @Test
        @DisplayName("has nobody left to play once every eligible seat has played")
        void shouldHaveNoSeatLeftWhenComplete() {
            final Trick trick = Trick.open(TRICK_ID, 1, 0)
                    .play(aPlayBy(0, SPOOFING_FOUR).build())
                    .play(aPlayBy(1, SPOOFING_KING).build())
                    .play(aPlayBy(2, REPUDIATION_KING).build());

            assertThat(trick.seatToPlay(THREE_SEATS)).isEmpty();
            assertThat(trick.isComplete(THREE_SEATS)).isTrue();
        }

        @Test
        @DisplayName("an open trick with no plays is not complete")
        void shouldNotBeCompleteWhileEmpty() {
            assertThat(Trick.open(TRICK_ID, 1, 0).isComplete(THREE_SEATS)).isFalse();
        }

        @Test
        @DisplayName("is not complete while a seat still owes a card")
        void shouldNotBeCompleteWhileASeatOwesACard() {
            final Trick trick = Trick.open(TRICK_ID, 1, 0)
                    .play(aPlayBy(0, SPOOFING_FOUR).build())
                    .play(aPlayBy(1, SPOOFING_KING).build());

            assertThat(trick.isComplete(THREE_SEATS)).isFalse();
        }

        @Test
        @DisplayName("knows which seats have already played")
        void shouldKnowWhoHasPlayed() {
            final Trick trick = Trick.open(TRICK_ID, 1, 0).play(aPlayBy(0, SPOOFING_FOUR).build());

            assertThat(trick.hasPlayed(0)).isTrue();
            assertThat(trick.hasPlayed(1)).isFalse();
        }

        /**
         * The last trick of an uneven deal is short: the seats dealt fewer cards have already run out,
         * so fewer cards are played than there are seats at the table. Because the whole deck is dealt
         * and nothing is discarded (ADR-023 Decision 1, reinstated by EOP-92), a three-player table
         * holding 23/23/22 reaches exactly this state on its final trick. Completion is decided against
         * the seats that still hold cards rather than against the table size, so a short trick resolves
         * normally — and a seat with an empty hand is never waited on.
         */
        @Test
        @DisplayName("completes a short final trick once the seats that still hold cards have played")
        void shouldCompleteAShortFinalTrick() {
            final Set<Integer> stillHolding = Set.of(0, 1);

            final Trick trick = Trick.open(TRICK_ID, 1, 0)
                    .play(aPlayBy(0, SPOOFING_FOUR).build())
                    .play(aPlayBy(1, SPOOFING_KING).build());

            assertThat(trick.isComplete(stillHolding))
                    .as("two of three seats have played, and the third holds no card, so the trick is done")
                    .isTrue();
            assertThat(trick.seatToPlay(stillHolding))
                    .as("an empty-handed seat is never asked for a card")
                    .isEmpty();
            assertThat(trick.isComplete(THREE_SEATS))
                    .as("the same trick is incomplete if the exhausted seat is wrongly said to hold a card")
                    .isFalse();
        }

        @Test
        @DisplayName("requires the set of seats holding cards, because it cannot count to the table size")
        void shouldRequireTheSeatsHoldingCards() {
            assertThatNullPointerException().isThrownBy(() -> Trick.open(TRICK_ID, 1, 0).seatToPlay(null));
        }
    }

    @Nested
    @DisplayName("rejects a play from a seat whose turn it is not")
    class OutOfTurn {

        @Test
        @DisplayName("accepts the seat whose turn it is")
        void shouldAcceptTheSeatInTurn() {
            Trick.open(TRICK_ID, 1, 1).assertSeatMayPlay(1, THREE_SEATS);
        }

        @Test
        @DisplayName("refuses another seat, naming the seat that is actually next")
        void shouldRefuseAnotherSeat() {
            final Trick trick = Trick.open(TRICK_ID, 1, 0).play(aPlayBy(0, SPOOFING_FOUR).build());

            assertThatExceptionOfType(OutOfTurnException.class)
                    .isThrownBy(() -> trick.assertSeatMayPlay(2, THREE_SEATS))
                    .satisfies(thrown -> {
                        assertThat(thrown.expectedSeat()).isEqualTo(1);
                        assertThat(thrown.attemptedSeat()).isEqualTo(2);
                    });
        }

        @Test
        @DisplayName("refuses any play into a completed trick")
        void shouldRefuseAPlayIntoACompletedTrick() {
            final Trick trick = Trick.open(TRICK_ID, 1, 0)
                    .play(aPlayBy(0, SPOOFING_FOUR).build())
                    .play(aPlayBy(1, SPOOFING_KING).build())
                    .play(aPlayBy(2, REPUDIATION_KING).build());

            assertThatIllegalStateException().isThrownBy(() -> trick.assertSeatMayPlay(0, THREE_SEATS));
        }

        @Test
        @DisplayName("says the trick was opened on a cardless seat rather than calling it complete, "
                + "because those are different faults and the message decides where a reader looks")
        void shouldSayWhenOpenedOnACardlessSeat() {
            final Trick openedOnAnEmptySeat = Trick.open(TRICK_ID, 20, 3);

            assertThatIllegalStateException()
                    .isThrownBy(() -> openedOnAnEmptySeat.assertSeatMayPlay(3, THREE_SEATS))
                    .satisfies(thrown -> assertThat(thrown.getMessage())
                            .contains("holds no cards")
                            .doesNotContain("is complete"));
        }
    }

    @Nested
    @DisplayName("adds a play without mutating the trick that was played into")
    class Playing {

        @Test
        @DisplayName("returns a new trick holding the play in order")
        void shouldReturnANewTrick() {
            final Trick open = Trick.open(TRICK_ID, 1, 0);

            final Trick after = open.play(aPlayBy(0, SPOOFING_FOUR).build())
                    .play(aPlayBy(1, SPOOFING_KING).build());

            assertThat(open.plays()).isEmpty();
            assertThat(after.plays()).extracting(play -> play.card().rank())
                    .containsExactly(Rank.FOUR, Rank.KING);
            assertThat(after.trickId()).isEqualTo(TRICK_ID);
            assertThat(after.sequence()).isEqualTo(1);
            assertThat(after.leaderSeat()).isZero();
        }

        @Test
        @DisplayName("refuses a null play")
        void shouldRefuseANullPlay() {
            assertThatNullPointerException().isThrownBy(() -> Trick.open(TRICK_ID, 1, 0).play(null));
        }

        @Test
        @DisplayName("refuses a play into a trick that has already been resolved")
        void shouldRefuseAPlayIntoAResolvedTrick() {
            final Trick resolved = Trick.open(TRICK_ID, 1, 0)
                    .play(aPlayBy(0, SPOOFING_FOUR).build())
                    .resolved();

            assertThatIllegalStateException()
                    .isThrownBy(() -> resolved.play(aPlayBy(1, SPOOFING_KING).build()));
        }

        @Test
        @DisplayName("exposes an unmodifiable list of plays")
        void shouldExposeAnUnmodifiableListOfPlays() {
            final Trick trick = Trick.open(TRICK_ID, 1, 0).play(aPlayBy(0, SPOOFING_FOUR).build());

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> trick.plays().clear());
        }
    }

    @Nested
    @DisplayName("resolves the winner: highest of the led suit unless trump was played")
    class Resolution {

        private Trick trickOf(final TrickPlay... plays) {
            return aTrick().withLeaderSeat(plays[0].seatOrder()).withPlays(plays).build().resolved();
        }

        @Test
        @DisplayName("the highest card of the led suit takes the trick")
        void shouldAwardToTheHighestOfTheLedSuit() {
            final Trick resolved = trickOf(
                    aPlayBy(0, SPOOFING_FOUR).build(),
                    aPlayBy(1, SPOOFING_KING).build(),
                    aPlayBy(2, SPOOFING_NINE).build());

            assertThat(resolved.winner()).isPresent();
            assertThat(resolved.winningSeat()).isEqualTo(1);
        }

        @Test
        @DisplayName("cards of neither the led suit nor trump cannot take it, however high")
        void shouldIgnoreOffSuitCards() {
            final Trick resolved = trickOf(
                    aPlayBy(0, SPOOFING_FOUR).build(),
                    aPlayBy(1, REPUDIATION_KING).build(),
                    aPlayBy(2, DENIAL_KING).build());

            assertThat(resolved.winningSeat()).isZero();
        }

        @Test
        @DisplayName("a single trump beats the highest card of the led suit")
        void shouldAwardToTrumpOverTheLedSuit() {
            final Trick resolved = trickOf(
                    aPlayBy(0, SPOOFING_KING).build(),
                    aPlayBy(1, TRUMP_TWO).build(),
                    aPlayBy(2, SPOOFING_QUEEN).build());

            assertThat(resolved.winningSeat()).isEqualTo(1);
        }

        @Test
        @DisplayName("the highest trump takes it when more than one was played")
        void shouldAwardToTheHighestTrump() {
            final Trick resolved = trickOf(
                    aPlayBy(0, SPOOFING_KING).build(),
                    aPlayBy(1, TRUMP_TEN).build(),
                    aPlayBy(2, TRUMP_TWO).build());

            assertThat(resolved.winningSeat()).isEqualTo(1);
        }

        @Test
        @DisplayName("and it is the rank that decides, not the order the trumps were played in")
        void shouldNotAwardToTheFirstTrumpPlayed() {
            final Trick resolved = trickOf(
                    aPlayBy(0, SPOOFING_KING).build(),
                    aPlayBy(1, TRUMP_TWO).build(),
                    aPlayBy(2, TRUMP_TEN).build());

            assertThat(resolved.winningSeat()).isEqualTo(2);
        }

        @Test
        @DisplayName("works when Elevation of Privilege is itself the led suit")
        void shouldResolveWhenTrumpIsLed() {
            final Trick resolved = trickOf(
                    aPlayBy(0, TRUMP_FOUR).build(),
                    aPlayBy(1, TRUMP_JACK).build(),
                    aPlayBy(2, SPOOFING_KING).build());

            assertThat(resolved.winningSeat()).isEqualTo(1);
        }

        @Test
        @DisplayName("a play that linked no threat still takes the trick, because failing to link only scores nothing")
        void shouldAwardToAnUnlinkedPlay() {
            final Trick resolved = trickOf(
                    aPlayBy(0, SPOOFING_FOUR).build(),
                    aPlayBy(1, SPOOFING_KING).withThreatLinked(false).build());

            assertThat(resolved.winningSeat()).isEqualTo(1);
            assertThat(resolved.winner()).get().extracting(TrickPlay::threatLinked).isEqualTo(false);
        }

        @Test
        @DisplayName("resolves a short trick, where a seat had already run out of cards")
        void shouldResolveAShortTrick() {
            final Trick resolved = trickOf(
                    aPlayBy(1, SPOOFING_FOUR).build(), aPlayBy(3, SPOOFING_NINE).build());

            assertThat(resolved.winningSeat()).isEqualTo(3);
        }

        @Test
        @DisplayName("refuses to resolve a trick with no plays")
        void shouldRefuseToResolveAnEmptyTrick() {
            assertThatIllegalStateException().isThrownBy(() -> Trick.open(TRICK_ID, 1, 0).resolved());
        }

        @Test
        @DisplayName("refuses to resolve a trick twice, so a winner cannot be quietly rewritten")
        void shouldRefuseToResolveTwice() {
            final Trick resolved = Trick.open(TRICK_ID, 1, 0)
                    .play(aPlayBy(0, SPOOFING_FOUR).build())
                    .resolved();

            assertThatIllegalStateException().isThrownBy(resolved::resolved);
        }

        @Test
        @DisplayName("does not know who leads next until it has been resolved")
        void shouldNotKnowTheNextLeaderWhileUnresolved() {
            final Trick unresolved = Trick.open(TRICK_ID, 1, 0).play(aPlayBy(0, SPOOFING_FOUR).build());

            assertThat(unresolved.winner()).isEmpty();
            assertThatIllegalStateException().isThrownBy(unresolved::winningSeat);
        }
    }

    @Nested
    @DisplayName("compares by value and renders without naming a card")
    class Representation {

        @Test
        @DisplayName("two tricks with the same plays are equal and share a hash code")
        void shouldBeEqualByValue() {
            final Trick one = Trick.open(TRICK_ID, 1, 0).play(aPlayBy(0, SPOOFING_FOUR).build());
            final Trick other = Trick.open(TRICK_ID, 1, 0).play(aPlayBy(0, SPOOFING_FOUR).build());

            assertThat(one).isEqualTo(other).hasSameHashCodeAs(other);
            assertThat(one).isNotEqualTo(Trick.open(TRICK_ID, 1, 0));
        }

        @Test
        @DisplayName("renders the count and the led suit but no card")
        void shouldNameNoCard() {
            final Trick trick = Trick.open(TRICK_ID, 1, 0).play(aPlayBy(0, TAMPERING_TWO).build());

            assertThat(trick.toString())
                    .contains("plays=1")
                    .contains("ledSuit=TAMPERING")
                    .contains("resolved=false")
                    .doesNotContain("There's a way");
        }

        @Test
        @DisplayName("renders no led suit while the trick is open")
        void shouldRenderNoLedSuitWhileOpen() {
            assertThat(Trick.open(TRICK_ID, 1, 0).toString()).contains("ledSuit=none");
        }

        @Test
        @DisplayName("says so once the trick has been resolved")
        void shouldRenderResolved() {
            final Trick resolved = aTrick()
                    .withLeaderSeat(0)
                    .withPlays(aPlayBy(0, SPOOFING_KING).build())
                    .build()
                    .resolved();

            assertThat(resolved.toString()).contains("resolved=true");
        }
    }

    @Nested
    @DisplayName("accepts a play only through the one guarded entry point")
    class GuardedEntryPoint {

        private Trick spoofingLed() {
            return Trick.open(TRICK_ID, 1, 0).play(aPlayBy(0, SPOOFING_KING).build());
        }

        /**
         * A hand whose identifiers match what {@link TrickPlayBuilder#aPlayBy(int, Card)} derives for the same
         * seat, so a play and the hand it is checked against agree on who owns them without either test
         * restating the identifier.
         */
        private Hand handFor(final int seat, final Card... cards) {
            return aHand()
                    .withHandId(new UUID(800, seat))
                    .withPlayerId(new UUID(700, seat))
                    .withCards(cards)
                    .build();
        }

        /** Files the given hands at seats zero upwards, which is the only seating a session can produce. */
        private Hands table(final Hand... hands) {
            final Map<Integer, Hand> bySeat = new LinkedHashMap<>();
            for (int seat = 0; seat < hands.length; seat++) {
                bySeat.put(seat, hands[seat]);
            }
            return Hands.reconstitute(bySeat);
        }

        /** A full table where only seat one's holding varies, since seat one is the seat to play. */
        private Hands tableWithSeatOneHolding(final Card... cards) {
            return table(
                    handFor(0, SPOOFING_NINE),
                    handFor(1, cards),
                    handFor(2, DENIAL_KING),
                    handFor(3, TRUMP_TEN));
        }

        private Card relabelled(final Card held, final StrideCategory suit, final Rank rank) {
            return CardBuilder.aCard()
                    .withCardId(held.cardId())
                    .withSuit(suit)
                    .withRank(rank)
                    .build();
        }

        @Test
        @DisplayName("accepts a card the player was dealt, played in turn")
        void shouldAcceptALegalPlay() {
            final Hands hands = tableWithSeatOneHolding(SPOOFING_FOUR, TAMPERING_TWO);

            final Trick after = spoofingLed().acceptPlay(1, aPlayBy(1, SPOOFING_FOUR).build(), hands);

            assertThat(after.plays()).hasSize(2);
            assertThat(after.plays().get(1).card()).isEqualTo(SPOOFING_FOUR);
            assertThat(after.plays().get(1).seatOrder()).isEqualTo(1);
        }

        @Test
        @DisplayName("refuses a play that claims a seat other than the one the caller is acting for")
        void shouldRefuseAClaimedSeat() {
            final Hands hands = table(
                    handFor(0, SPOOFING_NINE),
                    handFor(1, SPOOFING_FOUR),
                    handFor(2, DENIAL_KING),
                    handFor(3, TRUMP_TEN));

            assertThatExceptionOfType(NotYourSeatException.class)
                    .isThrownBy(() -> spoofingLed().acceptPlay(2, aPlayBy(1, SPOOFING_FOUR).build(), hands))
                    .satisfies(thrown -> {
                        assertThat(thrown.occupiedSeat()).isEqualTo(2);
                        assertThat(thrown.claimedSeat()).isEqualTo(1);
                    });
        }

        @Test
        @DisplayName("refuses a card relabelled as the led suit to escape following suit")
        void shouldRefuseAForgedLedSuit() {
            final Hands hands = tableWithSeatOneHolding(SPOOFING_FOUR, TAMPERING_TWO);
            final Card forged = relabelled(TAMPERING_TWO, StrideCategory.SPOOFING, Rank.KING);

            assertThatExceptionOfType(MustFollowSuitException.class)
                    .isThrownBy(() -> spoofingLed().acceptPlay(1, aPlayBy(1, forged).build(), hands))
                    .satisfies(thrown -> {
                        assertThat(thrown.ledSuit()).isEqualTo(StrideCategory.SPOOFING);
                        assertThat(thrown.attemptedSuit()).isEqualTo(StrideCategory.TAMPERING);
                    });
        }

        @Test
        @DisplayName("records the card that was dealt, not the card the request described")
        void shouldRecordTheDealtCard() {
            final Hands hands = tableWithSeatOneHolding(TAMPERING_TWO);
            final Card forged = relabelled(TAMPERING_TWO, StrideCategory.ELEVATION_OF_PRIVILEGE, Rank.KING);

            final Trick after = spoofingLed().acceptPlay(1, aPlayBy(1, forged).build(), hands);

            assertThat(after.plays().get(1).card()).isEqualTo(TAMPERING_TWO);
            assertThat(after.plays().get(1).card().isTrump()).isFalse();
        }

        @Test
        @DisplayName("so a relabelled card cannot take a trick it was never able to take")
        void shouldNotLetAForgedCardWin() {
            final Hands hands = table(handFor(0, SPOOFING_NINE), handFor(1, TAMPERING_TWO));
            final Card forged = relabelled(TAMPERING_TWO, StrideCategory.ELEVATION_OF_PRIVILEGE, Rank.KING);

            final Trick resolved = spoofingLed()
                    .acceptPlay(1, aPlayBy(1, forged).build(), hands)
                    .resolved();

            assertThat(resolved.winningSeat()).isZero();
            assertThat(resolved.winner()).map(TrickPlay::card).contains(SPOOFING_KING);
        }

        @Test
        @DisplayName("refuses a play naming a player other than the one who occupies the seat")
        void shouldRefuseAnotherPlayersIdentifier() {
            final Hands hands = tableWithSeatOneHolding(SPOOFING_FOUR);
            final TrickPlay impersonating = TrickPlayBuilder.aTrickPlay()
                    .withTrickPlayId(new UUID(900, 9))
                    .withPlayerId(new UUID(700, 2))
                    .withSeatOrder(1)
                    .withCard(SPOOFING_FOUR)
                    .build();

            assertThatExceptionOfType(PlayerMismatchException.class)
                    .isThrownBy(() -> spoofingLed().acceptPlay(1, impersonating, hands))
                    .satisfies(thrown -> {
                        assertThat(thrown.seatOrder()).isEqualTo(1);
                        assertThat(thrown.occupant()).isEqualTo(new UUID(700, 1));
                        assertThat(thrown.namedPlayer()).isEqualTo(new UUID(700, 2));
                    });
        }

        @Test
        @DisplayName("refuses a negative acting seat as a broken credential mapping, not as impersonation")
        void shouldRefuseNegativeActingSeat() {
            final Hands hands = tableWithSeatOneHolding(SPOOFING_FOUR);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> spoofingLed().acceptPlay(-1, aPlayBy(1, SPOOFING_FOUR).build(), hands))
                    .withMessageContaining("not a seat at this table");
        }

        @Test
        @DisplayName("refuses an acting seat beyond the six the game seats")
        void shouldRefuseActingSeatBeyondTable() {
            final Hands hands = tableWithSeatOneHolding(SPOOFING_FOUR);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> spoofingLed()
                            .acceptPlay(GameSession.MAXIMUM_PLAYERS, aPlayBy(1, SPOOFING_FOUR).build(), hands))
                    .withMessageContaining("not a seat at this table");
        }

        @Test
        @DisplayName("refuses an acting seat that was never dealt a hand")
        void shouldRefuseAnUnseatedActingSeat() {
            final Hands hands = table(handFor(0, SPOOFING_NINE), handFor(1, SPOOFING_FOUR), handFor(2, DENIAL_KING));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> spoofingLed().acceptPlay(4, aPlayBy(4, TRUMP_TEN).build(), hands))
                    .withMessageContaining("No hand was dealt to seat 4");
        }

        @Test
        @DisplayName("refuses a card the player does not hold")
        void shouldRefuseACardNotHeld() {
            final Hands hands = tableWithSeatOneHolding(TAMPERING_TWO);

            assertThatExceptionOfType(CardNotInHandException.class)
                    .isThrownBy(() -> spoofingLed().acceptPlay(1, aPlayBy(1, SPOOFING_FOUR).build(), hands))
                    .satisfies(thrown -> assertThat(thrown.cardId()).isEqualTo(SPOOFING_FOUR.cardId()));
        }

        @Test
        @DisplayName("refuses a play from a seat whose turn it is not")
        void shouldRefuseOutOfTurn() {
            final Hands hands = tableWithSeatOneHolding(SPOOFING_FOUR);

            assertThatExceptionOfType(OutOfTurnException.class)
                    .isThrownBy(() -> spoofingLed().acceptPlay(2, aPlayBy(2, DENIAL_KING).build(), hands))
                    .satisfies(thrown -> {
                        assertThat(thrown.expectedSeat()).isEqualTo(1);
                        assertThat(thrown.attemptedSeat()).isEqualTo(2);
                    });
        }

        @Test
        @DisplayName("works out whose turn it is from the hands, not from anything the caller supplied")
        void shouldDeriveTheSeatsHoldingCards() {
            final Hands seatOneOutOfCards = table(
                    handFor(0, SPOOFING_NINE),
                    handFor(1),
                    handFor(2, DENIAL_KING),
                    handFor(3, TRUMP_TEN));

            assertThatExceptionOfType(OutOfTurnException.class)
                    .isThrownBy(() -> spoofingLed().acceptPlay(1, aPlayBy(1, SPOOFING_FOUR).build(), seatOneOutOfCards))
                    .satisfies(thrown -> assertThat(thrown.expectedSeat()).isEqualTo(2));
        }

        @Test
        @DisplayName("checks the turn before the hand, so a player out of turn learns nothing about their cards")
        void shouldCheckTurnBeforeHand() {
            final Hands hands = tableWithSeatOneHolding(SPOOFING_FOUR);

            assertThatExceptionOfType(OutOfTurnException.class)
                    .isThrownBy(() -> spoofingLed().acceptPlay(2, aPlayBy(2, SPOOFING_KING).build(), hands));
        }

        @Test
        @DisplayName("refuses a null play or a null set of hands")
        void shouldRefuseNulls() {
            final Hands hands = tableWithSeatOneHolding(SPOOFING_FOUR);

            assertThatNullPointerException()
                    .isThrownBy(() -> spoofingLed().acceptPlay(1, null, hands));
            assertThatNullPointerException()
                    .isThrownBy(() -> spoofingLed().acceptPlay(1, aPlayBy(1, SPOOFING_FOUR).build(), null));
        }
    }

    @Nested
    @DisplayName("passes the lead to a seat that can actually use it")
    class PassingTheLead {

        private Trick wonBy(final int leaderSeat, final TrickPlay... plays) {
            return aTrick().withLeaderSeat(leaderSeat).withPlays(plays).build().resolved();
        }

        @Test
        @DisplayName("the winner leads the next trick while they still hold a card")
        void shouldLetTheWinnerLead() {
            final Trick resolved = wonBy(0, aPlayBy(0, SPOOFING_FOUR).build(), aPlayBy(1, SPOOFING_NINE).build());

            assertThat(resolved.winningSeat()).isEqualTo(1);
            assertThat(resolved.nextLeaderSeat(FOUR_SEATS)).hasValue(1);
        }

        @Test
        @DisplayName("a winner who has just played their last card passes the lead on clockwise")
        void shouldPassTheLeadFromACardlessWinner() {
            final Trick resolved = wonBy(0, aPlayBy(0, SPOOFING_FOUR).build(), aPlayBy(1, SPOOFING_NINE).build());

            assertThat(resolved.nextLeaderSeat(Set.of(0, 2, 3))).hasValue(2);
        }

        @Test
        @DisplayName("and wraps round the table to find one, rather than opening a trick nobody can play into")
        void shouldWrapToFindALeader() {
            final Trick resolved = wonBy(3, aPlayBy(3, SPOOFING_NINE).build(), aPlayBy(0, SPOOFING_FOUR).build());

            assertThat(resolved.winningSeat()).isEqualTo(3);
            assertThat(resolved.nextLeaderSeat(Set.of(1))).hasValue(1);
        }

        @Test
        @DisplayName("has no answer once every hand is empty, which is how the game ends")
        void shouldHaveNoLeaderWhenNoCardsRemain() {
            final Trick resolved = wonBy(0, aPlayBy(0, SPOOFING_FOUR).build());

            assertThat(resolved.nextLeaderSeat(Set.of())).isEmpty();
        }

        @Test
        @DisplayName("refuses to answer before the trick has been resolved")
        void shouldRefuseWhileUnresolved() {
            final Trick unresolved = Trick.open(TRICK_ID, 1, 0).play(aPlayBy(0, SPOOFING_FOUR).build());

            assertThatIllegalStateException().isThrownBy(() -> unresolved.nextLeaderSeat(FOUR_SEATS));
        }

        @Test
        @DisplayName("refuses a null set of seats")
        void shouldRefuseNullSeats() {
            final Trick resolved = wonBy(0, aPlayBy(0, SPOOFING_FOUR).build());

            assertThatNullPointerException().isThrownBy(() -> resolved.nextLeaderSeat(null));
        }
    }

    @Nested
    @DisplayName("refuses a stored play list that no legal play could have produced")
    class StoredInvariants {

        @Test
        @DisplayName("refuses one player holding two seats, which is the stored form of a seat claimed by impersonation")
        void shouldRefuseOnePlayerAtTwoSeats() {
            final TrickPlay first = aPlayBy(0, SPOOFING_KING).build();
            final TrickPlay second = TrickPlayBuilder.aTrickPlay()
                    .withTrickPlayId(new UUID(900, 21))
                    .withPlayerId(first.playerId())
                    .withSeatOrder(1)
                    .withCard(SPOOFING_FOUR)
                    .build();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> aTrick().withLeaderSeat(0).withPlays(first, second).build())
                    .withMessageContaining("two seats");
        }

        @Test
        @DisplayName("refuses two plays sharing an identifier, which no primary key would have allowed")
        void shouldRefuseDuplicatePlayIdentifiers() {
            final TrickPlay first = aPlayBy(0, SPOOFING_KING).build();
            final TrickPlay second = TrickPlayBuilder.aTrickPlay()
                    .withTrickPlayId(first.trickPlayId())
                    .withPlayerId(new UUID(700, 1))
                    .withSeatOrder(1)
                    .withCard(SPOOFING_FOUR)
                    .build();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> aTrick().withLeaderSeat(0).withPlays(first, second).build())
                    .withMessageContaining("share an identifier");
        }

        @Test
        @DisplayName("still accepts an ordinary trick in which every play differs in seat, player and identifier")
        void shouldAcceptAWellFormedTrick() {
            final Trick trick = aTrick()
                    .withLeaderSeat(0)
                    .withPlays(aPlayBy(0, SPOOFING_KING).build(), aPlayBy(1, SPOOFING_FOUR).build())
                    .build();

            assertThat(trick.plays()).hasSize(2);
        }
    }
}
