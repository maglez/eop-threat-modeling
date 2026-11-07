package org.maglez.eop.domain;

import java.util.UUID;

/**
 * Test data builder for {@link Card}.
 *
 * <p>Present so that a test which cares about one field does not have to state
 * the other three. Every default here is valid, so a test that fails is failing
 * on the thing it set rather than on incidental setup.
 *
 * <p>Lives beside the type it builds, as the testing rules require, and exists
 * only in the test tree: production code never constructs a card, because cards
 * come from a migration.
 */
public final class CardBuilder {

    private UUID cardId = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private StrideCategory suit = StrideCategory.SPOOFING;
    private Rank rank = Rank.FIVE;
    private String threatPrompt = "An attacker could impersonate a caller the system never verifies.";

    private CardBuilder() {
    }

    /**
     * Starts a builder holding valid defaults.
     *
     * @return a new builder
     */
    public static CardBuilder aCard() {
        return new CardBuilder();
    }

    /**
     * @param value the identifier to use
     * @return this builder
     */
    public CardBuilder withCardId(final UUID value) {
        this.cardId = value;
        return this;
    }

    /**
     * @param value the suit to use
     * @return this builder
     */
    public CardBuilder withSuit(final StrideCategory value) {
        this.suit = value;
        return this;
    }

    /**
     * @param value the rank to use
     * @return this builder
     */
    public CardBuilder withRank(final Rank value) {
        this.rank = value;
        return this;
    }

    /**
     * @param value the threat prompt to use
     * @return this builder
     */
    public CardBuilder withThreatPrompt(final String value) {
        this.threatPrompt = value;
        return this;
    }

    /**
     * @return the card described by this builder
     */
    public Card build() {
        return new Card(cardId, suit, rank, threatPrompt);
    }
}
