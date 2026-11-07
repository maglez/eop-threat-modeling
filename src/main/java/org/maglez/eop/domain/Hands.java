package org.maglez.eop.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Every hand in a session, filed by seat.
 *
 * <p>This is where the deck becomes hands and where the opening lead is derived. Both are rules of the
 * game rather than mechanics of the application, so both live here rather than in a use case.
 *
 * <p>Immutable: playing a card returns a new {@code Hands}.
 *
 * <p>Pure domain type: no Spring, no Jakarta, no persistence annotations. The persistence adapter holds
 * its own separate mapped type.
 */
public final class Hands {

    private final Map<Integer, Hand> handsBySeat;

    private Hands(final Map<Integer, Hand> handsBySeat) {
        Objects.requireNonNull(handsBySeat, "handsBySeat is required");
        final Map<Integer, Hand> ordered = new TreeMap<>();
        handsBySeat.forEach((seat, hand) -> {
            Objects.requireNonNull(seat, "a seat is required");
            Objects.requireNonNull(hand, "a hand is required");
            if (seat < 0 || seat >= GameSession.MAXIMUM_PLAYERS) {
                throw new IllegalArgumentException(
                        "A seat must be between 0 and "
                                + (GameSession.MAXIMUM_PLAYERS - 1)
                                + " inclusive, so it cannot be "
                                + seat);
            }
            ordered.put(seat, hand);
        });
        if (ordered.isEmpty()) {
            throw new IllegalArgumentException("A session has at least one hand");
        }
        final long distinctPlayers =
                ordered.values().stream().map(Hand::playerId).distinct().count();
        if (distinctPlayers != ordered.size()) {
            throw new IllegalArgumentException("One player cannot hold two hands in the same session");
        }
        final long distinctHands = ordered.values().stream().map(Hand::handId).distinct().count();
        if (distinctHands != ordered.size()) {
            throw new IllegalArgumentException("Two seats cannot share one hand identifier");
        }
        final long distinctCards = ordered.values().stream()
                .flatMap(hand -> hand.cards().stream())
                .map(Card::cardId)
                .distinct()
                .count();
        if (distinctCards != ordered.values().stream().mapToInt(Hand::size).sum()) {
            throw new IllegalArgumentException("The same card cannot be dealt to two seats");
        }
        this.handsBySeat = Collections.unmodifiableMap(new LinkedHashMap<>(ordered));
    }

    /**
     * One seat about to be dealt to: which seat it is, who is sitting in it, and the identifier its hand
     * will be stored under.
     *
     * <p>The identifiers are minted by the caller rather than here, because they are UUID v7 values
     * produced by an injected generator (ADR-018) and a domain type has no business reaching for a
     * clock or a random source. Passing them in also makes a deal completely deterministic, which is
     * what lets a test assert the exact distribution.
     *
     * @param seatOrder the seat, zero-based and stable for the life of the session (ADR-019)
     * @param playerId the player sitting in that seat
     * @param handId the identifier for the hand to be dealt to that seat
     */
    public record Seat(int seatOrder, UUID playerId, UUID handId) {

        /** Validates the seat assignment. */
        public Seat {
            Objects.requireNonNull(playerId, "playerId is required");
            Objects.requireNonNull(handId, "handId is required");
            if (seatOrder < 0 || seatOrder >= GameSession.MAXIMUM_PLAYERS) {
                throw new IllegalArgumentException(
                        "A seat must be between 0 and "
                                + (GameSession.MAXIMUM_PLAYERS - 1)
                                + " inclusive, so it cannot be "
                                + seatOrder);
            }
        }
    }

    /**
     * Deals every card in the deck, so that nothing is discarded.
     *
     * <p>The deal is round-robin: the card at index {@code i} goes to the seat at index
     * {@code i % seats.size()}, with the seats taken in ascending order. The loop runs to the end of
     * the deck, so all {@code deckSize} cards are dealt (ADR-023 Decision 1, reinstated by EOP-92).
     *
     * <p>When the deck does not divide evenly the hands differ by at most one card, and the surplus
     * falls to the lowest seats — a consequence of the round-robin order rather than a separate rule.
     * Over the 68-card printed deck (ADR-041) that gives 23/23/22 for three players, 17 each for four,
     * 14/14/14/13/13 for five and 12/12/11/11/11/11 for six.
     *
     * <p>Dealing everything is what makes the opening-lead rule total: the lowest-ranked Tampering card
     * is in the deck, so it is in somebody's hand, so {@link #openingLeaderSeat()} always finds a
     * holder. EOP-72 briefly truncated the deck to equal hands and kept that guarantee by swapping the
     * lowest Tampering card into the kept range, but that swap always landed it on the last seat, which
     * handed the opening lead to the highest-seated player instead of leaving it to the shuffle. Dealing
     * every card removes the special case and the bias together.
     *
     * <p>An unequal deal means the last trick can be short. That is supported rather than tolerated:
     * {@link Trick#isComplete(java.util.Collection)} and {@link Trick#seatToPlay(java.util.Collection)}
     * are both told which seats still hold cards, so a trick is complete once every seat that still has
     * a card has played.
     *
     * <p>Shuffling is deliberately not done here. This method deals the deck in exactly the order it is
     * given, so a test can hand it a known order and assert the exact distribution. Randomising the
     * order is the use case's job, through an injected port.
     *
     * @param orderedDeck the deck in the order it is to be dealt, which the caller has already shuffled
     * @param seats the seats to deal to
     * @return the dealt hands, filed by seat
     * @throws IllegalArgumentException if the deck or the seats are empty, if the seats are fewer than
     *     the game allows, or if two seat assignments collide
     */
    public static Hands deal(final List<Card> orderedDeck, final List<Seat> seats) {
        Objects.requireNonNull(orderedDeck, "orderedDeck is required");
        Objects.requireNonNull(seats, "seats is required");

        final List<Seat> ordered = new ArrayList<>(seats);
        ordered.forEach(seat -> Objects.requireNonNull(seat, "a seat assignment is required"));
        ordered.sort((left, right) -> Integer.compare(left.seatOrder(), right.seatOrder()));

        if (ordered.size() < GameSession.MINIMUM_PLAYERS_TO_START) {
            throw new IllegalArgumentException(
                    "The deck is dealt when play starts, which takes at least "
                            + GameSession.MINIMUM_PLAYERS_TO_START
                            + " players, so it cannot be dealt to "
                            + ordered.size());
        }
        if (ordered.size() > GameSession.MAXIMUM_PLAYERS) {
            throw new IllegalArgumentException(
                    "A session seats at most "
                            + GameSession.MAXIMUM_PLAYERS
                            + " players, so the deck cannot be dealt to "
                            + ordered.size());
        }
        if (orderedDeck.size() < ordered.size()) {
            throw new IllegalArgumentException(
                    "A deck of "
                            + orderedDeck.size()
                            + " cards cannot be dealt to "
                            + ordered.size()
                            + " players, because a player would be dealt no card at all");
        }

        final long distinctSeatOrders = ordered.stream().map(Seat::seatOrder).distinct().count();
        if (distinctSeatOrders != ordered.size()) {
            throw new IllegalArgumentException("Two seat assignments cannot share a seat order");
        }

        // Deal the whole deck. Nothing is truncated and nothing is discarded, so the surplus of an
        // uneven deal falls to the lowest seats and the opening-lead card is always in somebody's hand.
        final int playerCount = ordered.size();

        final Map<Integer, List<Card>> dealt = new TreeMap<>();
        ordered.forEach(seat -> dealt.put(seat.seatOrder(), new ArrayList<>()));
        for (int index = 0; index < orderedDeck.size(); index++) {
            final Seat seat = ordered.get(index % playerCount);
            dealt.get(seat.seatOrder()).add(orderedDeck.get(index));
        }

        final Map<Integer, Hand> hands = new TreeMap<>();
        ordered.forEach(seat -> hands.put(
                seat.seatOrder(),
                Hand.of(seat.handId(), seat.playerId(), dealt.get(seat.seatOrder()))));
        return new Hands(hands);
    }

    /**
     * Rebuilds the hands of a session from stored state.
     *
     * <p>This is the only path used when a client reconnects or refreshes, because the database is the
     * only authority on what a player is holding (ADR-014).
     *
     * @param handsBySeat the stored hands, keyed by seat
     * @return the reconstituted hands
     */
    public static Hands reconstitute(final Map<Integer, Hand> handsBySeat) {
        return new Hands(handsBySeat);
    }

    /**
     * Derives the seat that leads the first trick: the seat holding the lowest-ranked Tampering card
     * that was actually dealt.
     *
     * <p>The rank is derived and never written down, because there is no single right answer to write.
     * The printed deck that ships with the game starts on the three of Tampering (rank 2 is absent),
     * and the 68-card deck this application seeds matches that. A literal three in this method would
     * be a defect against any future deck variant that starts differently.
     *
     * <p>Tampering is where the game starts on purpose rather than by accident: tampering threats are
     * common and easy to see, so the first player gets a decision they can actually make.
     *
     * <p>There can be no tie, because the deck holds no two cards of the same suit and rank.
     *
     * @return the seat that leads the first trick
     * @throws NoTamperingCardDealtException if no Tampering card was dealt at all, which no real deck
     *     can produce and which therefore means something upstream is wrong
     */
    public int openingLeaderSeat() {
        int leaderSeat = -1;
        Card lowest = null;
        for (final Map.Entry<Integer, Hand> entry : handsBySeat.entrySet()) {
            final Optional<Card> candidate = entry.getValue().lowestOf(StrideCategory.TAMPERING);
            if (candidate.isEmpty()) {
                continue;
            }
            if (lowest == null || lowest.rank().beats(candidate.get().rank())) {
                lowest = candidate.get();
                leaderSeat = entry.getKey();
            }
        }
        if (lowest == null) {
            throw new NoTamperingCardDealtException(totalCards());
        }
        return leaderSeat;
    }

    /**
     * The hand dealt to a seat.
     *
     * @param seatOrder the seat to look up
     * @return that seat's hand
     * @throws IllegalArgumentException if no hand was dealt to that seat
     */
    public Hand handOf(final int seatOrder) {
        final Hand hand = handsBySeat.get(seatOrder);
        if (hand == null) {
            throw new IllegalArgumentException("No hand was dealt to seat " + seatOrder);
        }
        return hand;
    }

    /**
     * Whether a hand was dealt to a seat.
     *
     * @param seatOrder the seat to test
     * @return true if that seat is in the game
     */
    public boolean hasSeat(final int seatOrder) {
        return handsBySeat.containsKey(seatOrder);
    }

    /**
     * Removes a card from a seat's hand, returning the new hands.
     *
     * @param seatOrder the seat playing the card
     * @param card the card being played
     * @return a new {@code Hands} with the card gone from that seat
     * @throws IllegalArgumentException if no hand was dealt to that seat
     * @throws CardNotInHandException if that seat does not hold the card
     */
    public Hands withCardPlayed(final int seatOrder, final Card card) {
        Objects.requireNonNull(card, "card is required");
        final Hand reduced = handOf(seatOrder).without(card);
        final Map<Integer, Hand> updated = new TreeMap<>(handsBySeat);
        updated.put(seatOrder, reduced);
        return new Hands(updated);
    }

    /**
     * The seats that still hold at least one card.
     *
     * <p>This is the set the turn-order and trick-completion rules are computed against, because after
     * the whole deck is dealt out unevenly some seats run out of cards before others (ADR-023).
     *
     * @return an unmodifiable set of seats, in ascending order
     */
    public Set<Integer> seatsHoldingCards() {
        final Set<Integer> holding = new LinkedHashSet<>();
        handsBySeat.forEach((seat, hand) -> {
            if (!hand.isEmpty()) {
                holding.add(seat);
            }
        });
        return Collections.unmodifiableSet(holding);
    }

    /**
     * Whether every hand is exhausted.
     *
     * <p>Running out of cards is one of the three ways the game ends, alongside running out of time and
     * running out of ways to connect a threat to the system (PRD §3.3).
     *
     * @return true when no seat holds a card
     */
    public boolean allEmpty() {
        return handsBySeat.values().stream().allMatch(Hand::isEmpty);
    }

    /**
     * The number of cards still held across all seats.
     *
     * @return the total cards remaining
     */
    public int totalCards() {
        return handsBySeat.values().stream().mapToInt(Hand::size).sum();
    }

    /**
     * The seats in this session, in ascending order.
     *
     * @return an unmodifiable set of seats
     */
    public Set<Integer> seats() {
        return handsBySeat.keySet();
    }

    /**
     * Every hand, keyed by seat, in ascending seat order.
     *
     * @return an unmodifiable map of seat to hand
     */
    public Map<Integer, Hand> handsBySeat() {
        return handsBySeat;
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Hands that)) {
            return false;
        }
        return handsBySeat.equals(that.handsBySeat);
    }

    @Override
    public int hashCode() {
        return handsBySeat.hashCode();
    }

    /**
     * A summary that names no card.
     *
     * <p>A hand is private information — knowing what an opponent holds is knowing what they can and
     * cannot follow with — and the default rendering of a collection of hands would leak every hand in
     * the game into any log line that interpolated it.
     *
     * @return a short description of the hands
     */
    @Override
    public String toString() {
        return "Hands[seats=" + handsBySeat.size() + ", cardsRemaining=" + totalCards() + "]";
    }
}
