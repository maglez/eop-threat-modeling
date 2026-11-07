package org.maglez.eop.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Standing")
class StandingTest {

    private static final UUID PLAYER = new UUID(700, 1);

    private static org.maglez.eop.domain.DisplayName name() {
        return new org.maglez.eop.domain.DisplayName("Ada");
    }

    @Nested
    @DisplayName("a place in the order")
    class APlaceInTheOrder {

        @Test
        @DisplayName("carries the total, the position and whether it is shared")
        void shouldCarryTheTotalThePositionAndWhetherItIsShared() {
            final Standing standing = new Standing(PLAYER, 2, name(), 5, 2, true);

            assertThat(standing.playerId()).isEqualTo(PLAYER);
            assertThat(standing.seatOrder()).isEqualTo(2);
            assertThat(standing.displayName().value()).isEqualTo("Ada");
            assertThat(standing.points()).isEqualTo(5);
            assertThat(standing.position()).isEqualTo(2);
            assertThat(standing.tied()).isTrue();
        }

        @Test
        @DisplayName("accepts a player who has scored nothing and stands alone")
        void shouldAcceptAPlayerWhoHasScoredNothingAndStandsAlone() {
            final Standing standing = new Standing(PLAYER, 0, name(), 0, 1, false);

            assertThat(standing.points()).isZero();
            assertThat(standing.tied()).isFalse();
        }

        @Test
        @DisplayName("accepts both ends of the seating")
        void shouldAcceptBothEndsOfTheSeating() {
            assertThat(new Standing(PLAYER, 0, name(), 1, 1, false).seatOrder()).isZero();
            assertThat(new Standing(PLAYER, GameSession.MAXIMUM_PLAYERS - 1, name(), 1, 1, false).seatOrder())
                    .isEqualTo(GameSession.MAXIMUM_PLAYERS - 1);
        }
    }

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("refuses a standing with no player")
        void shouldRefuseAStandingWithNoPlayer() {
            assertThatNullPointerException().isThrownBy(() -> new Standing(null, 0, name(), 1, 1, false));
        }

        @Test
        @DisplayName("refuses a standing with no name")
        void shouldRefuseAStandingWithNoName() {
            assertThatNullPointerException().isThrownBy(() -> new Standing(PLAYER, 0, null, 1, 1, false));
        }

        @Test
        @DisplayName("refuses a seat below the table")
        void shouldRefuseASeatBelowTheTable() {
            assertThatIllegalArgumentException().isThrownBy(() -> new Standing(PLAYER, -1, name(), 1, 1, false));
        }

        @Test
        @DisplayName("refuses a seat beyond the table")
        void shouldRefuseASeatBeyondTheTable() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new Standing(PLAYER, GameSession.MAXIMUM_PLAYERS, name(), 1, 1, false));
        }

        @Test
        @DisplayName("refuses a negative total")
        void shouldRefuseANegativeTotal() {
            assertThatIllegalArgumentException().isThrownBy(() -> new Standing(PLAYER, 0, name(), -1, 1, false));

            assertThat(new Standing(PLAYER, 0, name(), 0, 1, false).points()).isZero();
        }

        @Test
        @DisplayName("refuses a position below first")
        void shouldRefuseAPositionBelowFirst() {
            assertThatIllegalArgumentException().isThrownBy(() -> new Standing(PLAYER, 0, name(), 1, 0, false));

            assertThat(new Standing(PLAYER, 0, name(), 1, 1, false).position()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("is equal to another standing describing the same place")
        void shouldBeEqualToAnotherStandingDescribingTheSamePlace() {
            final Standing one = new Standing(PLAYER, 1, name(), 4, 2, true);
            final Standing other = new Standing(PLAYER, 1, name(), 4, 2, true);

            assertThat(one).isEqualTo(other).hasSameHashCodeAs(other);
            assertThat(one).isNotEqualTo(new Standing(PLAYER, 1, name(), 4, 2, false));
        }
    }
}
