package org.maglez.eop.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.maglez.eop.domain.CardBuilder.aCard;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Card")
class CardTest {

    @Nested
    @DisplayName("rejects a malformed card at construction")
    class Validation {

        @Test
        @DisplayName("null identifier")
        void shouldRejectNullCardId() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new Card(null, StrideCategory.SPOOFING, Rank.TWO, "A threat."))
                    .withMessageContaining("cardId");
        }

        @Test
        @DisplayName("null suit")
        void shouldRejectNullSuit() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new Card(UUID.randomUUID(), null, Rank.TWO, "A threat."))
                    .withMessageContaining("suit");
        }

        @Test
        @DisplayName("null rank")
        void shouldRejectNullRank() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new Card(UUID.randomUUID(), StrideCategory.SPOOFING, null, "A threat."))
                    .withMessageContaining("rank");
        }

        @Test
        @DisplayName("null threat prompt")
        void shouldRejectNullThreatPrompt() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new Card(UUID.randomUUID(), StrideCategory.SPOOFING, Rank.TWO, null))
                    .withMessageContaining("threatPrompt");
        }

        @Test
        @DisplayName("blank threat prompt, because a card with nothing printed on it is not a card")
        void shouldRejectBlankThreatPrompt() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> aCard().withThreatPrompt("   ").build())
                    .withMessageContaining("must not be blank");
        }

        @Test
        @DisplayName("a threat prompt longer than the column can hold")
        void shouldRejectOverlongThreatPrompt() {
            final String tooLong = "x".repeat(Card.MAX_THREAT_PROMPT_LENGTH + 1);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> aCard().withThreatPrompt(tooLong).build())
                    .withMessageContaining("at most 500 characters");
        }

        @Test
        @DisplayName("a threat prompt of exactly the maximum length is accepted")
        void shouldAcceptThreatPromptAtMaximumLength() {
            final String atLimit = "x".repeat(Card.MAX_THREAT_PROMPT_LENGTH);
            assertThat(aCard().withThreatPrompt(atLimit).build().threatPrompt()).hasSize(Card.MAX_THREAT_PROMPT_LENGTH);
        }
    }

    @Nested
    @DisplayName("game rules")
    class GameRules {

        @Test
        @DisplayName("only elevation of privilege is trump")
        void shouldIdentifyTrumpCards() {
            assertThat(aCard().withSuit(StrideCategory.ELEVATION_OF_PRIVILEGE).build().isTrump()).isTrue();
            for (final StrideCategory suit : StrideCategory.values()) {
                if (suit != StrideCategory.ELEVATION_OF_PRIVILEGE) {
                    assertThat(aCard().withSuit(suit).build().isTrump()).isFalse();
                }
            }
        }
    }

    @Nested
    @DisplayName("renders itself without carrying its threat prompt into every log line")
    class Representation {

        @Test
        @DisplayName("names the suit and rank but not the prompt")
        void shouldNotRenderTheThreatPrompt() {
            final Card subject = aCard()
                    .withSuit(StrideCategory.TAMPERING)
                    .withRank(Rank.NINE)
                    .withThreatPrompt("An attacker could rewrite a settled ledger entry.")
                    .build();

            assertThat(subject.toString())
                    .contains("TAMPERING")
                    .contains("9")
                    .doesNotContain("An attacker could rewrite a settled ledger entry.");
        }
    }
}
