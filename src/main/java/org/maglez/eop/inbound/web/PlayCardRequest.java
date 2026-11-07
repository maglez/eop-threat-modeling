package org.maglez.eop.inbound.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.maglez.eop.domain.TrickPlay;

/**
 * The body of a request to play one card into the current trick.
 *
 * <p>What is absent is the security property. There is no {@code seatOrder}, no {@code playerId}, and
 * no {@code suit} or {@code rank}: the seat and the player come from the identity token, and the card
 * is resolved from the deck row named by {@code cardId} and then out of the caller's own hand. Both
 * omissions are defects this project has already had — a caller-supplied seat let a player play out of
 * somebody else's hand, and a caller-supplied suit and rank let a forged card take a trick. Adding
 * either field back for symmetry would reopen them.
 *
 * <p>A body naming any of those fields is accepted and the field is ignored, because nothing reads it:
 * the seat and player published back are always the token's and the suit and rank always the deck's,
 * so a forged field changes no outcome. Refusing unknown fields outright would be the stronger
 * posture, but that setting is global to every endpoint in the application and choosing it belongs to
 * a decision of its own rather than to one feature slice.
 *
 * <p>{@code threatLinked} is a boxed {@link Boolean} rather than a primitive so that omitting it is
 * legal. The deserialiser refuses a missing primitive, which would have made a field the contract
 * publishes as optional mandatory in practice — the server breaking its own published contract. The
 * compact constructor collapses both an absent and an explicitly null value to {@code false}, so
 * every reader downstream sees a plain boolean and no caller has to think about a third state.
 *
 * <p>Bean validation here is the outer of two checks, and the sizes are the domain's own constants
 * rather than literals, so the boundary cannot drift from the rule it is meant to mirror. The domain
 * revalidates in {@link TrickPlay}, which has to be safe to construct from anywhere. The outer check
 * exists so an over-long note is a 400 naming the field rather than a 500, and so the request is
 * refused before anything is written. Copying the component list also refuses a null entry inside it
 * at the boundary, which is the one shape bean validation would wave through: {@code @Size} holds for
 * a null element, and the domain would then have to reject it far from the caller who sent it.
 *
 * @param cardId       the card to play, which must be one the caller holds
 * @param threatLinked whether the player is linking this threat to their model; absent means no
 * @param components   the components the threat applies to; omitting it and sending an empty array
 *     mean the same thing
 * @param notes        what the player wants recorded, if anything
 */
@Schema(name = "PlayCardRequest", description = "Plays one card from the caller's own hand.")
public record PlayCardRequest(
        @NotNull
        UUID cardId,

        Boolean threatLinked,

        @Size(max = TrickPlay.MAX_COMPONENTS)
        List<@Size(max = TrickPlay.MAX_COMPONENT_NAME_LENGTH) String> components,

        @Size(max = TrickPlay.MAX_NOTES_LENGTH)
        String notes) {

    /**
     * Normalises the two optional fields so no reader downstream has to.
     */
    public PlayCardRequest {
        threatLinked = threatLinked != null && threatLinked;
        components = components == null ? null : List.copyOf(components);
    }
}
