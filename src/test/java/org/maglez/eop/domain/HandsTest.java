package org.maglez.eop.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.maglez.eop.domain.DeckFixture.card;
import static org.maglez.eop.domain.DeckFixture.fullDeck;
import static org.maglez.eop.domain.DeckFixture.seats;
import static org.maglez.eop.domain.HandBuilder.aHand;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Hands")
class HandsTest {

    private static Hand handAt(final int seat, final Card... cards) {
        return aHand()
                .withHandId(new UUID(800, seat))
                .withPlayerId(new UUID(700, seat))
                .withCards(cards)
                .build();
    }

    private static Map<Integer, Hand> at(final Hand... hands) {
        final Map<Integer, Hand> bySeat = new LinkedHashMap<>();
        for (int seat = 0; seat < hands.length; seat++) {
            bySeat.put(seat, hands[seat]);
        }
        return bySeat;
    }

    @Nested
    @DisplayName("deals every card, so nothing is discarded")
    class Dealing {

        @Test
        @DisplayName("three players hold twenty-three, twenty-three and twenty-two (68 = 23 + 23 + 22, 0 discarded)")
        void shouldDealEvenlyToThree() {
            final Hands hands = Hands.deal(fullDeck(), seats(3));

            assertThat(hands.handOf(0).size()).isEqualTo(23);
            assertThat(hands.handOf(1).size()).isEqualTo(23);
            assertThat(hands.handOf(2).size()).isEqualTo(22);
            assertThat(hands.totalCards()).isEqualTo(68);
        }

        @Test
        @DisplayName("six players hold twelve, twelve then eleven each (68 = 12 + 12 + 11 * 4, 0 discarded)")
        void shouldDealEvenlyToSix() {
            final Hands hands = Hands.deal(fullDeck(), seats(6));

            assertThat(hands.seats()).containsExactly(0, 1, 2, 3, 4, 5);
            assertThat(List.of(
                            hands.handOf(0).size(),
                            hands.handOf(1).size(),
                            hands.handOf(2).size(),
                            hands.handOf(3).size(),
                            hands.handOf(4).size(),
                            hands.handOf(5).size()))
                    .containsExactly(12, 12, 11, 11, 11, 11);
            assertThat(hands.totalCards()).isEqualTo(68);
        }

        @Test
        @DisplayName("four players each hold seventeen, because 68 divides evenly by four (0 discarded)")
        void shouldDealEqualHandsAtFour() {
            final Hands hands = Hands.deal(fullDeck(), seats(4));

            assertThat(List.of(
                            hands.handOf(0).size(),
                            hands.handOf(1).size(),
                            hands.handOf(2).size(),
                            hands.handOf(3).size()))
                    .containsExactly(17, 17, 17, 17);
            assertThat(hands.totalCards()).isEqualTo(68);
        }

        @Test
        @DisplayName("five players hold fourteen, fourteen, fourteen then thirteen each (68 = 14 * 3 + 13 * 2, 0 discarded)")
        void shouldDealEqualHandsAtFive() {
            final Hands hands = Hands.deal(fullDeck(), seats(5));

            assertThat(List.of(
                            hands.handOf(0).size(),
                            hands.handOf(1).size(),
                            hands.handOf(2).size(),
                            hands.handOf(3).size(),
                            hands.handOf(4).size()))
                    .containsExactly(14, 14, 14, 13, 13);
            assertThat(hands.totalCards()).isEqualTo(68);
        }

        @Test
        @DisplayName("every card is dealt exactly once, and none is discarded (5 players: all 68 cards dealt)")
        void shouldDealEveryCardExactlyOnce() {
            final Hands hands = Hands.deal(fullDeck(), seats(5));

            final List<UUID> dealt = new ArrayList<>();
            hands.handsBySeat()
                    .values()
                    .forEach(hand -> hand.cards().forEach(dealtCard -> dealt.add(dealtCard.cardId())));

            assertThat(hands.totalCards()).isEqualTo(68);
            assertThat(dealt).hasSize(68).doesNotHaveDuplicates();
        }

        /**
         * The regression guard for EOP-92. A discarding deal passes every hand-size assertion above
         * while quietly losing cards, so the property worth pinning is conservation: the dealt cards are
         * exactly the deck, on every table size the game supports.
         */
        @ParameterizedTest(name = "{0} players")
        @ValueSource(ints = {3, 4, 5, 6})
        @DisplayName("conserves the deck at every supported table size — no card lost, none duplicated")
        void shouldDiscardNothingAtAnyTableSize(final int playerCount) {
            final List<Card> deck = fullDeck();
            final Hands hands = Hands.deal(deck, seats(playerCount));

            final List<UUID> dealt = hands.handsBySeat().values().stream()
                    .flatMap(hand -> hand.cards().stream())
                    .map(Card::cardId)
                    .toList();

            assertThat(dealt)
                    .as("every card in the deck reaches a hand, and no card is dealt twice")
                    .hasSize(deck.size())
                    .doesNotHaveDuplicates()
                    .containsExactlyInAnyOrderElementsOf(deck.stream().map(Card::cardId).toList());
        }

        @ParameterizedTest(name = "{0} players")
        @ValueSource(ints = {3, 4, 5, 6})
        @DisplayName("spreads the surplus of an uneven deal across the lowest seats, one card at most apart")
        void shouldPlaceTheSurplusOnTheLowestSeats(final int playerCount) {
            final Hands hands = Hands.deal(fullDeck(), seats(playerCount));

            final List<Integer> sizes = hands.handsBySeat().values().stream()
                    .map(hand -> hand.cards().size())
                    .toList();

            assertThat(sizes)
                    .as("no seat holds more than one card more than another")
                    .isSortedAccordingTo((left, right) -> Integer.compare(right, left));
            assertThat(sizes.getFirst() - sizes.getLast())
                    .as("the largest and smallest hands differ by at most one card")
                    .isBetween(0, 1);
        }

        @Test
        @DisplayName("deals the lowest Tampering card even when the shuffle puts it last, because nothing is discarded")
        void shouldAlwaysDealTheLowestTamperingCard() {
            // Move the lowest Tampering card (THREE) to the very end of the deck. Under the equal-hands
            // rule this position was discarded for five players, which is what forced EOP-72 to swap it
            // into the kept range. Dealing everything makes the position irrelevant.
            final List<Card> deck = new ArrayList<>(fullDeck());
            final Card tamperingThree = deck.stream()
                    .filter(c -> c.suit() == StrideCategory.TAMPERING && c.rank() == Rank.THREE)
                    .findFirst()
                    .orElseThrow();
            deck.remove(tamperingThree);
            deck.add(tamperingThree);

            final Hands hands = Hands.deal(deck, seats(5));

            // The opening leader must be findable — this throws if no Tampering card was dealt.
            final int leaderSeat = hands.openingLeaderSeat();
            assertThat(leaderSeat).isBetween(0, 4);

            // The THREE of TAMPERING must be in one of the hands.
            final boolean tamperingThreeDealt = hands.handsBySeat().values().stream()
                    .flatMap(hand -> hand.cards().stream())
                    .anyMatch(c -> c.suit() == StrideCategory.TAMPERING && c.rank() == Rank.THREE);
            assertThat(tamperingThreeDealt).isTrue();
        }

        @Test
        @DisplayName("hands the last card of the deck to a seat rather than dropping it")
        void shouldDealTheFinalCardOfTheDeck() {
            final List<Card> deck = fullDeck();
            final Card last = deck.getLast();

            final Hands hands = Hands.deal(deck, seats(3));

            assertThat(hands.handsBySeat().values().stream()
                            .flatMap(hand -> hand.cards().stream())
                            .map(Card::cardId))
                    .as("the card at the end of an unevenly dividing deck is still dealt")
                    .contains(last.cardId());
        }

        @Test
        @DisplayName("deals the same way twice, because the order given is the order dealt")
        void shouldBeDeterministic() {
            assertThat(Hands.deal(fullDeck(), seats(4))).isEqualTo(Hands.deal(fullDeck(), seats(4)));
        }

        @Test
        @DisplayName("takes the seats in ascending order however they were listed")
        void shouldSortSeatsBeforeDealing() {
            final List<Hands.Seat> reversed = new ArrayList<>(seats(4));
            java.util.Collections.reverse(reversed);

            assertThat(Hands.deal(fullDeck(), reversed)).isEqualTo(Hands.deal(fullDeck(), seats(4)));
        }

        @Test
        @DisplayName("refuses to deal to fewer players than the game needs")
        void shouldRejectTooFewSeats() {
            assertThatIllegalArgumentException().isThrownBy(() -> Hands.deal(fullDeck(), seats(2)));
        }

        @Test
        @DisplayName("refuses to deal to more players than the table seats")
        void shouldRejectTooManySeats() {
            final List<Hands.Seat> tooMany = new ArrayList<>(seats(6));
            tooMany.add(new Hands.Seat(0, new UUID(700, 99), new UUID(800, 99)));

            assertThatIllegalArgumentException().isThrownBy(() -> Hands.deal(fullDeck(), tooMany));
        }

        @Test
        @DisplayName("refuses a deck too small to give every player a card")
        void shouldRejectADeckSmallerThanTheTable() {
            final List<Card> twoCards = List.of(
                    card(StrideCategory.TAMPERING, Rank.TWO), card(StrideCategory.SPOOFING, Rank.THREE));

            assertThatIllegalArgumentException().isThrownBy(() -> Hands.deal(twoCards, seats(3)));
        }

        @Test
        @DisplayName("refuses a null deck or null seats")
        void shouldRejectNulls() {
            assertThatNullPointerException().isThrownBy(() -> Hands.deal(null, seats(3)));
            assertThatNullPointerException().isThrownBy(() -> Hands.deal(fullDeck(), null));
        }
    }

    @Nested
    @DisplayName("derives the opening lead from the cards actually dealt")
    class OpeningLead {

        @Test
        @DisplayName("the seat holding the lowest Tampering card dealt leads")
        void shouldLeadFromTheLowestTamperingCard() {
            final Hands hands = Hands.reconstitute(at(
                    handAt(0, card(StrideCategory.TAMPERING, Rank.NINE)),
                    handAt(1, card(StrideCategory.TAMPERING, Rank.FOUR)),
                    handAt(2, card(StrideCategory.TAMPERING, Rank.KING))));

            assertThat(hands.openingLeaderSeat()).isEqualTo(1);
        }

        @Test
        @DisplayName("the two of Tampering leads when the deck holds one, as the seeded deck does")
        void shouldLeadFromTheTwoWhenDealt() {
            final Hands hands = Hands.reconstitute(at(
                    handAt(0, card(StrideCategory.TAMPERING, Rank.THREE)),
                    handAt(1, card(StrideCategory.TAMPERING, Rank.FIVE)),
                    handAt(2, card(StrideCategory.TAMPERING, Rank.TWO))));

            assertThat(hands.openingLeaderSeat()).isEqualTo(2);
        }

        @Test
        @DisplayName("the three of Tampering leads when no two was dealt, as in the printed deck")
        void shouldLeadFromTheThreeWhenNoTwoWasDealt() {
            final Hands hands = Hands.reconstitute(at(
                    handAt(0, card(StrideCategory.TAMPERING, Rank.SEVEN)),
                    handAt(1, card(StrideCategory.TAMPERING, Rank.THREE)),
                    handAt(2, card(StrideCategory.TAMPERING, Rank.QUEEN))));

            assertThat(hands.openingLeaderSeat()).isEqualTo(1);
        }

        @Test
        @DisplayName("ignores lower cards of every other suit, because Tampering is where the game starts")
        void shouldIgnoreOtherSuits() {
            final Hands hands = Hands.reconstitute(at(
                    handAt(0, card(StrideCategory.SPOOFING, Rank.TWO)),
                    handAt(1, card(StrideCategory.REPUDIATION, Rank.TWO)),
                    handAt(2, card(StrideCategory.TAMPERING, Rank.KING))));

            assertThat(hands.openingLeaderSeat()).isEqualTo(2);
        }

        @Test
        @DisplayName("fails loudly when no Tampering card was dealt at all, rather than picking a leader")
        void shouldRejectADealWithNoTamperingCard() {
            final Hands hands = Hands.reconstitute(at(
                    handAt(0, card(StrideCategory.SPOOFING, Rank.TWO)),
                    handAt(1, card(StrideCategory.REPUDIATION, Rank.THREE)),
                    handAt(2, card(StrideCategory.ELEVATION_OF_PRIVILEGE, Rank.KING))));

            assertThatExceptionOfType(NoTamperingCardDealtException.class)
                    .isThrownBy(hands::openingLeaderSeat)
                    .satisfies(thrown -> assertThat(thrown.cardsDealt()).isEqualTo(3));
        }

        @Test
        @DisplayName("derives a leader from a real deal such that no other seat holds a lower tampering card")
        void shouldDeriveALeaderFromARealDeal() {
            final Hands hands = Hands.deal(fullDeck(), seats(4));

            final int leader = hands.openingLeaderSeat();

            final Card led = hands.handOf(leader).lowestOf(StrideCategory.TAMPERING).orElseThrow();
            assertThat(hands.seats())
                    .allSatisfy(seat -> hands.handOf(seat)
                            .lowestOf(StrideCategory.TAMPERING)
                            .ifPresent(lowest -> assertThat(led.rank().beats(lowest.rank())).isFalse()));
        }
    }

    @Nested
    @DisplayName("rejects an impossible set of hands, including one rebuilt from storage")
    class Validation {

        @Test
        @DisplayName("a seat beyond the table")
        void shouldRejectSeatBeyondTable() {
            final Map<Integer, Hand> beyond = new HashMap<>();
            beyond.put(GameSession.MAXIMUM_PLAYERS, handAt(0, card(StrideCategory.TAMPERING, Rank.TWO)));

            assertThatIllegalArgumentException().isThrownBy(() -> Hands.reconstitute(beyond));
        }

        @Test
        @DisplayName("no hands at all")
        void shouldRejectNoHands() {
            assertThatIllegalArgumentException().isThrownBy(() -> Hands.reconstitute(Map.of()));
        }

        @Test
        @DisplayName("one player holding two hands")
        void shouldRejectOnePlayerWithTwoHands() {
            final Map<Integer, Hand> clash = new LinkedHashMap<>();
            clash.put(0, aHand().withHandId(new UUID(800, 0)).withPlayerId(new UUID(700, 0))
                    .withCards(card(StrideCategory.TAMPERING, Rank.TWO)).build());
            clash.put(1, aHand().withHandId(new UUID(800, 1)).withPlayerId(new UUID(700, 0))
                    .withCards(card(StrideCategory.TAMPERING, Rank.THREE)).build());

            assertThatIllegalArgumentException().isThrownBy(() -> Hands.reconstitute(clash));
        }

        @Test
        @DisplayName("two seats sharing one hand identifier")
        void shouldRejectSharedHandIdentifier() {
            final Map<Integer, Hand> clash = new LinkedHashMap<>();
            clash.put(0, aHand().withHandId(new UUID(800, 0)).withPlayerId(new UUID(700, 0))
                    .withCards(card(StrideCategory.TAMPERING, Rank.TWO)).build());
            clash.put(1, aHand().withHandId(new UUID(800, 0)).withPlayerId(new UUID(700, 1))
                    .withCards(card(StrideCategory.TAMPERING, Rank.THREE)).build());

            assertThatIllegalArgumentException().isThrownBy(() -> Hands.reconstitute(clash));
        }

        @Test
        @DisplayName("the same card dealt to two seats")
        void shouldRejectTheSameCardTwice() {
            final Card shared = card(StrideCategory.TAMPERING, Rank.TWO);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Hands.reconstitute(at(handAt(0, shared), handAt(1, shared))));
        }

        @Test
        @DisplayName("a null map, a null seat or a null hand")
        void shouldRejectNulls() {
            final Map<Integer, Hand> nullHand = new HashMap<>();
            nullHand.put(0, null);

            assertThatNullPointerException().isThrownBy(() -> Hands.reconstitute(null));
            assertThatNullPointerException().isThrownBy(() -> Hands.reconstitute(nullHand));
        }
    }

    @Nested
    @DisplayName("tracks what each seat still holds as cards are played")
    class Playing {

        private final Card tamperingTwo = card(StrideCategory.TAMPERING, Rank.TWO);
        private final Card spoofingKing = card(StrideCategory.SPOOFING, Rank.KING);
        private final Card repudiationFour = card(StrideCategory.REPUDIATION, Rank.FOUR);

        private Hands threeSeats() {
            return Hands.reconstitute(
                    at(handAt(0, tamperingTwo), handAt(1, spoofingKing), handAt(2, repudiationFour)));
        }

        @Test
        @DisplayName("playing a card removes it from that seat's hand only")
        void shouldRemoveThePlayedCard() {
            final Hands after = threeSeats().withCardPlayed(0, tamperingTwo);

            assertThat(after.handOf(0).isEmpty()).isTrue();
            assertThat(after.handOf(1).cards()).containsExactly(spoofingKing);
        }

        @Test
        @DisplayName("and leaves the original hands untouched")
        void shouldNotMutateTheOriginal() {
            final Hands before = threeSeats();

            before.withCardPlayed(0, tamperingTwo);

            assertThat(before.handOf(0).cards()).containsExactly(tamperingTwo);
            assertThat(before.totalCards()).isEqualTo(3);
        }

        @Test
        @DisplayName("refuses to play a card that seat does not hold, which is what stops a card being played twice")
        void shouldRejectACardTheSeatDoesNotHold() {
            assertThatExceptionOfType(CardNotInHandException.class)
                    .isThrownBy(() -> threeSeats().withCardPlayed(0, spoofingKing));
        }

        @Test
        @DisplayName("refuses a seat that was never dealt to")
        void shouldRejectAnUnknownSeat() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> threeSeats().withCardPlayed(4, tamperingTwo));
            assertThatIllegalArgumentException().isThrownBy(() -> threeSeats().handOf(4));
        }

        @Test
        @DisplayName("knows which seats are in the game")
        void shouldKnowItsSeats() {
            assertThat(threeSeats().hasSeat(1)).isTrue();
            assertThat(threeSeats().hasSeat(4)).isFalse();
        }

        @Test
        @DisplayName("reports only the seats still holding cards")
        void shouldReportSeatsHoldingCards() {
            final Hands after = threeSeats().withCardPlayed(1, spoofingKing);

            assertThat(after.seatsHoldingCards()).containsExactly(0, 2);
            assertThat(after.totalCards()).isEqualTo(2);
        }

        @Test
        @DisplayName("is not exhausted until every hand is empty")
        void shouldKnowWhenEveryHandIsEmpty() {
            final Hands partly = threeSeats().withCardPlayed(0, tamperingTwo);
            final Hands exhausted = partly.withCardPlayed(1, spoofingKing).withCardPlayed(2, repudiationFour);

            assertThat(partly.allEmpty()).isFalse();
            assertThat(exhausted.allEmpty()).isTrue();
            assertThat(exhausted.totalCards()).isZero();
        }
    }

    @Nested
    @DisplayName("keeps hands private and exposes no mutable state")
    class Privacy {

        @Test
        @DisplayName("names no card when rendered")
        void shouldNameNoCardInToString() {
            final Hands hands = Hands.deal(fullDeck(), seats(3));

            assertThat(hands.toString())
                    .contains("seats=3")
                    .contains("cardsRemaining=68")
                    .doesNotContain("TAMPERING")
                    .doesNotContain("There's a way");
        }

        @Test
        @DisplayName("exposes an unmodifiable map of hands")
        void shouldExposeAnUnmodifiableMap() {
            final Hands hands = Hands.deal(fullDeck(), seats(3));

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> hands.handsBySeat().clear());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> hands.seatsHoldingCards().clear());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> hands.seats().clear());
        }

        @Test
        @DisplayName("two identical deals are equal and share a hash code")
        void shouldBeEqualByValue() {
            final Hands one = Hands.deal(fullDeck(), seats(3));
            final Hands other = Hands.deal(fullDeck(), seats(3));

            assertThat(one).isEqualTo(other).hasSameHashCodeAs(other);
            assertThat(one).isNotEqualTo(Hands.deal(fullDeck(), seats(4)));
        }
    }

    @Nested
    @DisplayName("derives the opening lead from the cards in play, not from a rank written down")
    class DerivedOpeningLead {

        @Test
        @DisplayName("leads from the lowest tampering card dealt even when it is neither a two nor a three")
        void shouldLeadFromWhateverTheLowestTamperingCardIs() {
            final List<Card> deck = List.of(
                    DeckFixture.card(StrideCategory.SPOOFING, Rank.TWO),
                    DeckFixture.card(StrideCategory.TAMPERING, Rank.NINE),
                    DeckFixture.card(StrideCategory.TAMPERING, Rank.SEVEN),
                    DeckFixture.card(StrideCategory.TAMPERING, Rank.JACK),
                    DeckFixture.card(StrideCategory.SPOOFING, Rank.THREE),
                    DeckFixture.card(StrideCategory.SPOOFING, Rank.FOUR));

            final Hands hands = Hands.deal(deck, seats(3));
            final int leader = hands.openingLeaderSeat();

            assertThat(hands.handOf(leader).lowestOf(StrideCategory.TAMPERING))
                    .map(Card::rank)
                    .contains(Rank.SEVEN);
            assertThat(leader).isEqualTo(2);
        }

        @Test
        @DisplayName("picks the lowest even when that card sorts first in its holder's hand")
        void shouldPickTheLowestWhateverTheOrderInHand() {
            final Hands hands = Hands.reconstitute(at(
                    handAt(0, DeckFixture.card(StrideCategory.TAMPERING, Rank.TWO),
                            DeckFixture.card(StrideCategory.TAMPERING, Rank.NINE)),
                    handAt(1, DeckFixture.card(StrideCategory.TAMPERING, Rank.FIVE)),
                    handAt(2, DeckFixture.card(StrideCategory.SPOOFING, Rank.KING))));

            assertThat(hands.openingLeaderSeat()).isZero();
        }
    }

    @Nested
    @DisplayName("refuses a seat that is not at the table")
    class SeatBounds {

        @Test
        @DisplayName("a negative seat in a reconstituted set of hands")
        void shouldRejectNegativeSeatKey() {
            final Hand hand = handAt(0, DeckFixture.card(StrideCategory.SPOOFING, Rank.TWO));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Hands.reconstitute(Map.of(-1, hand)));
        }

        @Test
        @DisplayName("a negative seat on a dealing seat")
        void shouldRejectNegativeSeatOrder() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new Hands.Seat(-1, new UUID(700, 0), new UUID(800, 0)));
        }
    }

    @Nested
    @DisplayName("refuses a seating that could not have come from a real session")
    class SeatCollisions {

        @Test
        @DisplayName("refuses two seat assignments sharing a seat order, rather than silently dealing one seat two shares")
        void shouldRejectDuplicateSeatOrders() {
            final List<Hands.Seat> colliding = List.of(
                    new Hands.Seat(0, new UUID(700, 0), new UUID(800, 0)),
                    new Hands.Seat(1, new UUID(700, 1), new UUID(800, 1)),
                    new Hands.Seat(1, new UUID(700, 2), new UUID(800, 2)));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Hands.deal(DeckFixture.fullDeck(), colliding))
                    .withMessageContaining("share a seat order");
        }
    }
}
