package org.maglez.eop.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds cards and decks for tests.
 *
 * <p>Not a builder in the {@link CardBuilder} sense: it does not vary one field of one card, it produces
 * the specific cards a trick-taking rule needs to be tested against. A follow-suit test needs a hand
 * that holds the led suit and one that does not; a deal test needs all sixty-eight cards.
 *
 * <p>Card identifiers are derived from the suit and rank rather than random, so the same card is always
 * the same identifier across a test. That matters because {@link Hand#holds(Card)} compares by
 * identifier: a fixture that minted a fresh identifier each call would make a hand fail to recognise
 * its own card, and the test would fail for a reason that has nothing to do with the rule under test.
 *
 * <p>Exists only in the test tree. Production code never constructs a card, because cards come from a
 * migration.
 */
public final class DeckFixture {

    private DeckFixture() {
    }

    /**
     * The one card of the given suit and rank, with a deterministic identifier.
     *
     * @param suit the STRIDE category the card belongs to
     * @param rank the card's rank
     * @return that card
     */
    public static Card card(final StrideCategory suit, final Rank rank) {
        return CardBuilder.aCard()
                .withCardId(new UUID(suit.deckOrder(), rank.value()))
                .withSuit(suit)
                .withRank(rank)
                .withThreatPrompt("There's a way to attack via " + suit.name() + " at rank " + rank.symbol())
                .build();
    }

    /**
     * Several cards of one suit.
     *
     * @param suit the STRIDE category the cards belong to
     * @param ranks the ranks to produce
     * @return those cards, in the order the ranks were given
     */
    public static List<Card> cards(final StrideCategory suit, final Rank... ranks) {
        final List<Card> cards = new ArrayList<>();
        for (final Rank rank : ranks) {
            cards.add(card(suit, rank));
        }
        return List.copyOf(cards);
    }

    /**
     * The whole printed deck: sixty-eight cards matching the physical Elevation of Privilege deck.
     *
     * <p>The printed deck omits cards that were absent from the original whitepaper deck:
     * Tampering starts at rank 3 (no rank 2), and Elevation of Privilege starts at rank 5
     * (no ranks 2, 3, or 4). Aces do not appear in the game; King is the highest rank.
     * All other suits run 2–K (twelve cards each).
     *
     * <p>Ordered by suit and then by rank, which is a deck fresh out of the box rather than a shuffled
     * one. Dealing an unshuffled deck is exactly what makes the distribution assertable.
     *
     * @return all sixty-eight cards
     */
    public static List<Card> fullDeck() {
        final List<Card> deck = new ArrayList<>();
        for (final StrideCategory suit : StrideCategory.values()) {
            for (final Rank rank : Rank.values()) {
                if (suit == StrideCategory.TAMPERING && rank == Rank.TWO) {
                    continue; // Tampering starts at 3 in the printed deck
                }
                if (suit == StrideCategory.ELEVATION_OF_PRIVILEGE
                        && (rank == Rank.TWO || rank == Rank.THREE || rank == Rank.FOUR)) {
                    continue; // Elevation of Privilege starts at 5 in the printed deck
                }
                deck.add(card(suit, rank));
            }
        }
        return List.copyOf(deck);
    }

    /**
     * Seat assignments for a table of the given size, seated from zero upwards with deterministic
     * player and hand identifiers.
     *
     * @param players how many seats to fill
     * @return the seat assignments, in ascending seat order
     */
    public static List<Hands.Seat> seats(final int players) {
        final List<Hands.Seat> seats = new ArrayList<>();
        for (int seat = 0; seat < players; seat++) {
            seats.add(new Hands.Seat(seat, new UUID(700, seat), new UUID(800, seat)));
        }
        return List.copyOf(seats);
    }
}
