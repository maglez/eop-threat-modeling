package org.maglez.eop.application.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.maglez.eop.domain.Card;
import org.maglez.eop.application.port.out.DeckShuffler;

/**
 * A {@link DeckShuffler} that reverses the deck instead of randomising it.
 *
 * <p>Reversal is chosen deliberately over the identity permutation. A shuffler that handed the deck
 * straight back would let a use case that forgot to shuffle at all pass every assertion, because the
 * dealt order would match the canonical one either way. Reversal is a permutation of the whole deck
 * that no accident produces, so a test can state exactly which order was dealt and know the shuffle
 * was the thing that produced it.
 *
 * <p>Randomness is what the production implementation is for, and it is the one property a
 * deterministic test cannot assert. That split is why {@link DeckShuffler} is a port at all: the
 * distribution is measured once, against the real generator, and every use-case test above it gets a
 * permutation it can predict.
 */
final class RecordingDeckShuffler implements DeckShuffler {

    private final List<String> order;

    private List<Card> received;

    private int calls;

    /**
     * Creates the shuffler.
     *
     * @param callOrder the shared call log that records when the shuffle happened relative to the
     *     writes the use case makes afterwards
     */
    RecordingDeckShuffler(final List<String> callOrder) {
        this.order = Objects.requireNonNull(callOrder, "callOrder is required");
    }

    @Override
    public List<Card> shuffle(final List<Card> deck) {
        Objects.requireNonNull(deck, "deck is required");

        order.add("shuffle");
        calls++;
        received = List.copyOf(deck);

        final List<Card> reversed = new ArrayList<>(deck);
        Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    /**
     * Returns the deck as it arrived, before reversal.
     *
     * @return the deck this shuffler was handed, or {@code null} if it was never called
     */
    List<Card> received() {
        return received;
    }

    /**
     * Returns how many times a shuffle was asked for.
     *
     * <p>A deal shuffles once. More than once would mean the deck was read and shuffled per seat,
     * which would produce a distribution nobody intended.
     *
     * @return the number of calls
     */
    int calls() {
        return calls;
    }
}
