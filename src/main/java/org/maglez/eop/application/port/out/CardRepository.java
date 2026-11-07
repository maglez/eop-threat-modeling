package org.maglez.eop.application.port.out;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.maglez.eop.domain.Card;
import org.maglez.eop.application.port.in.PageQuery;
import org.maglez.eop.application.port.in.PageResult;

/**
 * Port through which the application reads the threat card deck.
 *
 * <p>Declared here, in the layer that needs it, and implemented outward in the
 * persistence adapter. That is what keeps the dependency arrow pointing inwards:
 * the database depends on the application, not the reverse.
 *
 * <p>Read only by design. The deck is reference data seeded by migration, so
 * there is no save, no delete and no write endpoint anywhere above this port.
 */
public interface CardRepository {

    /**
     * Reads one page of the deck in canonical order: by suit, then ascending rank.
     *
     * @param query which page to read
     * @return the requested page, empty content if the page is past the end
     */
    PageResult<Card> findAll(PageQuery query);

    /**
     * Reads a single card.
     *
     * @param cardId the identifier to look up
     * @return the card, or empty if no card has that identifier
     */
    Optional<Card> findById(UUID cardId);

    /**
     * Reads the whole deck in canonical order: by suit, then ascending rank.
     *
     * <p>Deliberately not a page. Dealing needs every card, and a paginated deal
     * would be one forgotten loop away from dealing a truncated deck — a defect
     * that produces a playable-looking game with cards missing, which is far
     * worse than a failure. The deck is seeded reference data of fixed, small
     * size, so reading all of it is cheap and the usual argument for pagination
     * does not apply.
     *
     * <p>Canonical order rather than shuffled: randomising is the dealing use
     * case's job, through {@link DeckShuffler}. A port that returned the deck
     * pre-shuffled would make a deal impossible to reproduce in a test.
     *
     * @return every card in the deck, in canonical order
     */
    List<Card> findWholeDeck();
}
