package org.maglez.eop.inbound.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.maglez.eop.domain.Trick;

/**
 * A trick and the cards played into it, as they cross the HTTP boundary.
 *
 * <p>Deliberately silent on three things a client will want: whose turn it is, whether the trick is
 * complete, and which seat leads next. All three depend on which seats still hold cards, which is not
 * part of a trick — after the uneven deal some seats run out before others (ADR-023) — so answering
 * them from here would mean guessing. They belong to a state read over the whole session.
 *
 * <p>{@code winningSeat} is taken from {@link Trick#winner()} rather than from
 * {@code Trick.winningSeat()}, which throws when the trick is unresolved. Reading the winner through
 * the {@link java.util.Optional} keeps the unresolved case an ordinary branch instead of an exception
 * that would surface as a 500 for a perfectly normal mid-trick response.
 *
 * <p>Annotated {@link JsonInclude} so the absence of {@code winningSeat} and {@code ledSuit} is what
 * distinguishes an unresolved trick and a just-opened one, exactly as the contract says. Nulls would
 * be a state no schema describes.
 *
 * @param trickId     identifier of this trick
 * @param sequence    which trick of the hand this is, counting from one
 * @param leaderSeat  the seat that led it
 * @param ledSuit     the STRIDE category that must be followed, absent until the first card is played
 * @param plays       the cards played, in play order from the leader clockwise
 * @param winningSeat the seat that took the trick, absent until the trick is resolved
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "Trick", description = "One round of play: at most one card from each seat holding cards.")
public record TrickDto(
        String trickId,
        int sequence,
        int leaderSeat,
        String ledSuit,
        List<TrickPlayDto> plays,
        Integer winningSeat) {

    /**
     * Copies the plays defensively so the trick cannot be altered after construction.
     *
     * <p>Play order is the one thing this shape promises, and a shared list could be reordered after
     * the object describing it was built.
     *
     * @throws NullPointerException if the plays are null
     */
    public TrickDto {
        plays = List.copyOf(plays);
    }

    /**
     * Converts a domain trick into its transport form.
     *
     * @param trick the domain trick, resolved or not
     * @return the transport object
     */
    public static TrickDto from(final Trick trick) {
        return new TrickDto(
                trick.trickId().toString(),
                trick.sequence(),
                trick.leaderSeat(),
                trick.ledSuit().map(Enum::name).orElse(null),
                trick.plays().stream().map(TrickPlayDto::from).toList(),
                trick.winner().map(winner -> Integer.valueOf(winner.seatOrder())).orElse(null));
    }
}
