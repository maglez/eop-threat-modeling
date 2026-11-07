package org.maglez.eop.inbound.web;

import io.swagger.v3.oas.annotations.media.Schema;
import org.maglez.eop.domain.Card;

/**
 * A threat card as it crosses the HTTP boundary.
 *
 * <p>Domain entities never leave the application, so this is what the API
 * returns. The separation is not ceremony: it means a change to the domain shape
 * does not silently become a breaking API change, and the field names here are
 * governed by {@code docs/api/openapi.yml} rather than by Java conventions.
 *
 * <p>{@code rank} is published three ways on purpose. The name is what code
 * should switch on, the symbol is what a card face shows, and the value is what a
 * client can compare numerically without knowing the enum order.
 *
 * @param cardId       stable identifier
 * @param suit         the STRIDE category
 * @param rank         the rank constant name
 * @param rankSymbol   how the rank is printed on the card
 * @param rankValue    numeric rank, king high at 13
 * @param threatPrompt the threat described on the card face
 */
@Schema(name = "Card", description = "A single threat card. Immutable reference data.")
public record CardDto(
        String cardId,
        String suit,
        String rank,
        String rankSymbol,
        int rankValue,
        String threatPrompt) {

    /**
     * Converts a domain card into its transport form.
     *
     * @param card the domain card
     * @return the transport object
     */
    public static CardDto from(final Card card) {
        return new CardDto(
                card.cardId().toString(),
                card.suit().name(),
                card.rank().name(),
                card.rank().symbol(),
                card.rank().value(),
                card.threatPrompt());
    }
}
