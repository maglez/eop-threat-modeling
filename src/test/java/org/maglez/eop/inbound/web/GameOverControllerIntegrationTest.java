package org.maglez.eop.inbound.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.maglez.eop.application.port.in.PersistGameResultPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import org.maglez.eop.domain.Player;

/**
 * Exercises the game-over routes end to end, through the real use cases, the real adapter and the
 * real database.
 *
 * <p>Nothing is stubbed. The interesting claims of these routes live in the seams: that the acting
 * player is resolved from the identity token, that a non-completed session is refused with 409, and
 * that a participant cannot start a new game. None of those survive being mocked out.
 *
 * <p>The flag-off class is a separate {@code @SpringBootTest} context so that the flag-on tests
 * can run in the shared context without paying for a second context start.
 */
@SpringBootTest(properties = "eop.features.game-over=true")
@AutoConfigureMockMvc
@DisplayName("Game-over endpoints (flag ON)")
class GameOverControllerIntegrationTest {

    private static final String SESSIONS = "/api/v1/sessions";

    private static final String PROBLEM_JSON = "application/problem+json";

    private static final String UNKNOWN_SESSION_ID = "00000000-0000-7000-8000-0000000000ff";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PersistGameResultPort persistGameResultPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Nested
    @DisplayName("GET /leaderboard — reading the leaderboard")
    class GetLeaderboard {

        @Test
        @DisplayName("refuses a session that is still in progress with 409")
        void shouldRefuseInProgressSession() throws Exception {
            final var table = startedTable();

            final var result = getLeaderboard(table.sessionId(), table.facilitator().playerToken());

            assertProblem(result, 409, "Game not completed");
        }

        @Test
        @DisplayName("refuses a caller with no credential")
        void shouldRefuseMissingToken() throws Exception {
            final var table = startedTable();

            final var result = getLeaderboard(table.sessionId(), null);

            assertProblem(result, 403, "Player not recognised");
        }

        @Test
        @DisplayName("reports an unknown session as absent")
        void shouldReportUnknownSession() throws Exception {
            final var table = startedTable();

            final var result = getLeaderboard(UNKNOWN_SESSION_ID, table.facilitator().playerToken());

            assertProblem(result, 404, "Session not found");
        }

        @Test
        @DisplayName("refuses a session identifier that is not a UUID")
        void shouldRefuseNonUuidSessionId() throws Exception {
            final var result = getLeaderboard("not-a-uuid", "any-token");

            Assertions.assertThat(result.getResponse().getStatus())
                    .as("an unreadable identifier is refused before anything is looked up")
                    .isEqualTo(400);
            assertProblemJson(result);
        }
    }

    @Nested
    @DisplayName("POST /new-game — starting a new game")
    class PostNewGame {

        @Test
        @DisplayName("refuses a session that is still in progress with 409")
        void shouldRefuseInProgressSession() throws Exception {
            final var table = startedTable();

            final var result = postNewGame(table.sessionId(), table.facilitator().playerToken());

            assertProblem(result, 409, "Game not completed");
        }

        @Test
        @DisplayName("refuses a participant, because starting a new game is the facilitator's job")
        void shouldRefuseAParticipant() throws Exception {
            final var table = startedTable();

            final var result = postNewGame(table.sessionId(), table.seats().get(1).playerToken());

            assertProblem(result, 403, "Only the facilitator can start play");
        }

        @Test
        @DisplayName("refuses a caller with no credential")
        void shouldRefuseMissingToken() throws Exception {
            final var table = startedTable();

            final var result = postNewGame(table.sessionId(), null);

            assertProblem(result, 403, "Player not recognised");
        }

        @Test
        @DisplayName("reports an unknown session as absent")
        void shouldReportUnknownSession() throws Exception {
            final var table = startedTable();

            final var result = postNewGame(UNKNOWN_SESSION_ID, table.facilitator().playerToken());

            assertProblem(result, 404, "Session not found");
        }

        @Test
        @DisplayName("refuses a session identifier that is not a UUID")
        void shouldRefuseNonUuidSessionId() throws Exception {
            final var result = postNewGame("not-a-uuid", "any-token");

            Assertions.assertThat(result.getResponse().getStatus())
                    .as("an unreadable identifier is refused before anything is looked up")
                    .isEqualTo(400);
            assertProblemJson(result);
        }
    }

    /**
     * Regression guards for the RFC 9457 contract on the leaderboard route.
     *
     * <p>Fault 1 of EOP-82 caused {@code GET /api/v1/sessions/{id}/leaderboard} to fall through
     * to Spring's static-resource handler when the {@code eop.features.game-over} flag was
     * {@code false}, producing a framework 404 whose detail contained
     * {@code "No static resource api/v1/sessions/…/leaderboard."} rather than an application
     * problem document. The flag is now {@code true} by default (verified by
     * {@code ShippedFeatureFlagDefaultsTest}), but a future refactor of the conditional, the
     * request-mapping path, or the {@code @ControllerAdvice} could reintroduce a framework 404
     * while the flag stays {@code true}. These tests fail in that world and pass only when the
     * route is mapped and the {@code GlobalExceptionHandler} is in the dispatch chain.
     *
     * <p>EOP-83 checked the claim this javadoc used to make — that the 409 for a non-completed
     * session was undocumented — and it was false: {@code docs/api/openapi.yml} has carried a full
     * {@code '409'} block on this operation, with an {@code application/problem+json} body, since
     * before that ticket was written. What the spec really got wrong sat elsewhere, in the
     * {@code Leaderboard} schema description, which claimed the leaderboard was "available in any
     * session state, not only {@code COMPLETED}" and so contradicted the operation immediately
     * above it as well as the use case. The schema now agrees with both.
     */
    @Nested
    @DisplayName("RFC 9457 contract guards — leaderboard route")
    class Rfc9457ContractGuards {

        @Test
        @DisplayName("unknown session returns application 404, not a framework static-resource 404")
        void leaderboard404IsApplicationProblemNotFramework() throws Exception {
            final var table = startedTable();

            final var result = getLeaderboard(UNKNOWN_SESSION_ID, table.facilitator().playerToken());

            // Status must be 404
            Assertions.assertThat(result.getResponse().getStatus())
                    .as("unknown session id returns 404")
                    .isEqualTo(404);

            // Content-Type must be application/problem+json — a framework 404 returns
            // application/json or text/html, not the RFC 9457 media type
            assertProblemJson(result);

            final var document = JsonPath.parse(result.getResponse().getContentAsString());

            // Title must be the application's own title, not Spring's "Not Found"
            Assertions.assertThat(document.read("$.title", String.class))
                    .as("title is the application's own, not the framework's")
                    .isEqualTo("Session not found");

            // Detail must contain the session identifier the caller supplied
            Assertions.assertThat(document.read("$.detail", String.class))
                    .as("detail names the unknown session identifier")
                    .contains(UNKNOWN_SESSION_ID);

            // THE REGRESSION GUARD: the framework 404 detail contains this literal string.
            // If this assertion fails the route has fallen through to static-resource handling.
            Assertions.assertThat(document.read("$.detail", String.class))
                    .as("detail must not contain the framework static-resource signature — "
                            + "if it does, the route is not mapped and the flag guard has been bypassed")
                    .doesNotContain("No static resource");
        }

        @Test
        @DisplayName("non-completed session returns 409 with full RFC 9457 body including detail")
        void leaderboard409ForNonCompletedSessionHasFullProblemBody() throws Exception {
            final var table = startedTable();

            final var result = getLeaderboard(table.sessionId(), table.facilitator().playerToken());

            // Status
            Assertions.assertThat(result.getResponse().getStatus())
                    .as("in-progress session returns 409 Conflict")
                    .isEqualTo(409);

            // Content-Type
            assertProblemJson(result);

            final var document = JsonPath.parse(result.getResponse().getContentAsString());

            // Title
            Assertions.assertThat(document.read("$.title", String.class))
                    .as("title is 'Game not completed'")
                    .isEqualTo("Game not completed");

            // Detail — GlobalExceptionHandler builds: "Session " + sessionId + " is not yet completed."
            Assertions.assertThat(document.read("$.detail", String.class))
                    .as("detail names the session and states it is not yet completed")
                    .contains(table.sessionId())
                    .contains("not yet completed");
        }

        @Test
        @DisplayName("completed session with no recorded result is distinguishable from an absent session")
        void leaderboard404ForUnrecordedResultIsDistinctFromSessionNotFound() throws Exception {
            final var table = endedTableWithoutResult();

            final var result = getLeaderboard(table.sessionId(), table.facilitator().playerToken());

            Assertions.assertThat(result.getResponse().getStatus())
                    .as("the status stays 404, so the front end's EOP-82 handling still applies")
                    .isEqualTo(404);
            assertProblemJson(result);

            final var document = JsonPath.parse(result.getResponse().getContentAsString());

            Assertions.assertThat(document.read("$.title", String.class))
                    .as("the title separates an unrecorded result from an absent session")
                    .isEqualTo("Game result not recorded");

            Assertions.assertThat(document.read("$.detail", String.class))
                    .as("the detail names the session and states what is actually wrong")
                    .contains(table.sessionId())
                    .contains("no result was recorded");

            // THE REGRESSION GUARD: before EOP-86 this path threw SessionNotFoundException, so a
            // seated player was told their own session did not exist. If either assertion fails,
            // the two meanings have been collapsed back onto one exception type.
            Assertions.assertThat(document.read("$.detail", String.class))
                    .as("a seated player is never told the session they are sitting at is absent")
                    .doesNotContain("No session found");
            Assertions.assertThat(document.read("$.detail", String.class))
                    .as("detail must not contain the framework static-resource signature")
                    .doesNotContain("No static resource");
        }

        @Test
        @DisplayName("a seated participant, not only the facilitator, sees the unrecorded-result 404")
        void leaderboard404ForUnrecordedResultReachesAnySeatedPlayer() throws Exception {
            // The security argument for splitting the 404 title is that only a seated player can
            // observe it — a stranger is stopped with 403 by ResolvePlayerPort before the result
            // row is read. "Seated" must therefore mean every seat, not just seat 0: a participant
            // has to receive the same problem body rather than a 403 or the old absent-session text.
            final var table = endedTableWithoutResult();
            final var participant = table.seats().get(1);

            final var result = getLeaderboard(table.sessionId(), participant.playerToken());

            Assertions.assertThat(result.getResponse().getStatus())
                    .as("a participant is seated, so this is 404 rather than the stranger's 403")
                    .isEqualTo(404);
            assertProblemJson(result);

            final var document = JsonPath.parse(result.getResponse().getContentAsString());

            Assertions.assertThat(document.read("$.title", String.class))
                    .as("a participant sees the same title the facilitator sees")
                    .isEqualTo("Game result not recorded");
            Assertions.assertThat(document.read("$.detail", String.class))
                    .as("and is never told the session they are sitting at is absent")
                    .doesNotContain("No session found");
        }
    }

    @Nested
    @DisplayName("Happy path — completed session")
    class HappyPath {

        @Test
        @DisplayName("returns the leaderboard for a completed session")
        void shouldReturnLeaderboardForCompletedSession() throws Exception {
            final var table = completedTable();

            final var result = getLeaderboard(table.sessionId(), table.facilitator().playerToken());

            Assertions.assertThat(result.getResponse().getStatus())
                    .as("a completed session with a persisted result returns 200")
                    .isEqualTo(200);
            final var body = result.getResponse().getContentAsString();
            final var document = JsonPath.parse(body);
            final List<Map<String, Object>> rows = document.read("$.rows");
            Assertions.assertThat(rows)
                    .as("one leaderboard row per seated player")
                    .hasSize(3);
            Assertions.assertThat(document.read("$.sessionStatus", String.class))
                    .as("session status is COMPLETED")
                    .isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("facilitator can start a new game after the session is completed")
        void shouldAllowFacilitatorToStartNewGame() throws Exception {
            final var table = completedTable();

            final var result = postNewGame(table.sessionId(), table.facilitator().playerToken());

            Assertions.assertThat(result.getResponse().getStatus())
                    .as("facilitator starting a new game on a completed session returns 204")
                    .isEqualTo(204);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Creates a session with three players, starts it, ends it via POST /end (forcing COMPLETED),
     * then persists the game result so the leaderboard is readable.
     */
    private Table completedTable() throws Exception {
        final var table = endedTableWithoutResult();
        // Persist the game result (normally triggered by ResolveTrickPort; here we call directly
        // because EndSessionPort does not go through the trick-resolution path)
        persistGameResultPort.execute(UUID.fromString(table.sessionId()));
        return table;
    }

    /**
     * Creates a session with three players, starts it, ends it via POST /end and removes the
     * recorded game result, leaving the session {@code COMPLETED} with no result row.
     *
     * <p>Since EOP-256 {@code EndSessionPort} persists the result on the early-end path, so
     * POST /end alone no longer leaves this state. The delete restores it: it stands for a session
     * completed before the fix, or one whose best-effort persistence failed and was logged while
     * the session still completed. {@link #completedTable()} calls this then persists by hand
     * afterwards to reach the happy path.
     *
     * @return a table whose session is completed with no result row
     */
    private Table endedTableWithoutResult() throws Exception {
        final var table = startedTable();
        mockMvc.perform(post(SESSIONS + "/" + table.sessionId() + "/end")
                        .header(SessionController.PLAYER_TOKEN_HEADER, table.facilitator().playerToken()))
                .andExpect(status().isNoContent());
        jdbcTemplate.update(
                "DELETE FROM game_result_player WHERE game_result_id IN "
                        + "(SELECT id FROM game_result WHERE game_session_id = ?)",
                UUID.fromString(table.sessionId()));
        jdbcTemplate.update(
                "DELETE FROM game_result WHERE game_session_id = ?",
                UUID.fromString(table.sessionId()));
        return table;
    }

    private MvcResult getLeaderboard(final String sessionId, final String playerToken) throws Exception {
        var request = get(SESSIONS + "/" + sessionId + "/leaderboard");
        if (playerToken != null) {
            request = request.header(SessionController.PLAYER_TOKEN_HEADER, playerToken);
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult postNewGame(final String sessionId, final String playerToken) throws Exception {
        var request = post(SESSIONS + "/" + sessionId + "/new-game");
        if (playerToken != null) {
            request = request.header(SessionController.PLAYER_TOKEN_HEADER, playerToken);
        }
        return mockMvc.perform(request).andReturn();
    }

    private Table startedTable() throws Exception {
        final var facilitator = createSession("Ada");
        final var second = joinSession(facilitator.joinCode(), "Grace");
        final var third = joinSession(facilitator.joinCode(), "Alan");
        final var table = new Table(facilitator.sessionId(), List.of(facilitator, second, third));
        mockMvc.perform(post(SESSIONS + "/" + table.sessionId() + "/start")
                        .header(SessionController.PLAYER_TOKEN_HEADER, table.facilitator().playerToken()))
                .andExpect(status().isOk());
        return table;
    }

    private Admission createSession(final String displayName) throws Exception {
        final var body = mockMvc.perform(post(SESSIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"%s\"}".formatted(displayName)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return admissionFrom(body);
    }

    private Admission joinSession(final String joinCode, final String displayName) throws Exception {
        final var body = mockMvc.perform(post(SESSIONS + "/" + joinCode + "/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"%s\"}".formatted(displayName)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return admissionFrom(body);
    }

    private static Admission admissionFrom(final String body) {
        final var document = JsonPath.parse(body);
        final var playerId = document.read("$.playerId", String.class);
        final List<Map<String, Object>> players = document.read("$.session.players");
        final var seatOrder = players.stream()
                .filter(player -> playerId.equals(player.get("playerId")))
                .map(player -> (Integer) player.get("seatOrder"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "the admitted player is missing from the session state"));
        return new Admission(
                document.read("$.session.sessionId", String.class),
                document.read("$.session.joinCode", String.class),
                playerId,
                document.read("$.playerToken", String.class),
                seatOrder);
    }

    private static void assertProblem(final MvcResult result, final int status, final String title)
            throws Exception {
        Assertions.assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertProblemJson(result);
        Assertions.assertThat(
                        JsonPath.parse(result.getResponse().getContentAsString()).read("$.title", String.class))
                .isEqualTo(title);
    }

    /**
     * Asserts a result carries a well-formed RFC 9457 problem document.
     *
     * <p>Checks three mandatory invariants:
     * <ul>
     *   <li>Content-Type starts with {@code application/problem+json}</li>
     *   <li>{@code $.status} matches the HTTP status line (RFC 9457 §3.1)</li>
     *   <li>{@code $.detail} is present and non-blank</li>
     * </ul>
     *
     * <p>Note: {@code $.type} is intentionally not asserted. RFC 9457 §3.1 makes it optional
     * with a default of {@code about:blank}, and Spring omits it from the serialised body
     * while it holds that default value.
     *
     * @param result the result to inspect
     * @throws Exception if the response body cannot be read
     */
    private static void assertProblemJson(final MvcResult result) throws Exception {
        Assertions.assertThat(result.getResponse().getContentType())
                .as("every refusal is an RFC 9457 problem document")
                .startsWith(PROBLEM_JSON);
        final var doc = JsonPath.parse(result.getResponse().getContentAsString());
        Assertions.assertThat(doc.read("$.status", Integer.class))
                .as("$.status body member must agree with the HTTP status line")
                .isEqualTo(result.getResponse().getStatus());
        Assertions.assertThat(doc.read("$.detail", String.class))
                .as("$.detail must be present and non-blank")
                .isNotBlank();
    }

    private record Admission(String sessionId, String joinCode, String playerId, String playerToken,
            int seatOrder) {
    }

    private record Table(String sessionId, List<Admission> seats) {

        Admission facilitator() {
            return seats.get(0);
        }
    }
}
