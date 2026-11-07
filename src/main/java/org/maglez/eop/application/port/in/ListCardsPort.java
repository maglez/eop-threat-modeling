package org.maglez.eop.application.port.in;

import org.maglez.eop.domain.Card;

/**
 * Inbound port for the listcards operation.
 *
 * <p>Implemented by {@code ListCardsService} in the application service layer. A driving
 * adapter calls the method here; the service orchestrates domain and outbound ports.
 */
public interface ListCardsPort {

    /**
     * Reads one page of the deck.
     *
     * @param query which page to read
     * @return the requested page in canonical deck order
     
     */
    PageResult<Card> execute(PageQuery query);
}
