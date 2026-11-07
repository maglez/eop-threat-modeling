package org.maglez.eop.inbound.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.maglez.eop.domain.PlayerBuilder.aPlayer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.domain.ScoreSheet;
import org.maglez.eop.domain.SessionStatus;
import org.maglez.eop.application.port.in.GetLeaderboardPort;
import org.maglez.eop.application.port.in.LeaderboardResult;
import org.maglez.eop.application.port.in.NewGamePort;
import org.maglez.eop.domain.PlayerBuilder;

/**
 * Unit tests for {@link GameOverController}, with no Spring context.
 *
 * <p>These exist for one reason that no integration test can serve. Until EOP-87 the controller wrote
 * the string {@code "COMPLETED"} into the response itself, and every HTTP-level test passed — not
 * because the value was derived, but because {@link GetLeaderboardPort} refuses every status other
 * than {@link SessionStatus#COMPLETED} one step earlier with a 409. A literal and a derivation are
 * indistinguishable through the HTTP surface, so a test that drives the adapter through the whole
 * stack cannot tell them apart however it is written.
 *
 * <p>Stubbing the use case removes that guard and lets the adapter be handed a status it could never
 * see in production today. If the controller echoes it, the value is genuinely derived. If the
 * controller reinstated a literal, these tests fail while every other test in the suite still passes.
 *
 * <p>The controller's constructor is package-private, so this test lives in its package and
 * constructs it directly rather than through the container.
 */
@DisplayName("GameOverController (unit)")
class GameOverControllerTest {

    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-7000-8000-0000000000d0");

    private static final String PLAYER_TOKEN = "a-token";

    private final GetLeaderboardPort getLeaderboardPort = mock(GetLeaderboardPort.class);

    private final NewGamePort newGamePort = mock(NewGamePort.class);

    private final GameOverController controller =
            new GameOverController(getLeaderboardPort, newGamePort);

    @Test
    @DisplayName("echoes the status the use case resolved rather than a literal")
    void shouldEchoTheResolvedSessionStatus() {
        // A status the use case cannot return today, precisely so that a hardcoded "COMPLETED"
        // cannot pass. The guard that makes this unreachable lives in the use case, not here.
        givenTheUseCaseReturns(SessionStatus.IN_PROGRESS);

        final LeaderboardDto returned = controller.getLeaderboard(SESSION_ID, PLAYER_TOKEN);

        assertThat(returned.sessionStatus())
                .as("the adapter must report the status it was handed, not one of its own")
                .isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("reports COMPLETED for a completed session, unchanged from before EOP-87")
    void shouldReportCompletedForACompletedSession() {
        givenTheUseCaseReturns(SessionStatus.COMPLETED);

        final LeaderboardDto returned = controller.getLeaderboard(SESSION_ID, PLAYER_TOKEN);

        assertThat(returned.sessionStatus())
                .as("the only status reachable in production must still serialise as before")
                .isEqualTo("COMPLETED");
    }

    private void givenTheUseCaseReturns(final SessionStatus status) {
        final ScoreSheet scoreSheet = ScoreSheet.of(List.of(aPlayer().build()), List.of());
        when(getLeaderboardPort.execute(SESSION_ID, PLAYER_TOKEN))
                .thenReturn(new LeaderboardResult(status, scoreSheet));
    }
}
