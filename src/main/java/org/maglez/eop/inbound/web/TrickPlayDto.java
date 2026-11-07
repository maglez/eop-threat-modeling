package org.maglez.eop.inbound.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.maglez.eop.domain.TrickPlay;

/**
 * One card played into a trick, as it crosses the HTTP boundary.
 *
 * <p>{@code seatOrder} is published but is never read from a request. It is derived from the identity
 * token of the request that made the play, and {@code PlayCardRequest} has no field for it. A client
 * that could name the seat could play out of another player's hand, which is a defect this project
 * has already had once.
 *
 * <p>Annotated {@link JsonInclude} so an absent note is an absent field rather than a null one. The
 * contract says {@code notes} is either a string or not there; {@code "notes": null} would be a third
 * state that no schema describes.
 *
 * @param trickPlayId  identifier of this play
 * @param playerId     the player who made it
 * @param seatOrder    the seat it was made from, taken from the token and never from a body
 * @param card         the card played
 * @param threatLinked whether the player linked the threat to their model
 * @param components   the components the threat was linked to, empty when none
 * @param notes        what the player wrote, absent when they wrote nothing
 * @param playedAt     when the play was recorded, ISO-8601 in UTC
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "TrickPlay", description = "A single card played into a trick.")
public record TrickPlayDto(
        String trickPlayId,
        String playerId,
        int seatOrder,
        CardDto card,
        boolean threatLinked,
        List<String> components,
        String notes,
        String playedAt) {

    /**
     * Copies the components defensively so the play cannot be altered after construction.
     *
     * @throws NullPointerException if the components are null
     */
    public TrickPlayDto {
        components = List.copyOf(components);
    }

    /**
     * Converts a domain play into its transport form.
     *
     * @param play the domain play
     * @return the transport object
     */
    public static TrickPlayDto from(final TrickPlay play) {
        return new TrickPlayDto(
                play.trickPlayId().toString(),
                play.playerId().toString(),
                play.seatOrder(),
                CardDto.from(play.card()),
                play.threatLinked(),
                play.components(),
                play.notesIfGiven().orElse(null),
                play.playedAt().toString());
    }
}
