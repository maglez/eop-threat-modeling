package org.maglez.eop.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.maglez.eop.domain.GameSessionBuilder.aSession;
import static org.maglez.eop.domain.PlayerBuilder.aParticipant;
import static org.maglez.eop.domain.PlayerBuilder.aPlayer;
import static org.maglez.eop.domain.TrickBuilder.aTrick;
import static org.maglez.eop.domain.TrickPlayBuilder.aPlayBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Exercises the {@link GameResult} domain record.
 *
 * <p>Covers construction validation, the {@code of} factory, and the immutability
 * guarantee on the standings list.
 */
@org.junit.jupiter.api.DisplayName("GameResult")
class GameResultTest {

    private static final UUID RESULT_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-7000-8000-0000000000ff");
    private static final Instant STARTED_AT = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant FINALISED_AT = Instant.parse("2026-01-01T11:00:00Z");

    @Nested
    @org.junit.jupiter.api.DisplayName("construction")
    class Construction {

        @Test
        @org.junit.jupiter.api.DisplayName("rejects a null gameResultId")
        void shouldRejectNullGameResultId() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new GameResult(null, SESSION_ID, new DisplayName("Ada"),
                            STARTED_AT, FINALISED_AT, List.of(aStanding())));
        }

        @Test
        @org.junit.jupiter.api.DisplayName("rejects a null sessionId")
        void shouldRejectNullSessionId() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new GameResult(RESULT_ID, null, new DisplayName("Ada"),
                            STARTED_AT, FINALISED_AT, List.of(aStanding())));
        }

        @Test
        @org.junit.jupiter.api.DisplayName("rejects a null facilitatorName")
        void shouldRejectNullFacilitatorName() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new GameResult(RESULT_ID, SESSION_ID, null,
                            STARTED_AT, FINALISED_AT, List.of(aStanding())));
        }

        @Test
        @org.junit.jupiter.api.DisplayName("rejects a null startedAt")
        void shouldRejectNullStartedAt() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new GameResult(RESULT_ID, SESSION_ID, new DisplayName("Ada"),
                            null, FINALISED_AT, List.of(aStanding())));
        }

        @Test
        @org.junit.jupiter.api.DisplayName("rejects a null finalisedAt")
        void shouldRejectNullFinalisedAt() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new GameResult(RESULT_ID, SESSION_ID, new DisplayName("Ada"),
                            STARTED_AT, null, List.of(aStanding())));
        }

        @Test
        @org.junit.jupiter.api.DisplayName("rejects a null standings list")
        void shouldRejectNullStandings() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new GameResult(RESULT_ID, SESSION_ID, new DisplayName("Ada"),
                            STARTED_AT, FINALISED_AT, null));
        }

        @Test
        @org.junit.jupiter.api.DisplayName("rejects an empty standings list")
        void shouldRejectEmptyStandings() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new GameResult(RESULT_ID, SESSION_ID, new DisplayName("Ada"),
                            STARTED_AT, FINALISED_AT, List.of()))
                    .withMessageContaining("at least one standing");
        }

        @Test
        @org.junit.jupiter.api.DisplayName("rejects finalisedAt before startedAt")
        void shouldRejectFinalisedAtBeforeStartedAt() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new GameResult(RESULT_ID, SESSION_ID, new DisplayName("Ada"),
                            FINALISED_AT, STARTED_AT, List.of(aStanding())))
                    .withMessageContaining("finalisedAt must not be before startedAt");
        }

        @Test
        @org.junit.jupiter.api.DisplayName("accepts finalisedAt equal to startedAt")
        void shouldAcceptFinalisedAtEqualToStartedAt() {
            final var result = new GameResult(RESULT_ID, SESSION_ID, new DisplayName("Ada"),
                    STARTED_AT, STARTED_AT, List.of(aStanding()));
            assertThat(result.finalisedAt()).isEqualTo(STARTED_AT);
        }

        @Test
        @org.junit.jupiter.api.DisplayName("makes the standings list immutable")
        void shouldMakeStandingsImmutable() {
            final var result = new GameResult(RESULT_ID, SESSION_ID, new DisplayName("Ada"),
                    STARTED_AT, FINALISED_AT, List.of(aStanding()));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> result.standings().add(aStanding()));
        }
    }

    @Nested
    @org.junit.jupiter.api.DisplayName("factory of()")
    class Factory {

        @Test
        @org.junit.jupiter.api.DisplayName("uses the facilitator's display name")
        void shouldUseFacilitatorDisplayName() {
            final var session = aSession()
                    .withSessionId(SESSION_ID)
                    .withStatus(SessionStatus.COMPLETED)
                    .withPlayers(List.of(
                            aPlayer().withDisplayName(new DisplayName("Ada")).build(),
                            aParticipant(1).withDisplayName(new DisplayName("Grace")).build()))
                    .build();
            final var scoreSheet = scoreSheetFor(session);

            final var result = GameResult.of(RESULT_ID, session, scoreSheet, STARTED_AT, FINALISED_AT);

            assertThat(result.facilitatorName().value()).isEqualTo("Ada");
        }

        @Test
        @org.junit.jupiter.api.DisplayName("falls back to the first player when no facilitator is found")
        void shouldFallBackToFirstPlayerWhenNoFacilitator() {
            final var session = aSession()
                    .withSessionId(SESSION_ID)
                    .withStatus(SessionStatus.COMPLETED)
                    .withPlayers(List.of(
                            aParticipant(0).withDisplayName(new DisplayName("Grace")).build(),
                            aParticipant(1).withDisplayName(new DisplayName("Alan")).build()))
                    .build();
            final var scoreSheet = scoreSheetFor(session);

            final var result = GameResult.of(RESULT_ID, session, scoreSheet, STARTED_AT, FINALISED_AT);

            assertThat(result.facilitatorName().value()).isEqualTo("Grace");
        }

        @Test
        @org.junit.jupiter.api.DisplayName("carries the session identifier")
        void shouldCarrySessionId() {
            final var session = aSession()
                    .withSessionId(SESSION_ID)
                    .withStatus(SessionStatus.COMPLETED)
                    .withPlayerCount(3)
                    .build();
            final var scoreSheet = scoreSheetFor(session);

            final var result = GameResult.of(RESULT_ID, session, scoreSheet, STARTED_AT, FINALISED_AT);

            assertThat(result.sessionId()).isEqualTo(SESSION_ID);
            assertThat(result.gameResultId()).isEqualTo(RESULT_ID);
            assertThat(result.startedAt()).isEqualTo(STARTED_AT);
            assertThat(result.finalisedAt()).isEqualTo(FINALISED_AT);
        }

        @Test
        @org.junit.jupiter.api.DisplayName("standings come from the score sheet")
        void shouldDeriveStandingsFromScoreSheet() {
            final var session = aSession()
                    .withSessionId(SESSION_ID)
                    .withStatus(SessionStatus.COMPLETED)
                    .withPlayerCount(3)
                    .build();
            final var scoreSheet = scoreSheetFor(session);

            final var result = GameResult.of(RESULT_ID, session, scoreSheet, STARTED_AT, FINALISED_AT);

            assertThat(result.standings()).hasSize(3);
        }

        @Test
        @org.junit.jupiter.api.DisplayName("rejects null arguments")
        void shouldRejectNullArguments() {
            final var session = aSession().withPlayerCount(3).build();
            final var scoreSheet = scoreSheetFor(session);

            assertThatNullPointerException().isThrownBy(
                    () -> GameResult.of(null, session, scoreSheet, STARTED_AT, FINALISED_AT));
            assertThatNullPointerException().isThrownBy(
                    () -> GameResult.of(RESULT_ID, null, scoreSheet, STARTED_AT, FINALISED_AT));
            assertThatNullPointerException().isThrownBy(
                    () -> GameResult.of(RESULT_ID, session, null, STARTED_AT, FINALISED_AT));
            assertThatNullPointerException().isThrownBy(
                    () -> GameResult.of(RESULT_ID, session, scoreSheet, null, FINALISED_AT));
            assertThatNullPointerException().isThrownBy(
                    () -> GameResult.of(RESULT_ID, session, scoreSheet, STARTED_AT, null));
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Standing aStanding() {
        return new Standing(UUID.randomUUID(), 0, new DisplayName("Ada"), 0, 1, false);
    }

    /**
     * Builds a score sheet from a session with no tricks played — every player on zero.
     */
    private static ScoreSheet scoreSheetFor(final GameSession session) {
        return ScoreSheet.of(session.players(), List.of());
    }
}
