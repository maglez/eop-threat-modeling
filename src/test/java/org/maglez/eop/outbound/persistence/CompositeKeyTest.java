package org.maglez.eop.outbound.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The two {@code @IdClass} keys standing behind the composite primary keys {@code pk_hand_card} and
 * {@code pk_trick_play_component}.
 *
 * <p>These are the only hand-written {@code equals} and {@code hashCode} in the persistence adapter,
 * and they are load-bearing in a way that is easy to miss: JPA uses them for identity-map lookups
 * and so for deciding whether {@code saveAllAndFlush} inserts a row or merges one. A key that
 * reported two different rows equal would silently turn an insert into a merge and lose a component;
 * one that reported the same row unequal would attempt a duplicate insert and fail against the
 * primary key.
 *
 * <p>They are tested here rather than left to the integration tests because those reach the keys only
 * through Hibernate, which compares them reflectively — invisible to coverage — and, more to the
 * point, only ever compares keys that <em>agree</em>. Every branch that decides two keys are
 * <em>different</em> is therefore untaken by the suite that appears to exercise them, which is why
 * both classes measured zero branch coverage while the round trips were passing.
 *
 * <p>Both no-argument constructors are exercised too. They exist only because JPA requires them, and
 * a key in that state holds a null identifier, which is the one input {@link java.util.Objects#equals}
 * is here to absorb.
 */
@DisplayName("Composite primary keys")
class CompositeKeyTest {

    private static final UUID FIRST = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private static final UUID SECOND = UUID.fromString("00000000-0000-7000-8000-000000000002");

    @Nested
    @DisplayName("a hand_card key")
    class AHandCardKey {

        @Test
        @DisplayName("equals another key naming the same card in the same hand")
        void equalsAKeyWithBothPartsMatching() {
            final HandCardJpaEntity.Key key = new HandCardJpaEntity.Key(FIRST, SECOND);
            final HandCardJpaEntity.Key same = new HandCardJpaEntity.Key(FIRST, SECOND);

            assertThat(key).isEqualTo(same).hasSameHashCodeAs(same);
            assertThat(key).isEqualTo(key);
        }

        @Test
        @DisplayName("differs when either the hand or the card differs")
        void differsWhenEitherPartDiffers() {
            final HandCardJpaEntity.Key key = new HandCardJpaEntity.Key(FIRST, SECOND);

            assertThat(key).isNotEqualTo(new HandCardJpaEntity.Key(SECOND, SECOND));
            assertThat(key).isNotEqualTo(new HandCardJpaEntity.Key(FIRST, FIRST));
        }

        @Test
        @DisplayName("differs from null and from anything that is not a key")
        void differsFromNullAndOtherTypes() {
            final HandCardJpaEntity.Key key = new HandCardJpaEntity.Key(FIRST, SECOND);
            // Held as Object so the assertion reads as "equals rejects a foreign type" rather than as a
            // comparison of two unrelated static types, which is a different (and accidental) claim.
            final Object oneOfTheIdsAsAPlainString = "00000000-0000-7000-8000-000000000001";

            assertThat(key).isNotEqualTo(null);
            assertThat(key).isNotEqualTo(oneOfTheIdsAsAPlainString);
        }

        @Test
        @DisplayName("tolerates the empty state JPA constructs it in")
        void toleratesTheEmptyStateJpaConstructsItIn() {
            final HandCardJpaEntity.Key blank = new HandCardJpaEntity.Key();
            final HandCardJpaEntity.Key alsoBlank = new HandCardJpaEntity.Key();

            assertThat(blank).isEqualTo(alsoBlank).hasSameHashCodeAs(alsoBlank);
            assertThat(blank).isNotEqualTo(new HandCardJpaEntity.Key(FIRST, SECOND));
        }

        @Test
        @DisplayName("names both of its parts when printed")
        void namesBothPartsWhenPrinted() {
            assertThat(new HandCardJpaEntity.Key(FIRST, SECOND))
                    .hasToString("HandCardJpaEntity.Key[handId=" + FIRST + ", cardId=" + SECOND + "]");
        }
    }

    @Nested
    @DisplayName("a trick_play_component key")
    class ATrickPlayComponentKey {

        @Test
        @DisplayName("equals another key naming the same ordinal of the same play")
        void equalsAKeyWithBothPartsMatching() {
            final TrickPlayComponentJpaEntity.Key key = new TrickPlayComponentJpaEntity.Key(FIRST, 3);
            final TrickPlayComponentJpaEntity.Key same = new TrickPlayComponentJpaEntity.Key(FIRST, 3);

            assertThat(key).isEqualTo(same).hasSameHashCodeAs(same);
            assertThat(key).isEqualTo(key);
        }

        @Test
        @DisplayName("differs when either the play or the ordinal differs")
        void differsWhenEitherPartDiffers() {
            final TrickPlayComponentJpaEntity.Key key = new TrickPlayComponentJpaEntity.Key(FIRST, 3);

            assertThat(key).isNotEqualTo(new TrickPlayComponentJpaEntity.Key(SECOND, 3));
            assertThat(key).isNotEqualTo(new TrickPlayComponentJpaEntity.Key(FIRST, 4));
        }

        @Test
        @DisplayName("keeps the ordinal zero of one play apart from the ordinal zero of another")
        void keepsTheFirstComponentOfEachPlayApart() {
            assertThat(new TrickPlayComponentJpaEntity.Key(FIRST, 0))
                    .isNotEqualTo(new TrickPlayComponentJpaEntity.Key(SECOND, 0));
        }

        @Test
        @DisplayName("differs from null and from anything that is not a key")
        void differsFromNullAndOtherTypes() {
            final TrickPlayComponentJpaEntity.Key key = new TrickPlayComponentJpaEntity.Key(FIRST, 3);
            // Held as Object so the assertion reads as "equals rejects a foreign type" rather than as a
            // comparison of two unrelated static types, which is a different (and accidental) claim.
            final Object theOrdinalOnItsOwn = 3;

            assertThat(key).isNotEqualTo(null);
            assertThat(key).isNotEqualTo(theOrdinalOnItsOwn);
        }

        @Test
        @DisplayName("tolerates the empty state JPA constructs it in")
        void toleratesTheEmptyStateJpaConstructsItIn() {
            final TrickPlayComponentJpaEntity.Key blank = new TrickPlayComponentJpaEntity.Key();
            final TrickPlayComponentJpaEntity.Key alsoBlank = new TrickPlayComponentJpaEntity.Key();

            assertThat(blank).isEqualTo(alsoBlank).hasSameHashCodeAs(alsoBlank);
            assertThat(blank).isNotEqualTo(new TrickPlayComponentJpaEntity.Key(FIRST, 0));
        }

        @Test
        @DisplayName("names both of its parts when printed")
        void namesBothPartsWhenPrinted() {
            assertThat(new TrickPlayComponentJpaEntity.Key(FIRST, 7))
                    .hasToString(
                            "TrickPlayComponentJpaEntity.Key[trickPlayId=" + FIRST + ", ordinal=7]");
        }
    }
}
