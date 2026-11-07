package org.maglez.eop.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.maglez.eop.domain.TrickPlayBuilder.aPlayBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ScoreSheet")
class ScoreSheetTest {

    private static final UUID SEAT_ZERO = new UUID(700, 0);

    private static final UUID SEAT_ONE = new UUID(700, 1);

    private static final UUID SEAT_TWO = new UUID(700, 2);

    /**
     * Seats three players whose identifiers line up with the ones {@link TrickPlayBuilder#aPlayBy(int, Card)} derives from a seat, so that
     * a play can be attributed to a player. The two fixtures use different identifier ranges by default, which would otherwise produce
     * plays by strangers.
     *
     * @return the facilitator in seat zero and two participants
     */
    private static List<Player> threePlayers() {
        return List.of(player(0, PlayerRole.FACILITATOR), player(1, PlayerRole.PARTICIPANT), player(2, PlayerRole.PARTICIPANT));
    }

    private static Player player(final int seatOrder, final PlayerRole role) {
        return PlayerBuilder.aPlayer()
                .withPlayerId(new UUID(700, seatOrder))
                .withDisplayName(new org.maglez.eop.domain.DisplayName("Player " + seatOrder))
                .withSeatOrder(seatOrder)
                .withRole(role)
                .build();
    }

    private static Card card(final StrideCategory suit, final Rank rank) {
        return DeckFixture.card(suit, rank);
    }

    private static Trick trickBySeatZeroAt(final int sequence) {
        return TrickBuilder.aTrick()
                .withTrickId(new UUID(1000, 90L + sequence))
                .withSequence(sequence)
                .withLeaderSeat(0)
                .withPlays(aPlayBy(0, card(StrideCategory.SPOOFING, Rank.NINE)).build())
                .build();
    }

    @Nested
    @DisplayName("the shipped scoring rule")
    class TheShippedScoringRule {

        @Test
        @DisplayName("gives one point for a connected threat and one for taking the trick, and nothing else")
        void shouldScoreOnePointPerThreatAndOnePerTrick() {
            final TrickPlay lead = aPlayBy(0, card(StrideCategory.SPOOFING, Rank.KING)).build();
            final TrickPlay second = aPlayBy(1, card(StrideCategory.SPOOFING, Rank.FIVE)).build();
            final TrickPlay third = aPlayBy(2, card(StrideCategory.SPOOFING, Rank.SIX)).build();
            final Trick trick = TrickBuilder.aTrick().withLeaderSeat(0).withPlays(lead, second, third).build().resolved();

            final ScoreSheet sheet = ScoreSheet.of(threePlayers(), List.of(trick));

            assertThat(trick.winningSeat()).isZero();
            assertThat(sheet.pointsOf(SEAT_ZERO)).isEqualTo(2);
            assertThat(sheet.pointsOf(SEAT_ONE)).isEqualTo(1);
            assertThat(sheet.pointsOf(SEAT_TWO)).isEqualTo(1);
        }

        @Test
        @DisplayName("scores nothing for a play with no threat connected to it, though that play can still take the trick")
        void shouldScoreNoThreatPointForAnUnlinkedPlayThatStillWins() {
            final TrickPlay lead = aPlayBy(0, card(StrideCategory.SPOOFING, Rank.FIVE)).build();
            final TrickPlay unlinked = aPlayBy(1, card(StrideCategory.SPOOFING, Rank.KING))
                    .withThreatLinked(false)
                    .withComponents(List.of())
                    .build();
            final TrickPlay third = aPlayBy(2, card(StrideCategory.SPOOFING, Rank.SIX)).build();
            final Trick trick = TrickBuilder.aTrick().withLeaderSeat(0).withPlays(lead, unlinked, third).build().resolved();

            final ScoreSheet sheet = ScoreSheet.of(threePlayers(), List.of(trick));

            assertThat(trick.winningSeat()).isEqualTo(1);
            assertThat(sheet.pointsOf(SEAT_ONE)).isEqualTo(1);
            assertThat(sheet.rows())
                    .filteredOn(row -> row.seatOrder() == 1)
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row.threatPoint()).isFalse();
                        assertThat(row.trickPoint()).isTrue();
                        assertThat(row.points()).isEqualTo(1);
                    });
        }

        @Test
        @DisplayName("gives the trick point to a trump played off suit, because Elevation of Privilege beats the led suit")
        void shouldGiveTheTrickPointToATrump() {
            final TrickPlay lead = aPlayBy(0, card(StrideCategory.SPOOFING, Rank.KING)).build();
            final TrickPlay trump = aPlayBy(1, card(StrideCategory.ELEVATION_OF_PRIVILEGE, Rank.TWO)).build();
            final TrickPlay high = aPlayBy(2, card(StrideCategory.SPOOFING, Rank.QUEEN)).build();
            final Trick trick = TrickBuilder.aTrick().withLeaderSeat(0).withPlays(lead, trump, high).build().resolved();

            final ScoreSheet sheet = ScoreSheet.of(threePlayers(), List.of(trick));

            assertThat(trick.winningSeat()).isEqualTo(1);
            assertThat(sheet.pointsOf(SEAT_ONE)).isEqualTo(2);
            assertThat(sheet.pointsOf(SEAT_TWO)).isEqualTo(1);
        }

        @Test
        @DisplayName("scores a connected threat that names no component, because the rule keys on the connection and not on the naming")
        void shouldScoreALinkedThreatThatNamesNoComponent() {
            final TrickPlay lead = aPlayBy(0, card(StrideCategory.TAMPERING, Rank.THREE)).withComponents(List.of()).build();
            final Trick trick = TrickBuilder.aTrick().withLeaderSeat(0).withPlays(lead).build().resolved();

            final ScoreSheet sheet = ScoreSheet.of(threePlayers(), List.of(trick));

            assertThat(sheet.pointsOf(SEAT_ZERO)).isEqualTo(2);
            assertThat(sheet.rows()).singleElement().satisfies(row -> {
                assertThat(row.threatPoint()).isTrue();
                assertThat(row.components()).isEmpty();
            });
        }
    }

    @Nested
    @DisplayName("a trick still on the table")
    class ATrickStillOnTheTable {

        @Test
        @DisplayName("counts its threat points immediately but awards no trick point until somebody has taken it")
        void shouldCountThreatPointsBeforeResolution() {
            final Trick unresolved = TrickBuilder.aTrick()
                    .withLeaderSeat(0)
                    .withPlays(aPlayBy(0, card(StrideCategory.REPUDIATION, Rank.FOUR)).build(),
                            aPlayBy(1, card(StrideCategory.REPUDIATION, Rank.NINE)).build())
                    .build();

            final ScoreSheet sheet = ScoreSheet.of(threePlayers(), List.of(unresolved));

            assertThat(sheet.pointsOf(SEAT_ZERO)).isEqualTo(1);
            assertThat(sheet.pointsOf(SEAT_ONE)).isEqualTo(1);
            assertThat(sheet.rows()).allSatisfy(row -> assertThat(row.trickPoint()).isFalse());
        }
    }

    @Nested
    @DisplayName("standings")
    class Standings {

        @Test
        @DisplayName("shows a shared lead as a tie rather than breaking it")
        void shouldShowATieAsATie() {
            final ScoreSheet sheet = ScoreSheet.of(threePlayers(), List.of(firstTrickWonBySeatZero(), secondTrickWonBySeatOne()));

            assertThat(sheet.pointsOf(SEAT_ZERO)).isEqualTo(3);
            assertThat(sheet.pointsOf(SEAT_ONE)).isEqualTo(3);
            assertThat(sheet.pointsOf(SEAT_TWO)).isEqualTo(2);
            assertThat(sheet.leadIsShared()).isTrue();
            assertThat(sheet.leaders()).extracting(Standing::seatOrder).containsExactly(0, 1);
            assertThat(sheet.standings()).extracting(Standing::position).containsExactly(1, 1, 3);
            assertThat(sheet.standings()).extracting(Standing::tied).containsExactly(true, true, false);
        }

        @Test
        @DisplayName("lists every seated player before a card has been played, all level and all tied")
        void shouldListEverybodyOnNothingBeforeAnyPlay() {
            final ScoreSheet sheet = ScoreSheet.of(threePlayers(), List.of());

            assertThat(sheet.rows()).isEmpty();
            assertThat(sheet.standings()).hasSize(3).allSatisfy(standing -> {
                assertThat(standing.points()).isZero();
                assertThat(standing.position()).isEqualTo(1);
                assertThat(standing.tied()).isTrue();
            });
            assertThat(sheet.leaders()).hasSize(3);
        }

        @Test
        @DisplayName("puts the outright leader alone in first place and marks nobody as tied")
        void shouldReportAnOutrightLeader() {
            final ScoreSheet sheet = ScoreSheet.of(threePlayers(), List.of(firstTrickWonBySeatZero()));

            assertThat(sheet.leaders()).extracting(Standing::seatOrder).containsExactly(0);
            assertThat(sheet.leadIsShared()).isFalse();
            assertThat(sheet.standings()).extracting(Standing::position).containsExactly(1, 2, 2);
        }
    }

    @Nested
    @DisplayName("the Score Card")
    class TheScoreCard {

        @Test
        @DisplayName("carries all five columns of the printed sheet for every play")
        void shouldCarryEveryColumnOfThePrintedSheet() {
            final TrickPlay lead = aPlayBy(0, card(StrideCategory.DENIAL_OF_SERVICE, Rank.SEVEN))
                    .withComponents(List.of("Payments API", "Session store"))
                    .withNotes("Flooding the queue starves the worker pool")
                    .build();
            final Trick trick = TrickBuilder.aTrick().withLeaderSeat(0).withPlays(lead).build().resolved();

            final ScoreSheet sheet = ScoreSheet.of(threePlayers(), List.of(trick));

            assertThat(sheet.rows()).singleElement().satisfies(row -> {
                assertThat(row.displayName().value()).isEqualTo("Player 0");
                assertThat(row.points()).isEqualTo(2);
                assertThat(row.card().suit()).isEqualTo(StrideCategory.DENIAL_OF_SERVICE);
                assertThat(row.card().rank()).isEqualTo(Rank.SEVEN);
                assertThat(row.components()).containsExactly("Payments API", "Session store");
                assertThat(row.notes()).contains("Flooding the queue starves the worker pool");
            });
        }

        @Test
        @DisplayName("orders rows by the order the cards were played, whatever order the tricks arrive in")
        void shouldOrderRowsByPlay() {
            final ScoreSheet sheet = ScoreSheet.of(threePlayers(), List.of(secondTrickWonBySeatOne(), firstTrickWonBySeatZero()));

            assertThat(sheet.rows()).extracting(row -> row.card().rank())
                    .containsExactly(Rank.KING, Rank.FIVE, Rank.SIX, Rank.FOUR, Rank.QUEEN, Rank.TWO);
        }

        @Test
        @DisplayName("leaves a play with no note carrying no note")
        void shouldLeaveAnAbsentNoteAbsent() {
            final Trick trick = TrickBuilder.aTrick()
                    .withLeaderSeat(0)
                    .withPlays(aPlayBy(0, card(StrideCategory.SPOOFING, Rank.EIGHT)).withNotes(null).build())
                    .build()
                    .resolved();

            final ScoreSheet sheet = ScoreSheet.of(threePlayers(), List.of(trick));

            assertThat(sheet.rows()).singleElement().satisfies(row -> assertThat(row.notes()).isEmpty());
        }
    }

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("refuses a sheet with nobody seated")
        void shouldRefuseNoPlayers() {
            assertThatExceptionOfType(ScoreNotDerivableException.class).isThrownBy(() -> ScoreSheet.of(List.of(), List.of()))
                    .extracting(ScoreNotDerivableException::reason).isEqualTo(ScoreNotDerivableException.Reason.NO_PLAYERS);

            assertThat(ScoreSheet.of(List.of(player(0, PlayerRole.FACILITATOR)), List.of()).standings()).hasSize(1);
        }

        @Test
        @DisplayName("refuses null arguments rather than scoring an unknown game")
        void shouldRefuseNulls() {
            assertThatNullPointerException().isThrownBy(() -> ScoreSheet.of(null, List.of()));
            assertThatNullPointerException().isThrownBy(() -> ScoreSheet.of(threePlayers(), null));
        }

        @Test
        @DisplayName("refuses a play made by somebody who is not seated, rather than dropping it from the sheet")
        void shouldRefuseAPlayByAStranger() {
            final Card ten = card(StrideCategory.SPOOFING, Rank.TEN);
            final Trick trick = TrickBuilder.aTrick()
                    .withLeaderSeat(0)
                    .withPlays(aPlayBy(0, ten).withPlayerId(new UUID(42, 42)).build())
                    .build();

            assertThatExceptionOfType(ScoreNotDerivableException.class).isThrownBy(() -> ScoreSheet.of(threePlayers(), List.of(trick)))
                    .extracting(ScoreNotDerivableException::reason).isEqualTo(ScoreNotDerivableException.Reason.PLAY_BY_UNSEATED_PLAYER);

            final Trick seated = TrickBuilder.aTrick().withLeaderSeat(0).withPlays(aPlayBy(0, ten).build()).build();
            assertThat(ScoreSheet.of(threePlayers(), List.of(seated)).rows()).hasSize(1);
        }

        @Test
        @DisplayName("refuses a play whose seat disagrees with where its player is sitting")
        void shouldRefuseASeatMismatch() {
            final Card ten = card(StrideCategory.SPOOFING, Rank.TEN);
            final Trick trick = TrickBuilder.aTrick()
                    .withLeaderSeat(1)
                    .withPlays(aPlayBy(1, ten).withPlayerId(new UUID(700, 2)).build())
                    .build();

            assertThatExceptionOfType(ScoreNotDerivableException.class).isThrownBy(() -> ScoreSheet.of(threePlayers(), List.of(trick)))
                    .extracting(ScoreNotDerivableException::reason).isEqualTo(ScoreNotDerivableException.Reason.PLAY_SEAT_MISMATCH);

            final Trick agreeing = TrickBuilder.aTrick().withLeaderSeat(1).withPlays(aPlayBy(1, ten).build()).build();
            assertThat(ScoreSheet.of(threePlayers(), List.of(agreeing)).rows()).hasSize(1);
        }

        @Test
        @DisplayName("refuses the same trick counted twice")
        void shouldRefuseADuplicateTrick() {
            final Trick trick = firstTrickWonBySeatZero();

            assertThatExceptionOfType(ScoreNotDerivableException.class)
                    .isThrownBy(() -> ScoreSheet.of(threePlayers(), List.of(trick, trick)))
                    .extracting(ScoreNotDerivableException::reason).isEqualTo(ScoreNotDerivableException.Reason.TRICK_REPEATED);

            assertThat(ScoreSheet.of(threePlayers(), List.of(trick)).rows()).isNotEmpty();
        }

        @Test
        @DisplayName("refuses two tricks claiming the same place in the hand")
        void shouldRefuseADuplicateSequence() {
            final Trick first = firstTrickWonBySeatZero();
            final Trick clash = trickBySeatZeroAt(first.sequence());
            final Trick following = trickBySeatZeroAt(first.sequence() + 1);

            assertThatExceptionOfType(ScoreNotDerivableException.class)
                    .isThrownBy(() -> ScoreSheet.of(threePlayers(), List.of(first, clash)))
                    .extracting(ScoreNotDerivableException::reason).isEqualTo(ScoreNotDerivableException.Reason.SEQUENCE_REPEATED);

            assertThat(ScoreSheet.of(threePlayers(), List.of(first, following)).rows()).hasSizeGreaterThan(1);
        }

        @Test
        @DisplayName("refuses the same player seated twice")
        void shouldRefuseADuplicatePlayer() {
            final List<Player> twice = List.of(player(0, PlayerRole.FACILITATOR), player(0, PlayerRole.PARTICIPANT));

            assertThatExceptionOfType(ScoreNotDerivableException.class).isThrownBy(() -> ScoreSheet.of(twice, List.of()))
                    .extracting(ScoreNotDerivableException::reason).isEqualTo(ScoreNotDerivableException.Reason.PLAYER_SEATED_TWICE);

            final List<Player> once = List.of(player(0, PlayerRole.FACILITATOR), player(1, PlayerRole.PARTICIPANT));
            assertThat(ScoreSheet.of(once, List.of()).standings()).hasSize(2);
        }

        @Test
        @DisplayName("refuses a null trick and a null player")
        void shouldRefuseANullMember() {
            final List<Trick> withNull = java.util.Arrays.asList(firstTrickWonBySeatZero(), null);
            final List<Player> withNullPlayer = java.util.Arrays.asList(player(0, PlayerRole.FACILITATOR), null);

            assertThatNullPointerException().isThrownBy(() -> ScoreSheet.of(threePlayers(), withNull));
            assertThatNullPointerException().isThrownBy(() -> ScoreSheet.of(withNullPlayer, List.of()));
        }

        @Test
        @DisplayName("refuses to answer for a player who is not in this game")
        void shouldRefuseToScoreAStranger() {
            final ScoreSheet sheet = ScoreSheet.of(threePlayers(), List.of());

            assertThatExceptionOfType(ScoreNotDerivableException.class).isThrownBy(() -> sheet.pointsOf(new UUID(42, 42)))
                    .extracting(ScoreNotDerivableException::reason).isEqualTo(ScoreNotDerivableException.Reason.PLAYER_NOT_SEATED);
            assertThatNullPointerException().isThrownBy(() -> sheet.pointsOf(null));

            assertThat(sheet.pointsOf(new UUID(700, 0))).isZero();
        }
    }

    @Nested
    @DisplayName("value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("differs from a sheet with the same rows but another player standing")
        void shouldDifferFromASheetWithTheSameRowsButAnotherPlayerStanding() {
            final Trick trick = firstTrickWonBySeatZero();
            final List<Player> four = List.of(player(0, PlayerRole.FACILITATOR), player(1, PlayerRole.PARTICIPANT),
                    player(2, PlayerRole.PARTICIPANT), player(3, PlayerRole.PARTICIPANT));

            final ScoreSheet three = ScoreSheet.of(threePlayers(), List.of(trick));
            final ScoreSheet withASpectator = ScoreSheet.of(four, List.of(trick));

            assertThat(withASpectator.rows()).isEqualTo(three.rows());
            assertThat(withASpectator.standings()).hasSize(4);
            assertThat(withASpectator).isNotEqualTo(three);
        }

        @Test
        @DisplayName("two sheets scored from the same play are equal")
        void shouldBeEqualForTheSamePlay() {
            final Trick trick = firstTrickWonBySeatZero();

            final ScoreSheet one = ScoreSheet.of(threePlayers(), List.of(trick));
            final ScoreSheet other = ScoreSheet.of(threePlayers(), List.of(trick));

            assertThat(one).isEqualTo(other).hasSameHashCodeAs(other).isEqualTo(one);
            assertThat(one).isNotEqualTo(ScoreSheet.of(threePlayers(), List.of()));
            assertThat(one).isNotEqualTo("not a sheet");
        }

        @Test
        @DisplayName("describes itself without reproducing the sheet")
        void shouldDescribeItselfWithoutTheSheet() {
            final ScoreSheet sheet = ScoreSheet.of(threePlayers(), List.of(firstTrickWonBySeatZero()));

            assertThat(sheet.toString()).isEqualTo("ScoreSheet[rows=3, players=3, leading=2]");
        }

        @Test
        @DisplayName("hands out rows and standings that a caller cannot modify")
        void shouldHandOutUnmodifiableViews() {
            final ScoreSheet sheet = ScoreSheet.of(threePlayers(), List.of(firstTrickWonBySeatZero()));

            assertThat(sheet.rows()).isUnmodifiable();
            assertThat(sheet.standings()).isUnmodifiable();
            assertThat(sheet.rows().get(0).components()).isUnmodifiable();
        }
    }

    private static Trick firstTrickWonBySeatZero() {
        return TrickBuilder.aTrick()
                .withTrickId(new UUID(1000, 1))
                .withSequence(1)
                .withLeaderSeat(0)
                .withPlays(aPlayBy(0, card(StrideCategory.SPOOFING, Rank.KING)).build(),
                        aPlayBy(1, card(StrideCategory.SPOOFING, Rank.FIVE)).build(),
                        aPlayBy(2, card(StrideCategory.SPOOFING, Rank.SIX)).build())
                .build()
                .resolved();
    }

    private static Trick secondTrickWonBySeatOne() {
        return TrickBuilder.aTrick()
                .withTrickId(new UUID(1000, 2))
                .withSequence(2)
                .withLeaderSeat(0)
                .withPlays(aPlayBy(0, card(StrideCategory.TAMPERING, Rank.FOUR)).build(),
                        aPlayBy(1, card(StrideCategory.TAMPERING, Rank.QUEEN)).build(),
                        aPlayBy(2, card(StrideCategory.TAMPERING, Rank.TWO)).build())
                .build()
                .resolved();
    }

    @Nested
    @DisplayName("STRIDE capture breakdown")
    class StrideCaptureBreakdown {

        @Test
        @DisplayName("attributes all cards in a trick to the winner when the winner plays last")
        void shouldAttributeAllCardsToWinnerWhenWinnerPlaysLast() {
            // Trick: seat 0 leads SPOOFING FIVE, seat 1 plays SPOOFING SIX, seat 2 wins with SPOOFING KING
            final TrickPlay lead = aPlayBy(0, card(StrideCategory.SPOOFING, Rank.FIVE)).build();
            final TrickPlay second = aPlayBy(1, card(StrideCategory.SPOOFING, Rank.SIX)).build();
            final TrickPlay winner = aPlayBy(2, card(StrideCategory.SPOOFING, Rank.KING)).build();
            final Trick trick = TrickBuilder.aTrick().withLeaderSeat(0).withPlays(lead, second, winner).build().resolved();

            final ScoreSheet sheet = ScoreSheet.of(threePlayers(), List.of(trick));

            final Map<UUID, Map<StrideCategory, Integer>> captures = sheet.capturedBySuitByPlayer();
            assertThat(captures).containsOnlyKeys(SEAT_TWO);
            assertThat(captures.get(SEAT_TWO)).containsEntry(StrideCategory.SPOOFING, 3);
        }

        @Test
        @DisplayName("attributes all cards in a trick to the winner when the winner plays first (leader wins)")
        void shouldAttributeAllCardsToWinnerWhenWinnerPlaysFirst() {
            // Trick: seat 0 leads SPOOFING KING (wins), seat 1 plays SPOOFING FIVE, seat 2 plays SPOOFING SIX
            final TrickPlay winner = aPlayBy(0, card(StrideCategory.SPOOFING, Rank.KING)).build();
            final TrickPlay second = aPlayBy(1, card(StrideCategory.SPOOFING, Rank.FIVE)).build();
            final TrickPlay third = aPlayBy(2, card(StrideCategory.SPOOFING, Rank.SIX)).build();
            final Trick trick = TrickBuilder.aTrick().withLeaderSeat(0).withPlays(winner, second, third).build().resolved();

            final ScoreSheet sheet = ScoreSheet.of(threePlayers(), List.of(trick));

            final Map<UUID, Map<StrideCategory, Integer>> captures = sheet.capturedBySuitByPlayer();
            assertThat(captures).containsOnlyKeys(SEAT_ZERO);
            assertThat(captures.get(SEAT_ZERO)).containsEntry(StrideCategory.SPOOFING, 3);
        }

        @Test
        @DisplayName("attributes all cards in a trick to the winner when the winner plays in the middle")
        void shouldAttributeAllCardsToWinnerWhenWinnerPlaysInMiddle() {
            // Trick: seat 0 leads SPOOFING FIVE, seat 1 wins with SPOOFING KING, seat 2 plays SPOOFING SIX
            final TrickPlay lead = aPlayBy(0, card(StrideCategory.SPOOFING, Rank.FIVE)).build();
            final TrickPlay winner = aPlayBy(1, card(StrideCategory.SPOOFING, Rank.KING)).build();
            final TrickPlay third = aPlayBy(2, card(StrideCategory.SPOOFING, Rank.SIX)).build();
            final Trick trick = TrickBuilder.aTrick().withLeaderSeat(0).withPlays(lead, winner, third).build().resolved();

            final ScoreSheet sheet = ScoreSheet.of(threePlayers(), List.of(trick));

            final Map<UUID, Map<StrideCategory, Integer>> captures = sheet.capturedBySuitByPlayer();
            assertThat(captures).containsOnlyKeys(SEAT_ONE);
            assertThat(captures.get(SEAT_ONE)).containsEntry(StrideCategory.SPOOFING, 3);
        }

        @Test
        @DisplayName("accumulates captures across multiple tricks for the same player")
        void shouldAccumulateCapturesAcrossMultipleTricks() {
            // Trick 1: seat 0 wins with SPOOFING KING (3 SPOOFING cards)
            final Trick trick1 = TrickBuilder.aTrick()
                    .withTrickId(new UUID(1000, 10))
                    .withSequence(1)
                    .withLeaderSeat(0)
                    .withPlays(
                            aPlayBy(0, card(StrideCategory.SPOOFING, Rank.KING)).build(),
                            aPlayBy(1, card(StrideCategory.SPOOFING, Rank.FIVE)).build(),
                            aPlayBy(2, card(StrideCategory.SPOOFING, Rank.SIX)).build())
                    .build().resolved();
            // Trick 2: seat 0 wins with TAMPERING KING (2 TAMPERING + 1 SPOOFING)
            final Trick trick2 = TrickBuilder.aTrick()
                    .withTrickId(new UUID(1000, 11))
                    .withSequence(2)
                    .withLeaderSeat(0)
                    .withPlays(
                            aPlayBy(0, card(StrideCategory.TAMPERING, Rank.KING)).build(),
                            aPlayBy(1, card(StrideCategory.TAMPERING, Rank.FIVE)).build(),
                            aPlayBy(2, card(StrideCategory.SPOOFING, Rank.SEVEN)).build())
                    .build().resolved();

            final ScoreSheet sheet = ScoreSheet.of(threePlayers(), List.of(trick1, trick2));

            final Map<UUID, Map<StrideCategory, Integer>> captures = sheet.capturedBySuitByPlayer();
            assertThat(captures).containsOnlyKeys(SEAT_ZERO);
            assertThat(captures.get(SEAT_ZERO)).containsEntry(StrideCategory.SPOOFING, 4);
            assertThat(captures.get(SEAT_ZERO)).containsEntry(StrideCategory.TAMPERING, 2);
        }

        @Test
        @DisplayName("attributes tricks to different winners correctly")
        void shouldAttributeTricksToDifferentWinners() {
            // Trick 1: seat 0 wins (SPOOFING KING)
            final Trick trick1 = TrickBuilder.aTrick()
                    .withTrickId(new UUID(1000, 20))
                    .withSequence(1)
                    .withLeaderSeat(0)
                    .withPlays(
                            aPlayBy(0, card(StrideCategory.SPOOFING, Rank.KING)).build(),
                            aPlayBy(1, card(StrideCategory.SPOOFING, Rank.FIVE)).build())
                    .build().resolved();
            // Trick 2: seat 1 wins (TAMPERING KING)
            final Trick trick2 = TrickBuilder.aTrick()
                    .withTrickId(new UUID(1000, 21))
                    .withSequence(2)
                    .withLeaderSeat(0)
                    .withPlays(
                            aPlayBy(0, card(StrideCategory.TAMPERING, Rank.FIVE)).build(),
                            aPlayBy(1, card(StrideCategory.TAMPERING, Rank.KING)).build())
                    .build().resolved();

            final ScoreSheet sheet = ScoreSheet.of(threePlayers(), List.of(trick1, trick2));

            final Map<UUID, Map<StrideCategory, Integer>> captures = sheet.capturedBySuitByPlayer();
            assertThat(captures.get(SEAT_ZERO)).containsEntry(StrideCategory.SPOOFING, 2);
            assertThat(captures.get(SEAT_ONE)).containsEntry(StrideCategory.TAMPERING, 2);
        }

        @Test
        @DisplayName("returns empty map when no tricks have been played")
        void shouldReturnEmptyMapWhenNoTricks() {
            final ScoreSheet sheet = ScoreSheet.of(threePlayers(), List.of());

            assertThat(sheet.capturedBySuitByPlayer()).isEmpty();
        }

        @Test
        @DisplayName("excludes unresolved tricks from captures")
        void shouldExcludeUnresolvedTricksFromCaptures() {
            // Unresolved trick: only one play, no winner yet
            final TrickPlay lead = aPlayBy(0, card(StrideCategory.SPOOFING, Rank.KING)).build();
            final Trick unresolved = TrickBuilder.aTrick().withLeaderSeat(0).withPlays(lead).build();
            // Not calling .resolved() — trick has no winner

            final ScoreSheet sheet = ScoreSheet.of(threePlayers(), List.of(unresolved));

            assertThat(sheet.capturedBySuitByPlayer()).isEmpty();
        }

        @Test
        @DisplayName("returns unmodifiable maps")
        void shouldReturnUnmodifiableMaps() {
            final TrickPlay winner = aPlayBy(0, card(StrideCategory.SPOOFING, Rank.KING)).build();
            final Trick trick = TrickBuilder.aTrick().withLeaderSeat(0).withPlays(winner).build().resolved();
            final ScoreSheet sheet = ScoreSheet.of(threePlayers(), List.of(trick));

            final Map<UUID, Map<StrideCategory, Integer>> outer = sheet.capturedBySuitByPlayer();
            assertThat(outer).isUnmodifiable();
            assertThat(outer.get(SEAT_ZERO)).isUnmodifiable();
        }
    }
}
