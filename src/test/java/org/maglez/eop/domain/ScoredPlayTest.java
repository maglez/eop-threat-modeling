package org.maglez.eop.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.maglez.eop.domain.PlayerBuilder.aPlayer;
import static org.maglez.eop.domain.TrickPlayBuilder.aPlayBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ScoredPlay")
class ScoredPlayTest {

    private static final Card FIVE_OF_SPOOFING = DeckFixture.card(StrideCategory.SPOOFING, Rank.FIVE);

    private static Player player(final int seatOrder) {
        return aPlayer().withPlayerId(new UUID(700, seatOrder)).withSeatOrder(seatOrder).build();
    }

    private static ScoredPlay row(final int seatOrder) {
        return new ScoredPlay(new UUID(700, seatOrder), seatOrder, new org.maglez.eop.domain.DisplayName("Ada"), FIVE_OF_SPOOFING,
                List.of("Payments API"), Optional.empty(), true, false);
    }

    @Nested
    @DisplayName("scoring a play")
    class ScoringAPlay {

        @Test
        @DisplayName("carries all five Score Card columns across from the play")
        void shouldCarryAllFiveScoreCardColumnsAcrossFromThePlay() {
            final Player player = player(1);
            final TrickPlay play = aPlayBy(1, FIVE_OF_SPOOFING).withComponents(List.of("Payments API", "Audit log"))
                    .withNotes("An attacker forges the caller identity").build();

            final ScoredPlay scored = ScoredPlay.of(player, play, true);

            assertThat(scored.displayName()).isEqualTo(player.displayName());
            assertThat(scored.points()).isEqualTo(2);
            assertThat(scored.card()).isEqualTo(FIVE_OF_SPOOFING);
            assertThat(scored.components()).containsExactly("Payments API", "Audit log");
            assertThat(scored.notes()).contains("An attacker forges the caller identity");
            assertThat(scored.playerId()).isEqualTo(player.playerId());
            assertThat(scored.seatOrder()).isEqualTo(1);
        }

        @Test
        @DisplayName("awards the trick point only to the play that took the trick")
        void shouldAwardTheTrickPointOnlyToThePlayThatTookTheTrick() {
            final TrickPlay play = aPlayBy(0, FIVE_OF_SPOOFING).build();

            assertThat(ScoredPlay.of(player(0), play, true).trickPoint()).isTrue();
            assertThat(ScoredPlay.of(player(0), play, false).trickPoint()).isFalse();
        }

        @Test
        @DisplayName("awards no threat point to an unlinked play that still took the trick")
        void shouldAwardNoThreatPointToAnUnlinkedPlayThatStillTookTheTrick() {
            final TrickPlay unlinked = aPlayBy(0, FIVE_OF_SPOOFING).withThreatLinked(false).withComponents(List.of()).build();

            final ScoredPlay scored = ScoredPlay.of(player(0), unlinked, true);

            assertThat(scored.threatPoint()).isFalse();
            assertThat(scored.trickPoint()).isTrue();
            assertThat(scored.points()).isEqualTo(1);
        }

        @Test
        @DisplayName("scores a linked threat that names no component")
        void shouldScoreALinkedThreatThatNamesNoComponent() {
            final TrickPlay named = aPlayBy(0, FIVE_OF_SPOOFING).withComponents(List.of()).build();

            final ScoredPlay scored = ScoredPlay.of(player(0), named, false);

            assertThat(scored.components()).isEmpty();
            assertThat(scored.threatPoint()).isTrue();
            assertThat(scored.points()).isEqualTo(1);
        }

        @Test
        @DisplayName("counts one point for each of the two reasons a row can score")
        void shouldCountOnePointForEachOfTheTwoReasonsARowCanScore() {
            final org.maglez.eop.domain.DisplayName ada = new org.maglez.eop.domain.DisplayName("Ada");
            final UUID player = new UUID(700, 0);

            assertThat(new ScoredPlay(player, 0, ada, FIVE_OF_SPOOFING, List.of(), Optional.empty(), false, false).points()).isZero();
            assertThat(new ScoredPlay(player, 0, ada, FIVE_OF_SPOOFING, List.of(), Optional.empty(), true, false).points()).isEqualTo(1);
            assertThat(new ScoredPlay(player, 0, ada, FIVE_OF_SPOOFING, List.of(), Optional.empty(), false, true).points()).isEqualTo(1);
            assertThat(new ScoredPlay(player, 0, ada, FIVE_OF_SPOOFING, List.of(), Optional.empty(), true, true).points()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("refuses to score a play against a player who did not make it")
        void shouldRefuseToScoreAPlayAgainstAPlayerWhoDidNotMakeIt() {
            final TrickPlay bySeatOne = aPlayBy(1, FIVE_OF_SPOOFING).build();

            assertThatExceptionOfType(ScoreNotDerivableException.class).isThrownBy(() -> ScoredPlay.of(player(2), bySeatOne, false))
                    .extracting(ScoreNotDerivableException::reason).isEqualTo(ScoreNotDerivableException.Reason.PLAY_NOT_BY_THIS_PLAYER);

            assertThat(ScoredPlay.of(player(1), bySeatOne, false).seatOrder()).isEqualTo(1);
        }

        @Test
        @DisplayName("refuses to score without a player or without a play")
        void shouldRefuseToScoreWithoutAPlayerOrWithoutAPlay() {
            final TrickPlay play = aPlayBy(0, FIVE_OF_SPOOFING).build();

            assertThatNullPointerException().isThrownBy(() -> ScoredPlay.of(null, play, false));
            assertThatNullPointerException().isThrownBy(() -> ScoredPlay.of(player(0), null, false));
        }

        @Test
        @DisplayName("refuses a row missing any of the things it describes")
        void shouldRefuseARowMissingAnyOfTheThingsItDescribes() {
            final org.maglez.eop.domain.DisplayName ada = new org.maglez.eop.domain.DisplayName("Ada");
            final UUID player = new UUID(700, 0);
            final List<String> components = List.of("Payments API");
            final Optional<String> notes = Optional.empty();

            assertThatNullPointerException()
                    .isThrownBy(() -> new ScoredPlay(null, 0, ada, FIVE_OF_SPOOFING, components, notes, true, false));
            assertThatNullPointerException()
                    .isThrownBy(() -> new ScoredPlay(player, 0, null, FIVE_OF_SPOOFING, components, notes, true, false));
            assertThatNullPointerException().isThrownBy(() -> new ScoredPlay(player, 0, ada, null, components, notes, true, false));
            assertThatNullPointerException().isThrownBy(() -> new ScoredPlay(player, 0, ada, FIVE_OF_SPOOFING, null, notes, true, false));
            assertThatNullPointerException()
                    .isThrownBy(() -> new ScoredPlay(player, 0, ada, FIVE_OF_SPOOFING, components, null, true, false));
        }

        @Test
        @DisplayName("refuses a seat that is not at the table")
        void shouldRefuseASeatThatIsNotAtTheTable() {
            final org.maglez.eop.domain.DisplayName ada = new org.maglez.eop.domain.DisplayName("Ada");
            final UUID player = new UUID(700, 0);
            final List<String> components = List.of();
            final Optional<String> notes = Optional.empty();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ScoredPlay(player, -1, ada, FIVE_OF_SPOOFING, components, notes, true, false));
            assertThatIllegalArgumentException().isThrownBy(() -> new ScoredPlay(player, GameSession.MAXIMUM_PLAYERS, ada,
                    FIVE_OF_SPOOFING, components, notes, true, false));

            assertThat(new ScoredPlay(player, 0, ada, FIVE_OF_SPOOFING, components, notes, true, false).seatOrder()).isZero();
            assertThat(new ScoredPlay(player, GameSession.MAXIMUM_PLAYERS - 1, ada, FIVE_OF_SPOOFING, components, notes, true, false)
                    .seatOrder()).isEqualTo(GameSession.MAXIMUM_PLAYERS - 1);
        }
    }

    @Nested
    @DisplayName("value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("does not let the caller change the components after the fact")
        void shouldNotLetTheCallerChangeTheComponentsAfterTheFact() {
            final List<String> mutable = new ArrayList<>(List.of("Payments API"));
            final ScoredPlay scored = new ScoredPlay(new UUID(700, 0), 0, new org.maglez.eop.domain.DisplayName("Ada"),
                    FIVE_OF_SPOOFING, mutable, Optional.empty(), true, false);

            mutable.add("Audit log");

            assertThat(scored.components()).containsExactly("Payments API");
            assertThat(scored.components()).isUnmodifiable();
        }

        @Test
        @DisplayName("is equal to another row describing the same play")
        void shouldBeEqualToAnotherRowDescribingTheSamePlay() {
            assertThat(row(1)).isEqualTo(row(1)).hasSameHashCodeAs(row(1));
            assertThat(row(1)).isNotEqualTo(row(2));
        }

        @Test
        @DisplayName("renders itself without repeating what a player typed")
        void shouldRenderItselfWithoutRepeatingWhatAPlayerTyped() {
            final TrickPlay play = aPlayBy(1, FIVE_OF_SPOOFING).withComponents(List.of("Payments API", "Audit log"))
                    .withNotes("An attacker forges the caller identity").build();

            final String rendered = ScoredPlay.of(player(1), play, true).toString();

            assertThat(rendered).doesNotContain("An attacker forges the caller identity", "Payments API", "Audit log")
                    .doesNotContain(FIVE_OF_SPOOFING.threatPrompt()).contains("components=2", "notes=given", "points=2");
        }

        @Test
        @DisplayName("renders an absent note as absent")
        void shouldRenderAnAbsentNoteAsAbsent() {
            assertThat(row(1).toString()).contains("notes=none", "components=1");
        }
    }
}
