package org.maglez.eop.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Rank")
class RankTest {

    @Test
    @DisplayName("has twelve ranks, two through king")
    void shouldHaveTwelveRanks() {
        assertThat(Rank.values()).hasSize(12);
        assertThat(Rank.TWO.value()).isEqualTo(2);
        assertThat(Rank.KING.value()).isEqualTo(13);
    }

    @Test
    @DisplayName("declaration order runs from lowest to highest, so ordinal comparison agrees with value")
    void shouldDeclareRanksInAscendingOrder() {
        final Rank[] ranks = Rank.values();
        for (int i = 1; i < ranks.length; i++) {
            assertThat(ranks[i].value()).isGreaterThan(ranks[i - 1].value());
        }
    }

    @Test
    @DisplayName("king is the highest rank")
    void shouldTreatKingAsHighest() {
        assertThat(Rank.KING.beats(Rank.QUEEN)).isTrue();
        assertThat(Rank.QUEEN.beats(Rank.KING)).isFalse();
        assertThat(Rank.TWO.beats(Rank.TWO)).isFalse();
    }

    @Test
    @DisplayName("prints face cards as letters and the rest as numerals")
    void shouldPrintSymbols() {
        assertThat(Rank.TWO.symbol()).isEqualTo("2");
        assertThat(Rank.TEN.symbol()).isEqualTo("10");
        assertThat(Rank.JACK.symbol()).isEqualTo("J");
        assertThat(Rank.QUEEN.symbol()).isEqualTo("Q");
        assertThat(Rank.KING.symbol()).isEqualTo("K");
    }

    @Test
    @DisplayName("resolves every stored value back to its rank")
    void shouldResolveEveryStoredValue() {
        for (final Rank rank : Rank.values()) {
            assertThat(Rank.ofValue(rank.value())).isSameAs(rank);
        }
    }

    @Test
    @DisplayName("rejects a value no card carries, rather than guessing")
    void shouldRejectUnknownValue() {
        assertThatIllegalArgumentException().isThrownBy(() -> Rank.ofValue(1)).withMessageContaining("1");
        assertThatIllegalArgumentException().isThrownBy(() -> Rank.ofValue(14)).withMessageContaining("14");
    }
}
