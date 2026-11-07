package org.maglez.eop.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.maglez.eop.domain.DeckFixture.card;
import static org.maglez.eop.domain.HandBuilder.aHand;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Hand")
class HandTest {

    private static final UUID HAND_ID = new UUID(800, 7);
    private static final UUID PLAYER_ID = new UUID(700, 7);

    @Nested
    @DisplayName("rejects a malformed hand at construction")
    class Validation {

        @Test
        @DisplayName("null hand identifier")
        void shouldRejectNullHandId() {
            assertThatNullPointerException()
                    .isThrownBy(() -> Hand.of(null, PLAYER_ID, List.of()))
                    .withMessageContaining("handId");
        }

        @Test
        @DisplayName("null player identifier")
        void shouldRejectNullPlayerId() {
            assertThatNullPointerException()
                    .isThrownBy(() -> Hand.of(HAND_ID, null, List.of()))
                    .withMessageContaining("playerId");
        }

        @Test
        @DisplayName("null card list")
        void shouldRejectNullCards() {
            assertThatNullPointerException()
                    .isThrownBy(() -> Hand.of(HAND_ID, PLAYER_ID, null))
                    .withMessageContaining("cards");
        }

        @Test
        @DisplayName("a null card inside the list")
        void shouldRejectNullCardInList() {
            final List<Card> withNull = Arrays.asList(card(StrideCategory.SPOOFING, Rank.TWO), null);

            assertThatNullPointerException()
                    .isThrownBy(() -> Hand.of(HAND_ID, PLAYER_ID, withNull));
        }

        @Test
        @DisplayName("the same card twice, because the deck holds no duplicates")
        void shouldRejectDuplicateCards() {
            final Card spoofingTwo = card(StrideCategory.SPOOFING, Rank.TWO);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> Hand.of(HAND_ID, PLAYER_ID, List.of(spoofingTwo, spoofingTwo)));
        }

        @Test
        @DisplayName("but accepts an empty hand, which is what a hand becomes once every card is played")
        void shouldAcceptEmptyHand() {
            final Hand hand = Hand.of(HAND_ID, PLAYER_ID, List.of());

            assertThat(hand.isEmpty()).isTrue();
            assertThat(hand.size()).isZero();
        }
    }

    @Nested
    @DisplayName("orders its cards canonically")
    class Ordering {

        @Test
        @DisplayName("by suit in deck order, then by rank ascending")
        void shouldSortBySuitThenRank() {
            final Hand hand = aHand()
                    .withCards(
                            card(StrideCategory.ELEVATION_OF_PRIVILEGE, Rank.THREE),
                            card(StrideCategory.SPOOFING, Rank.KING),
                            card(StrideCategory.SPOOFING, Rank.FOUR),
                            card(StrideCategory.TAMPERING, Rank.KING))
                    .build();

            assertThat(hand.cards())
                    .extracting(Card::suit, Card::rank)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(StrideCategory.SPOOFING, Rank.FOUR),
                            org.assertj.core.groups.Tuple.tuple(StrideCategory.SPOOFING, Rank.KING),
                            org.assertj.core.groups.Tuple.tuple(StrideCategory.TAMPERING, Rank.KING),
                            org.assertj.core.groups.Tuple.tuple(StrideCategory.ELEVATION_OF_PRIVILEGE, Rank.THREE));
        }

        @Test
        @DisplayName("so two hands dealt the same cards in different orders are equal")
        void shouldBeEqualRegardlessOfDealingOrder() {
            final Card one = card(StrideCategory.SPOOFING, Rank.TWO);
            final Card two = card(StrideCategory.REPUDIATION, Rank.QUEEN);

            final Hand dealtOneFirst = Hand.of(HAND_ID, PLAYER_ID, List.of(one, two));
            final Hand dealtTwoFirst = Hand.of(HAND_ID, PLAYER_ID, List.of(two, one));

            assertThat(dealtOneFirst).isEqualTo(dealtTwoFirst);
            assertThat(dealtOneFirst).hasSameHashCodeAs(dealtTwoFirst);
        }

        @Test
        @DisplayName("and its card list cannot be modified by a caller")
        void shouldExposeAnUnmodifiableCardList() {
            final Hand hand = aHand().build();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> hand.cards().clear());
        }
    }

    @Nested
    @DisplayName("answers what it holds")
    class Holdings {

        @Test
        @DisplayName("holds a card it was dealt")
        void shouldHoldDealtCard() {
            final Card dealt = card(StrideCategory.TAMPERING, Rank.NINE);

            assertThat(aHand().withCards(dealt).build().holds(dealt)).isTrue();
        }

        @Test
        @DisplayName("does not hold a card it was not dealt")
        void shouldNotHoldUndealtCard() {
            final Hand hand = aHand().withCards(card(StrideCategory.TAMPERING, Rank.NINE)).build();

            assertThat(hand.holds(card(StrideCategory.TAMPERING, Rank.TEN))).isFalse();
        }

        @Test
        @DisplayName("recognises its card by identifier, which is what a request names")
        void shouldRecogniseCardByIdentifier() {
            final Card dealt = card(StrideCategory.TAMPERING, Rank.NINE);
            final Card sameIdentifierDifferentPrompt = CardBuilder.aCard()
                    .withCardId(dealt.cardId())
                    .withSuit(dealt.suit())
                    .withRank(dealt.rank())
                    .withThreatPrompt("A prompt worded differently by a client.")
                    .build();

            assertThat(aHand().withCards(dealt).build().holds(sameIdentifierDifferentPrompt)).isTrue();
        }

        @Test
        @DisplayName("refuses a null card rather than answering false, so no rule can be asked a malformed question")
        void shouldRejectNullCard() {
            assertThatNullPointerException()
                    .isThrownBy(() -> aHand().build().holds(null))
                    .withMessageContaining("card");
        }

        @Test
        @DisplayName("holds the led suit when it has any card of it")
        void shouldHoldSuit() {
            final Hand hand = aHand().withCards(card(StrideCategory.DENIAL_OF_SERVICE, Rank.SIX)).build();

            assertThat(hand.holdsSuit(StrideCategory.DENIAL_OF_SERVICE)).isTrue();
            assertThat(hand.holdsSuit(StrideCategory.REPUDIATION)).isFalse();
        }

        @Test
        @DisplayName("refuses a null suit rather than answering false, which would fail open on the follow-suit rule")
        void shouldRejectNullSuit() {
            assertThatNullPointerException()
                    .isThrownBy(() -> aHand().build().holdsSuit(null))
                    .withMessageContaining("suit");
        }
    }

    @Nested
    @DisplayName("finds its lowest card of a suit, which is how the opening lead is derived")
    class LowestOfSuit {

        @Test
        @DisplayName("returns the lowest rank held in that suit")
        void shouldReturnLowestRankOfSuit() {
            final Hand hand = aHand()
                    .withCards(
                            card(StrideCategory.TAMPERING, Rank.NINE),
                            card(StrideCategory.TAMPERING, Rank.THREE),
                            card(StrideCategory.TAMPERING, Rank.JACK),
                            card(StrideCategory.SPOOFING, Rank.TWO))
                    .build();

            assertThat(hand.lowestOf(StrideCategory.TAMPERING))
                    .map(Card::rank)
                    .contains(Rank.THREE);
        }

        @Test
        @DisplayName("returns nothing when the hand holds none of that suit")
        void shouldReturnEmptyWhenSuitAbsent() {
            final Hand hand = aHand().withCards(card(StrideCategory.SPOOFING, Rank.TWO)).build();

            assertThat(hand.lowestOf(StrideCategory.TAMPERING)).isEmpty();
        }

        @Test
        @DisplayName("keeps the lower card when the next one it looks at is higher")
        void shouldKeepTheLowerCardWhenTheNextIsHigher() {
            final Hand hand = aHand()
                    .withCards(
                            card(StrideCategory.TAMPERING, Rank.TWO),
                            card(StrideCategory.TAMPERING, Rank.NINE))
                    .build();

            assertThat(hand.lowestOf(StrideCategory.TAMPERING))
                    .map(Card::rank)
                    .contains(Rank.TWO);
        }

        @Test
        @DisplayName("refuses a null suit rather than answering empty, which would silently lose the opening lead")
        void shouldRejectNullSuit() {
            assertThatNullPointerException()
                    .isThrownBy(() -> aHand().build().lowestOf(null))
                    .withMessageContaining("suit");
        }
    }

    @Nested
    @DisplayName("plays a card by producing a new hand")
    class PlayingACard {

        @Test
        @DisplayName("removes the card played and leaves the rest")
        void shouldRemoveThePlayedCard() {
            final Card played = card(StrideCategory.TAMPERING, Rank.NINE);
            final Card kept = card(StrideCategory.SPOOFING, Rank.FOUR);
            final Hand hand = aHand().withCards(played, kept).build();

            final Hand after = hand.without(played);

            assertThat(after.holds(played)).isFalse();
            assertThat(after.holds(kept)).isTrue();
            assertThat(after.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("leaves the original hand untouched, because a hand is immutable")
        void shouldNotMutateTheOriginalHand() {
            final Card played = card(StrideCategory.TAMPERING, Rank.NINE);
            final Hand hand = aHand().withCards(played, card(StrideCategory.SPOOFING, Rank.FOUR)).build();

            hand.without(played);

            assertThat(hand.holds(played)).isTrue();
            assertThat(hand.size()).isEqualTo(2);
        }

        @Test
        @DisplayName("keeps the same identity, because it is the same hand with one card fewer")
        void shouldKeepIdentifiers() {
            final Card played = card(StrideCategory.TAMPERING, Rank.NINE);
            final Hand hand = aHand().withHandId(HAND_ID).withPlayerId(PLAYER_ID).withCards(played).build();

            final Hand after = hand.without(played);

            assertThat(after.handId()).isEqualTo(HAND_ID);
            assertThat(after.playerId()).isEqualTo(PLAYER_ID);
        }

        @Test
        @DisplayName("refuses a card it does not hold, which is what stops a card being played twice")
        void shouldRejectPlayingACardItDoesNotHold() {
            final Card played = card(StrideCategory.TAMPERING, Rank.NINE);
            final Hand hand = aHand().withHandId(HAND_ID).withCards(played).build();
            final Hand emptied = hand.without(played);

            assertThatExceptionOfType(CardNotInHandException.class)
                    .isThrownBy(() -> emptied.without(played))
                    .satisfies(thrown -> {
                        assertThat(thrown.handId()).isEqualTo(HAND_ID);
                        assertThat(thrown.cardId()).isEqualTo(played.cardId());
                    });
        }

        @Test
        @DisplayName("refuses a null card")
        void shouldRejectNullCard() {
            final Hand hand = aHand().build();

            assertThatExceptionOfType(CardNotInHandException.class)
                    .isThrownBy(() -> hand.without(null));
        }
    }

    @Nested
    @DisplayName("keeps its cards private")
    class Privacy {

        @Test
        @DisplayName("its rendering names no card, because a hand is private information")
        void shouldNotRenderItsCards() {
            final Card held = card(StrideCategory.TAMPERING, Rank.NINE);
            final Hand hand = aHand().withCards(held).build();

            final String rendered = hand.toString();

            assertThat(rendered).doesNotContain(held.threatPrompt());
            assertThat(rendered).doesNotContain(StrideCategory.TAMPERING.name());
            assertThat(rendered).doesNotContain(held.rank().symbol());
            assertThat(rendered).contains("cards=1");
        }
    }

    @Nested
    @DisplayName("resolves a card a request only named, so a claimed suit and rank cannot be believed")
    class Resolving {

        @Test
        @DisplayName("hands back the card as it was dealt")
        void shouldResolveToTheDealtCard() {
            final Card held = card(StrideCategory.TAMPERING, Rank.TWO);
            final Hand hand = aHand().withCards(held).build();

            assertThat(hand.resolve(held)).isEqualTo(held);
        }

        @Test
        @DisplayName("ignores a suit and rank the caller relabelled onto a card they do hold")
        void shouldIgnoreARelabelledSuitAndRank() {
            final Card held = card(StrideCategory.TAMPERING, Rank.TWO);
            final Hand hand = aHand().withCards(held).build();
            final Card forged = CardBuilder.aCard()
                    .withCardId(held.cardId())
                    .withSuit(StrideCategory.ELEVATION_OF_PRIVILEGE)
                    .withRank(Rank.KING)
                    .build();

            final Card resolved = hand.resolve(forged);

            assertThat(resolved.suit()).isEqualTo(StrideCategory.TAMPERING);
            assertThat(resolved.rank()).isEqualTo(Rank.TWO);
            assertThat(resolved.isTrump()).isFalse();
        }

        @Test
        @DisplayName("refuses a card the hand was never dealt")
        void shouldRefuseACardNotHeld() {
            final Hand hand = aHand().withCards(card(StrideCategory.TAMPERING, Rank.TWO)).build();
            final Card elsewhere = card(StrideCategory.SPOOFING, Rank.KING);

            assertThatExceptionOfType(CardNotInHandException.class)
                    .isThrownBy(() -> hand.resolve(elsewhere))
                    .satisfies(thrown -> {
                        assertThat(thrown.handId()).isEqualTo(hand.handId());
                        assertThat(thrown.cardId()).isEqualTo(elsewhere.cardId());
                    });
        }

        @Test
        @DisplayName("refuses a null candidate rather than resolving something arbitrary")
        void shouldRefuseNull() {
            assertThatExceptionOfType(CardNotInHandException.class)
                    .isThrownBy(() -> aHand().build().resolve(null));
        }
    }

    @Nested
    @DisplayName("finds the lowest card of a suit whichever way round the comparison falls")
    class LowestIsOrderIndependent {

        @Test
        @DisplayName("keeps the running lowest when the next card is higher")
        void shouldKeepTheRunningLowest() {
            final Hand hand = aHand()
                    .withCards(DeckFixture.card(StrideCategory.TAMPERING, Rank.TWO),
                            DeckFixture.card(StrideCategory.TAMPERING, Rank.NINE))
                    .build();

            assertThat(hand.lowestOf(StrideCategory.TAMPERING)).map(Card::rank).contains(Rank.TWO);
        }

        @Test
        @DisplayName("takes the next card when it is lower than the running lowest, so the answer does not depend on order")
        void shouldTakeTheLowerCandidate() {
            final Hand hand = aHand()
                    .withCards(DeckFixture.card(StrideCategory.TAMPERING, Rank.NINE),
                            DeckFixture.card(StrideCategory.TAMPERING, Rank.THREE),
                            DeckFixture.card(StrideCategory.TAMPERING, Rank.KING))
                    .build();

            assertThat(hand.lowestOf(StrideCategory.TAMPERING)).map(Card::rank).contains(Rank.THREE);
        }
    }
}
