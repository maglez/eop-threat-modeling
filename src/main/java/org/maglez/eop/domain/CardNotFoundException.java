package org.maglez.eop.domain;

import java.util.UUID;

/**
 * Raised when a card is requested by an identifier that no card has.
 *
 * <p>A plain Java exception with no Spring imports, per ADR-005. Mapping it to
 * an HTTP status is the interface layer's job, not the domain's — the domain
 * does not know that HTTP exists.
 */
public class CardNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID cardId;

    /**
     * Creates the exception for a specific unknown identifier.
     *
     * @param cardId the identifier that matched no card
     */
    public CardNotFoundException(final UUID cardId) {
        super("No card found with identifier " + cardId);
        this.cardId = cardId;
    }

    /**
     * The identifier that matched no card.
     *
     * @return the requested identifier
     */
    public UUID cardId() {
        return cardId;
    }
}
