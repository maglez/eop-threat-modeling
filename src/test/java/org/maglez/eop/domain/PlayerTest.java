package org.maglez.eop.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.maglez.eop.domain.PlayerBuilder.aParticipant;
import static org.maglez.eop.domain.PlayerBuilder.aPlayer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Player")
class PlayerTest {

    @Nested
    @DisplayName("validation")
    class Validation {

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 5})
        @DisplayName("accepts every seat the table actually has")
        void shouldAcceptSeatsWithinTheTable(final int seat) {
            assertThat(aPlayer().withSeatOrder(seat).build().seatOrder()).isEqualTo(seat);
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, 6, 7})
        @DisplayName("rejects a seat the table does not have, so a seventh chair cannot exist")
        void shouldRejectSeatsOutsideTheTable(final int seat) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> aPlayer().withSeatOrder(seat).build())
                    .withMessageContaining("A seat is 0 through 5");
        }

        @Test
        @DisplayName("rejects a null identifier")
        void shouldRejectNullPlayerId() {
            assertThatNullPointerException()
                    .isThrownBy(() -> aPlayer().withPlayerId(null).build())
                    .withMessageContaining("playerId");
        }

        @Test
        @DisplayName("rejects a null display name")
        void shouldRejectNullDisplayName() {
            assertThatNullPointerException()
                    .isThrownBy(() -> aPlayer().withDisplayName(null).build())
                    .withMessageContaining("displayName");
        }

        @Test
        @DisplayName("rejects a null role")
        void shouldRejectNullRole() {
            assertThatNullPointerException()
                    .isThrownBy(() -> aPlayer().withRole(null).build())
                    .withMessageContaining("role");
        }

        @Test
        @DisplayName("rejects a null connection status")
        void shouldRejectNullConnectionStatus() {
            assertThatNullPointerException()
                    .isThrownBy(() -> aPlayer().withConnectionStatus(null).build())
                    .withMessageContaining("connectionStatus");
        }

        @Test
        @DisplayName("rejects a null credential digest, because an unidentifiable player cannot be resolved")
        void shouldRejectNullIdentityTokenHash() {
            assertThatNullPointerException()
                    .isThrownBy(() -> aPlayer().withIdentityTokenHash(null).build())
                    .withMessageContaining("identityTokenHash");
        }

        @Test
        @DisplayName("rejects a null join instant")
        void shouldRejectNullJoinedAt() {
            assertThatNullPointerException()
                    .isThrownBy(() -> aPlayer().withJoinedAt(null).build())
                    .withMessageContaining("joinedAt");
        }
    }

    @Nested
    @DisplayName("identity")
    class Identity {

        @Test
        @DisplayName("recognises the digest of its own token")
        void shouldRecogniseItsOwnDigest() {
            final Player player = aPlayer().withToken("a-known-token").build();

            assertThat(player.isIdentifiedBy(IdentityTokenHash.of("a-known-token"))).isTrue();
        }

        @Test
        @DisplayName("does not recognise another player's digest")
        void shouldNotRecogniseAnotherDigest() {
            final Player player = aPlayer().withToken("a-known-token").build();

            assertThat(player.isIdentifiedBy(IdentityTokenHash.of("a-different-token"))).isFalse();
        }

        @Test
        @DisplayName("answers false for a null candidate rather than throwing, so a missing header is a plain refusal")
        void shouldAnswerFalseForNullCandidate() {
            assertThat(aPlayer().build().isIdentifiedBy(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("table authority")
    class TableAuthority {

        @Test
        @DisplayName("the facilitator may start play")
        void facilitatorMayStartPlay() {
            assertThat(aPlayer().withRole(PlayerRole.FACILITATOR).build().canStartPlay()).isTrue();
        }

        @Test
        @DisplayName("a participant may not start play")
        void participantMayNotStartPlay() {
            assertThat(aParticipant(1).build().canStartPlay()).isFalse();
        }
    }
}
