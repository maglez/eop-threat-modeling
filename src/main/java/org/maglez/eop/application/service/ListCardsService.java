package org.maglez.eop.application.service;

import java.util.Objects;
import org.maglez.eop.domain.Card;
import org.maglez.eop.application.port.in.ListCardsPort;
import org.maglez.eop.application.port.in.PageQuery;
import org.maglez.eop.application.port.in.PageResult;
import org.maglez.eop.application.port.out.CardRepository;

/**
 * Lists the threat card deck, one page at a time.
 *
 * <p>Thin today, and that is the point: the endpoint exists so that every later
 * story has a real domain type, a real migration and a real contract to build
 * on. When dealing arrives in EOP-14 the deck is read through this same port.
 *
 * <p>No Spring annotations. The bean definition lives in the configuration
 * layer, so this class stays a plain object that can be unit tested with no
 * container.
 */
public class ListCardsService implements ListCardsPort {

    private final CardRepository cardRepository;

    /**
     * Creates the use case.
     *
     * @param cardRepository the port used to read the deck
     */
    public ListCardsService(final CardRepository cardRepository) {
        this.cardRepository = Objects.requireNonNull(cardRepository, "cardRepository is required");
    }

    /**
     * Reads one page of the deck.
     *
     * @param query which page to read
     * @return the requested page in canonical deck order
     */
    public PageResult<Card> execute(final PageQuery query) {
        Objects.requireNonNull(query, "query is required");
        return cardRepository.findAll(query);
    }
}
