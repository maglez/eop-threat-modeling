package org.maglez.eop.application.port.in;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Everything a player may say when they play a card.
 *
 * <p>What this record leaves out is the point of it. There is no seat order, no player identifier,
 * no suit and no rank, because every one of those is derivable from something the caller cannot
 * choose: the seat and the player come from the identity token, and the suit and rank come from the
 * deck row named by {@code cardId}. Two defects found earlier in this project were exactly the
 * absence of that discipline &mdash; a caller-supplied seat let one player play out of another's
 * hand, and a caller-supplied suit and rank let a forged card win a trick. Neither is expressible
 * through this type, which is a stronger guarantee than a validation rule that someone has to
 * remember to write.
 *
 * <p>The identity token is deliberately allowed to be null. {@link ResolvePlayerPort} answers a
 * missing token and an unrecognised token with the same refusal on purpose, so that a caller learns
 * nothing from the difference; rejecting null here would turn one of those two cases into a server
 * fault and give the distinction away.
 *
 * <p>Absent components are normalised to an empty list rather than refused. A play that links no
 * threat component is an ordinary outcome and not an error &mdash; {@code threatLinked == false} is
 * a legal answer &mdash; so a client that omits the field entirely is making a valid move.
 *
 * @param sessionId the session the card is played into
 * @param playerToken the identity token of the player playing, or null if none was presented
 * @param cardId the card being played, resolved against the deck and then against the hand
 * @param threatLinked whether the player claimed this card as a threat to the system under review
 * @param components the component names the player linked the threat to, empty if none
 * @param notes what the player wrote about the threat, or null if nothing was written
 */
public record PlayCardCommand(
        UUID sessionId,
        String playerToken,
        UUID cardId,
        boolean threatLinked,
        List<String> components,
        String notes) {

    /**
     * Validates and normalises the command.
     *
     * @throws NullPointerException if sessionId or cardId is null
     */
    public PlayCardCommand {
        Objects.requireNonNull(sessionId, "sessionId is required");
        Objects.requireNonNull(cardId, "cardId is required");
        components = components == null ? List.of() : List.copyOf(components);
    }
}
