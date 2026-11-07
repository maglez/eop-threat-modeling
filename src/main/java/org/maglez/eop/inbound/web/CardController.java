package org.maglez.eop.inbound.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.Objects;
import java.util.UUID;
import org.maglez.eop.domain.Card;
import org.maglez.eop.application.port.in.GetCardPort;
import org.maglez.eop.application.port.in.ListCardsPort;
import org.maglez.eop.application.port.in.PageQuery;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the threat card deck.
 *
 * <p>An adapter and nothing more: it translates HTTP into a use case call and the
 * result back into JSON. There is no business rule here, and no data access.
 *
 * <p>Read only. The deck is reference data seeded by migration, so no write verb
 * is mapped and none should be added — EOP-13 changes the deck by adding a
 * migration, not by calling an endpoint.
 */
@RestController
@RequestMapping("/api/v1/cards")
public class CardController {

    private final ListCardsPort listCards;
    private final GetCardPort getCard;

    CardController(final ListCardsPort listCards, final GetCardPort getCard) {
        this.listCards = Objects.requireNonNull(listCards, "listCards is required");
        this.getCard = Objects.requireNonNull(getCard, "getCard is required");
    }

    /**
     * Lists the deck one page at a time, in canonical order.
     *
     * <p>An out-of-range {@code page} or {@code size} is rejected by
     * {@link PageQuery} and surfaces as a 400 problem detail rather than being
     * quietly clamped, so a client bug stays visible.
     *
     * @param page zero-based page index
     * @param size page size
     * @return the requested page of cards
     */
    @GetMapping
    @Operation(summary = "List threat cards",
            description = "Returns a page of threat cards ordered by STRIDE suit, then by ascending rank.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "A page of cards."),
        @ApiResponse(responseCode = "400", description = "The page index or size is out of range."),
        @ApiResponse(responseCode = "429", description = "The read rate limit for this source address is exhausted.")
    })
    public ResponseEntity<PagedResponse<CardDto>> listCards(
            @RequestParam(name = "page", defaultValue = "0") final int page,
            @RequestParam(name = "size", defaultValue = "20") final int size) {

        return ResponseEntity.ok(PagedResponse.from(listCards.execute(new PageQuery(page, size)).map(CardDto::from)));
    }

    /**
     * Reads a single card.
     *
     * @param cardId identifier of the card
     * @return the card
     */
    @GetMapping("/{cardId}")
    @Operation(summary = "Get a single threat card")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The card."),
        @ApiResponse(responseCode = "400", description = "The identifier is not a UUID."),
        @ApiResponse(responseCode = "404", description = "No card exists with that identifier."),
        @ApiResponse(responseCode = "429", description = "The read rate limit for this source address is exhausted.")
    })
    public ResponseEntity<CardDto> getCard(@PathVariable("cardId") final UUID cardId) {
        final Card card = getCard.execute(cardId);
        return ResponseEntity.ok(CardDto.from(card));
    }
}
