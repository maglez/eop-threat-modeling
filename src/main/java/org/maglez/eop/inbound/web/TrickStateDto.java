package org.maglez.eop.inbound.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import org.maglez.eop.application.port.in.TrickState;

/**
 * The state of play at a table, as it crosses the HTTP boundary.
 *
 * <p>A separate shape from {@link TrickDto} rather than an extension of it. {@code TrickDto}'s silence
 * about whose turn it is, whether the trick is complete and which seat leads next is documented there
 * as deliberate — those answers depend on the seats still holding cards, which is not part of a trick
 * (ADR-023) — and adding them to it would have made every response that carries a trick claim to
 * answer them, including the ones built where the hands were never read.
 *
 * <p>Annotated {@link JsonInclude} for the same reason {@link TrickDto} is: the three optional parts of
 * this answer are absent at ordinary moments, and their absence is what the contract describes. A null
 * {@code seatToPlay} would be a state no schema mentions, so it is left out of the document instead.
 * {@code complete} and {@code handComplete} are primitives and always present, which is exactly the
 * pair the contract marks required.
 *
 * <p>{@code seatToPlay} and {@code nextLeaderSeat} agree whenever both are present and are still both
 * published, because they are read from different authorities — the cards in the trick, and the seat the
 * session records as leading. Reconciling them here would throw away the only check a client has on
 * either.
 *
 * @param trick          the current trick, absent only between the deal and the first card led
 * @param seatToPlay     the seat entitled to play next, absent when no card may be played
 * @param complete       whether every seat still holding cards has played into the trick
 * @param nextLeaderSeat the seat that leads the next trick, absent until the trick is resolved and once
 *                       the hand is spent
 * @param handComplete   whether every card dealt has been played
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "TrickState", description = "The current trick together with the answers that depend on the hands.")
public record TrickStateDto(
        TrickDto trick,
        Integer seatToPlay,
        boolean complete,
        Integer nextLeaderSeat,
        boolean handComplete) {

    /**
     * Converts the state of play into its transport form.
     *
     * @param state the state of play as the use case computed it
     * @return the transport object
     */
    public static TrickStateDto from(final TrickState state) {
        return new TrickStateDto(
                state.trick().map(TrickDto::from).orElse(null),
                state.seatToPlay().isPresent() ? Integer.valueOf(state.seatToPlay().getAsInt()) : null,
                state.complete(),
                state.nextLeaderSeat().isPresent() ? Integer.valueOf(state.nextLeaderSeat().getAsInt()) : null,
                state.handComplete());
    }
}
