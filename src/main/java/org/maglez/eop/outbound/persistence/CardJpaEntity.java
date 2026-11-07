package org.maglez.eop.outbound.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.maglez.eop.domain.Card;
import org.maglez.eop.domain.Rank;
import org.maglez.eop.domain.StrideCategory;

/**
 * How a threat card is stored.
 *
 * <p>Separate from the domain {@link Card} on purpose. The domain type is an
 * immutable record with no framework imports; JPA needs a mutable class with a
 * no-argument constructor and its own annotations. Merging the two would drag
 * Jakarta into the domain, which is exactly the dependency Hexagonal Architecture
 * forbids.
 *
 * <p>Package private: nothing outside this adapter should be able to name it.
 */
@Entity
@Table(name = "card")
class CardJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "suit", nullable = false, length = 32)
    private StrideCategory suit;

    /**
     * Mirrors {@link StrideCategory#deckOrder()}. Persisted rather than derived
     * so that ORDER BY happens in SQL and pagination is correct.
     */
    @Column(name = "suit_order", nullable = false)
    private int suitOrder;

    @Column(name = "card_rank", nullable = false)
    private int cardRank;

    @Column(name = "threat_prompt", nullable = false, length = Card.MAX_THREAT_PROMPT_LENGTH)
    private String threatPrompt;

    /** Required by JPA. Not for application use. */
    protected CardJpaEntity() {
        // Intentionally empty: JPA instantiates through this constructor and then populates fields.
    }

    /**
     * Converts the stored row into the domain type. The domain constructor
     * revalidates, so a row that was corrupted outside the application fails
     * here rather than travelling on as a valid-looking card.
     *
     * @return the domain card
     */
    Card toDomain() {
        return new Card(id, suit, Rank.ofValue(cardRank), threatPrompt);
    }

    /**
     * The persisted suit ordering, exposed so a test can assert it has not
     * drifted from the enum.
     *
     * @return the stored suit order
     */
    int suitOrder() {
        return suitOrder;
    }

    /**
     * The suit as stored.
     *
     * @return the stored suit
     */
    StrideCategory suit() {
        return suit;
    }
}
