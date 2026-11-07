package org.maglez.eop.inbound.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.maglez.eop.domain.ScoredPlay;

/**
 * One row of the Score Card, as it goes over the wire.
 *
 * <p>The two reasons a row can score are published separately rather than pre-summed, so that a
 * reader can see why it scored and not only that it did. {@code points} is their sum and never
 * anything else.
 *
 * <p>A display name travels as the plain string it is. It is neither verified nor unique, so it is
 * here for display and {@code playerId} is what identifies the player. It is escaped on the way out
 * by the serialiser rather than on the way in, so a name containing an ampersand is stored as typed
 * (ADR-015).
 *
 * <p>{@code notes} is absent when the player wrote nothing, never an empty string.
 *
 * @param playerId    the player who made the play
 * @param seatOrder   the seat the play was made from
 * @param displayName the name the player chose, for display only
 * @param card        the card that was played, already face up on the table
 * @param components  the parts of the system the player named, in the order they gave them
 * @param notes       what the player typed about the threat, or {@code null} if nothing
 * @param threatPoint whether the play scored for connecting a threat to the system
 * @param trickPoint  whether the play took the trick
 * @param points      what the row scored in total, never more than two
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "ScoredPlay", description = "One row of the Score Card: who played, what they played, and why it scored")
public record ScoredPlayDto(
        UUID playerId,
        int seatOrder,
        String displayName,
        CardDto card,
        List<String> components,
        String notes,
        boolean threatPoint,
        boolean trickPoint,
        int points) {

    /**
     * Copies the components defensively so the row cannot be altered after construction.
     *
     * @throws NullPointerException if the components are null
     */
    public ScoredPlayDto {
        components = List.copyOf(components);
    }

    /**
     * Converts a scored play into its wire form.
     *
     * @param row the scored play
     * @return the corresponding response body fragment
     */
    public static ScoredPlayDto from(final ScoredPlay row) {
        return new ScoredPlayDto(
                row.playerId(),
                row.seatOrder(),
                row.displayName().value(),
                CardDto.from(row.card()),
                row.components(),
                row.notes().orElse(null),
                row.threatPoint(),
                row.trickPoint(),
                row.points());
    }
}
