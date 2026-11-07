package org.maglez.eop.application.port.out;

import java.util.List;
import org.maglez.eop.domain.Card;

/**
 * Port through which the dealing use case randomises the deck.
 *
 * <p>This exists because {@code Hands.deal} deliberately does not shuffle. The
 * deal is a pure, deterministic function of an ordered deck and a set of seats,
 * which is what makes it testable: a test hands it a known order and asserts
 * exactly which card landed where. Randomness in that method would have made
 * every one of those assertions probabilistic.
 *
 * <p>Declared in this layer rather than taken as a {@code java.util.Random}
 * parameter so that the security-relevant choice of source is made once, in the
 * adapter, and cannot be weakened by a caller passing a seeded generator.
 *
 * <p>The shuffle is a security control, not a fairness nicety. Every player can
 * see the deck's composition — it is published reference data — so a predictable
 * order lets a player who knows their own hand deduce everyone else's, and
 * knowing which cards are still out is most of the skill of the game. An
 * implementation must therefore draw from a cryptographically secure source.
 */
public interface DeckShuffler {

    /**
     * Returns the given cards in a random order.
     *
     * <p>Implementations must not modify the list they are given: the caller reads
     * the deck from a port that may return an immutable or cached list, and a
     * shuffle that mutated it would corrupt the next deal.
     *
     * @param deck the cards to shuffle, in any order
     * @return a new list holding exactly the same cards in random order
     * @throws NullPointerException if {@code deck} is null
     */
    List<Card> shuffle(List<Card> deck);
}
