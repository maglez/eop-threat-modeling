package org.maglez.eop.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Test data builder for {@link TrickPlay}.
 *
 * <p>A play carries eight components, of which most rules care about two: the
 * seat and the card. This builder states the other six so a trick-resolution
 * test reads as the rule it is checking rather than as a constructor call.
 *
 * <p>The default instant is fixed rather than {@code Instant.now()} so a test
 * comparing two plays for equality is not at the mercy of the clock.
 */
public final class TrickPlayBuilder {

    /** Fixed play time, so equality and ordering assertions are deterministic. */
    public static final Instant PLAYED_AT = Instant.parse("2026-08-12T10:15:30Z");

    private UUID trickPlayId = new UUID(900, 1);
    private UUID playerId = new UUID(700, 0);
    private int seatOrder;
    private Card card = DeckFixture.card(StrideCategory.SPOOFING, Rank.FIVE);
    private boolean threatLinked = true;
    private List<String> components = List.of("Payments API");
    private String notes;
    private Instant playedAt = PLAYED_AT;

    private TrickPlayBuilder() {
    }

    /**
     * Starts a builder holding valid defaults: seat zero, a linked threat, one
     * named component and no notes.
     *
     * @return a new builder
     */
    public static TrickPlayBuilder aTrickPlay() {
        return new TrickPlayBuilder();
    }

    /**
     * Convenience for the commonest case in a trick test: this seat played this
     * card. Derives the play and player identifiers from the seat so two plays
     * in the same trick cannot accidentally collide on an identifier.
     *
     * @param seat the seat that played
     * @param played the card played
     * @return a new builder for that seat and card
     */
    public static TrickPlayBuilder aPlayBy(final int seat, final Card played) {
        return aTrickPlay()
                .withTrickPlayId(new UUID(900, seat))
                .withPlayerId(new UUID(700, seat))
                .withSeatOrder(seat)
                .withCard(played);
    }

    /**
     * @param value the play identifier to use
     * @return this builder
     */
    public TrickPlayBuilder withTrickPlayId(final UUID value) {
        this.trickPlayId = value;
        return this;
    }

    /**
     * @param value the identifier of the player who played
     * @return this builder
     */
    public TrickPlayBuilder withPlayerId(final UUID value) {
        this.playerId = value;
        return this;
    }

    /**
     * @param value the seat that played
     * @return this builder
     */
    public TrickPlayBuilder withSeatOrder(final int value) {
        this.seatOrder = value;
        return this;
    }

    /**
     * @param value the card played
     * @return this builder
     */
    public TrickPlayBuilder withCard(final Card value) {
        this.card = value;
        return this;
    }

    /**
     * @param value whether the player linked the threat to the system
     * @return this builder
     */
    public TrickPlayBuilder withThreatLinked(final boolean value) {
        this.threatLinked = value;
        return this;
    }

    /**
     * @param value the components the threat was linked to
     * @return this builder
     */
    public TrickPlayBuilder withComponents(final List<String> value) {
        this.components = value;
        return this;
    }

    /**
     * @param value the free-text note, or null
     * @return this builder
     */
    public TrickPlayBuilder withNotes(final String value) {
        this.notes = value;
        return this;
    }

    /**
     * @param value the time the card was played
     * @return this builder
     */
    public TrickPlayBuilder withPlayedAt(final Instant value) {
        this.playedAt = value;
        return this;
    }

    /**
     * @return the play described by this builder
     */
    public TrickPlay build() {
        return new TrickPlay(trickPlayId, playerId, seatOrder, card, threatLinked, components, notes, playedAt);
    }
}
