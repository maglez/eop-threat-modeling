package org.maglez.eop.inbound.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.maglez.eop.domain.Hand;

/**
 * The cards the requester is holding, as they cross the HTTP boundary.
 *
 * <p>There is no DTO for another player's hand and none for every hand at once, because there is no
 * operation that returns either. A shape that could carry somebody else's cards would eventually be
 * returned by something.
 *
 * <p>No {@code seatOrder}. The caller already knows its own seat: it holds the {@code playerId} it
 * was given when it joined and can find itself in {@code SessionState.players}. Repeating the seat
 * here would be a second copy of game data that could disagree with the first.
 *
 * @param handId    identifier of this hand within the session
 * @param playerId  the player holding it, which is always the caller
 * @param cardCount how many cards are left in it
 * @param cards     the cards themselves, in no promised order
 */
@Schema(name = "Hand", description = "The cards held by the requesting player. Never another player's.")
public record HandDto(
        String handId,
        String playerId,
        int cardCount,
        List<CardDto> cards) {

    /**
     * Copies the cards defensively so the hand cannot be altered after construction.
     *
     * <p>A record is only a value if every component is one, and this one describes what a player may
     * legally play; a list that stayed shared with its builder could be added to after the object
     * reporting it had been created.
     *
     * @throws NullPointerException if the cards are null
     */
    public HandDto {
        cards = List.copyOf(cards);
    }

    /**
     * Converts a domain hand into its transport form.
     *
     * <p>{@code cardCount} is taken from {@link Hand#size()} rather than from the list built here, so
     * the two cannot drift apart: a client that renders the count and a client that renders the cards
     * are looking at the same number.
     *
     * @param hand the domain hand
     * @return the transport object
     */
    public static HandDto from(final Hand hand) {
        return new HandDto(
                hand.handId().toString(),
                hand.playerId().toString(),
                hand.size(),
                hand.cards().stream().map(CardDto::from).toList());
    }
}
