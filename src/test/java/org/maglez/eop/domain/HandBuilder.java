package org.maglez.eop.domain;

import java.util.List;
import java.util.UUID;

/**
 * Test data builder for {@link Hand}.
 *
 * <p>Present so that a test which cares about the cards does not have to invent
 * two identifiers, and a test which cares about identity does not have to invent
 * a plausible holding. Every default here is valid.
 *
 * <p>The default holding is deliberately mixed-suit and small: three cards in
 * three different suits, which is the shape most follow-suit tests need. A test
 * about following suit states the holding it needs and nothing else.
 *
 * <p>One of those three defaults is a trump — the seven of Elevation of Privilege —
 * and that is deliberate rather than an oversight. A hand that cannot follow suit
 * may play anything including trump, so the default holding has to contain one for
 * that branch to be reachable without every test restating it. The cost is that a
 * test which takes the default and then asks who won a trick is asking about a hand
 * that holds trump, so a test about winning states its own holding.
 */
public final class HandBuilder {

    private UUID handId = new UUID(800, 1);
    private UUID playerId = new UUID(700, 1);
    private List<Card> cards = List.of(
            DeckFixture.card(StrideCategory.SPOOFING, Rank.FOUR),
            DeckFixture.card(StrideCategory.TAMPERING, Rank.NINE),
            DeckFixture.card(StrideCategory.ELEVATION_OF_PRIVILEGE, Rank.SEVEN));

    private HandBuilder() {
    }

    /**
     * Starts a builder holding valid defaults.
     *
     * @return a new builder
     */
    public static HandBuilder aHand() {
        return new HandBuilder();
    }

    /**
     * @param value the hand identifier to use
     * @return this builder
     */
    public HandBuilder withHandId(final UUID value) {
        this.handId = value;
        return this;
    }

    /**
     * @param value the identifier of the player holding the hand
     * @return this builder
     */
    public HandBuilder withPlayerId(final UUID value) {
        this.playerId = value;
        return this;
    }

    /**
     * @param value the cards held
     * @return this builder
     */
    public HandBuilder withCards(final List<Card> value) {
        this.cards = value;
        return this;
    }

    /**
     * @param value the cards held
     * @return this builder
     */
    public HandBuilder withCards(final Card... value) {
        this.cards = List.of(value);
        return this;
    }

    /**
     * @return the hand described by this builder
     */
    public Hand build() {
        return Hand.of(handId, playerId, cards);
    }
}
