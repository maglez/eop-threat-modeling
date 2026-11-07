package org.maglez.eop.application.port.in;

import org.maglez.eop.domain.Card;
import java.util.UUID;
import org.maglez.eop.domain.CardNotFoundException;

/**
 * Inbound port for the getcard operation.
 *
 * <p>Implemented by {@code GetCardService} in the application service layer. A driving
 * adapter calls the method here; the service orchestrates domain and outbound ports.
 */
public interface GetCardPort {

    /**
     * Reads the card with the given identifier.
     *
     * @param cardId the identifier to look up
     * @return the card
     * @throws CardNotFoundException if no card has that identifier
     
     */
    Card execute(UUID cardId);
}
