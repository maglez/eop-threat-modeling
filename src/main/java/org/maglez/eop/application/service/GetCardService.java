package org.maglez.eop.application.service;

import java.util.Objects;
import java.util.UUID;
import org.maglez.eop.domain.Card;
import org.maglez.eop.domain.CardNotFoundException;
import org.maglez.eop.application.port.in.GetCardPort;
import org.maglez.eop.application.port.out.CardRepository;

/**
 * Reads a single threat card, or fails if it does not exist.
 *
 * <p>The port returns an {@link java.util.Optional}; this use case turns the
 * empty case into a domain exception. That choice is deliberate: "absent" is a
 * legitimate answer to a repository, but it is an error to a caller who named a
 * specific card, and the layer that knows which of the two applies is this one.
 */
public class GetCardService implements GetCardPort {

    private final CardRepository cardRepository;

    /**
     * Creates the use case.
     *
     * @param cardRepository the port used to read the deck
     */
    public GetCardService(final CardRepository cardRepository) {
        this.cardRepository = Objects.requireNonNull(cardRepository, "cardRepository is required");
    }

    /**
     * Reads the card with the given identifier.
     *
     * @param cardId the identifier to look up
     * @return the card
     * @throws CardNotFoundException if no card has that identifier
     */
    public Card execute(final UUID cardId) {
        Objects.requireNonNull(cardId, "cardId is required");
        return cardRepository.findById(cardId).orElseThrow(() -> new CardNotFoundException(cardId));
    }
}
