package org.maglez.eop.outbound.security;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.maglez.eop.domain.Card;
import org.maglez.eop.application.port.out.DeckShuffler;
import org.springframework.stereotype.Component;

/**
 * Shuffles the deck from a cryptographically secure source.
 *
 * <p>{@link SecureRandom} rather than {@link java.util.Random} because the deck's
 * composition is public. Every player can read the whole card list from the deck
 * endpoint, so the only thing hidden about a deal is the order it came out in. A
 * predictable order — and {@code java.util.Random} is predictable from a few
 * dozen observed outputs, seeded or not — would let a player who has seen their
 * own hand work out everyone else's, which is most of the information the game
 * withholds on purpose.
 *
 * <p>{@link Collections#shuffle(List, java.util.Random)} rather than a
 * hand-written loop: it is a correct Fisher-Yates and takes the random source as
 * a parameter, so the only decision left here is which source to pass. Writing
 * the swap loop by hand would add a well-known off-by-one bias for no benefit.
 *
 * <p>The list handed in is copied before shuffling, never sorted in place. The
 * deck arrives from {@code CardRepository.findWholeDeck}, which is free to return
 * an immutable or shared list, and mutating it would either throw or quietly
 * poison the next deal.
 */
@Component
public class SecureRandomDeckShuffler implements DeckShuffler {

    private final SecureRandom random;

    /**
     * Creates a shuffler over the platform's default secure random source.
     *
     * <p>The no-argument {@link SecureRandom} constructor asks the platform for its
     * strongest available source and does not block on Linux the way reading
     * {@code /dev/random} would. No seed is supplied anywhere: a seed that a test
     * could set is a seed an operator could accidentally pin, and a pinned seed
     * would make every table in the deployment deal the same game.
     */
    public SecureRandomDeckShuffler() {
        this.random = new SecureRandom();
    }

    @Override
    public List<Card> shuffle(final List<Card> deck) {
        Objects.requireNonNull(deck, "deck is required");
        final List<Card> shuffled = new ArrayList<>(deck);
        Collections.shuffle(shuffled, random);
        return List.copyOf(shuffled);
    }
}
