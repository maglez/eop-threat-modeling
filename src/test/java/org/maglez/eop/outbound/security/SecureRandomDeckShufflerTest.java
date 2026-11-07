package org.maglez.eop.outbound.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.domain.Card;
import org.maglez.eop.domain.DeckFixture;


/**
 * Measures the one property of a shuffle that only the real implementation can be held to.
 *
 * <p>Every use case above this class is tested against a deterministic stand-in, because a
 * predictable permutation is what makes a deal assertable. That leaves exactly one thing
 * unmeasured, and it is measured here: that this implementation returns a genuine permutation of
 * what it was given, keeps its hands off the caller's list, and does not hand the deck back in the
 * order it arrived.
 *
 * <p>The assertion that the order changes is the only probabilistic one in the suite. A shuffle of
 * the sixty-eight card deck has 68! possible outcomes, so the chance of two independent shuffles
 * agreeing with each other by luck is far smaller than the chance of the machine running this test
 * failing mid-run. The assertion is written over two shuffles rather than one so that it fails for
 * the defect it is aimed at — an implementation that forgot to shuffle at all, or that shuffled a
 * copy and returned the original — rather than merely for an unlucky draw.
 *
 * <p>Preserving the caller's list matters more than it looks. The deck arrives from
 * {@code CardRepository.findWholeDeck()}, which builds it from an immutable {@code List.copyOf},
 * and a future caching adapter could hand out the same instance to every table. An implementation
 * that shuffled in place would either throw on the immutable list or, worse, reorder a shared deck
 * under every other session at once.
 */
@DisplayName("SecureRandomDeckShuffler")
class SecureRandomDeckShufflerTest {

    private final SecureRandomDeckShuffler shuffler = new SecureRandomDeckShuffler();

    @Test
    @DisplayName("returns every card it was given, exactly once")
    void shouldReturnAPermutationOfTheDeck() {
        final var deck = DeckFixture.fullDeck();

        final var shuffled = shuffler.shuffle(deck);

        assertThat(shuffled).hasSameSizeAs(deck).containsExactlyInAnyOrderElementsOf(deck);
    }

    @Test
    @DisplayName("does not hand the deck back in the order it arrived")
    void shouldChangeTheOrder() {
        final var deck = DeckFixture.fullDeck();

        final List<Card> first = shuffler.shuffle(deck);
        final List<Card> second = shuffler.shuffle(deck);

        assertThat(first).isNotEqualTo(deck);
        assertThat(second).isNotEqualTo(deck);
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("leaves the caller's list untouched")
    void shouldNotMutateItsArgument() {
        final var deck = DeckFixture.fullDeck();
        final List<Card> asHandedOver = new ArrayList<>(deck);

        shuffler.shuffle(asHandedOver);

        assertThat(asHandedOver).containsExactlyElementsOf(deck);
    }

    @Test
    @DisplayName("accepts an immutable deck, which is the only kind the port ever hands over")
    void shouldAcceptAnImmutableList() {
        final List<Card> immutable = List.copyOf(DeckFixture.fullDeck());

        final var shuffled = shuffler.shuffle(immutable);

        assertThat(shuffled).containsExactlyInAnyOrderElementsOf(immutable);
    }

    @Test
    @DisplayName("returns a list the caller cannot reorder afterwards")
    void shouldReturnAnImmutableList() {
        final var shuffled = shuffler.shuffle(DeckFixture.fullDeck());

        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> shuffled.remove(0));
    }

    @Test
    @DisplayName("shuffles an empty deck into an empty deck rather than failing")
    void shouldShuffleAnEmptyDeck() {
        assertThat(shuffler.shuffle(List.of())).isEmpty();
    }

    @Test
    @DisplayName("refuses a null deck")
    void shouldRefuseANullDeck() {
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> shuffler.shuffle(null))
                .withMessageContaining("deck is required");
    }
}
