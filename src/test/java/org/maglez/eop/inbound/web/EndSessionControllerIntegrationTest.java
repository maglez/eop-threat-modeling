package org.maglez.eop.inbound.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.maglez.eop.domain.Player;

/**
 * Exercises {@code POST /api/v1/sessions/{sessionId}/end} end to end, through the real use case,
 * the real adapter and the real database.
 *
 * <p>Nothing is stubbed. The interesting claims of this route live in the seams: that the acting
 * player is resolved from the identity token and not from the request body, that a participant is
 * refused before any write, and that a non-in-progress session is refused with 409 rather than
 * 500. None of those survive being mocked out.
 *
 * <p>Every test seats its own table, because a session is cheap and shared fixtures across tests
 * would couple them to each other's timing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("End-session endpoint")
class EndSessionControllerIntegrationTest {

    private static final String SESSIONS = "/api/v1/sessions";

    private static final String PROBLEM_JSON = "application/problem+json";

    @Autowired
    private MockMvc mockMvc;

    @Nested
    @DisplayName("Ending a session in progress")
    class EndingASession {

        @Test
        @DisplayName("returns 204 and the session becomes COMPLETED")
        void shouldEndTheSession() throws Exception {
            final var table = startedTable();

            final var result = endSession(table.sessionId(), table.facilitator().playerToken());

            Assertions.assertThat(result.getResponse().getStatus())
                    .as("the facilitator ends the session and there is nothing to say back")
                    .isEqualTo(204);
            Assertions.assertThat(result.getResponse().getContentAsString())
                    .as("a 204 carries no body")
                    .isEmpty();

            // Verify the session is now COMPLETED by reading its state.
            final var state = mockMvc.perform(get(SESSIONS + "/" + table.sessionId())
                            .header(SessionController.PLAYER_TOKEN_HEADER, table.facilitator().playerToken()))
                    .andExpect(status().isOk())
                    .andReturn();
            final var status = JsonPath.parse(state.getResponse().getContentAsString())
                    .read("$.status", String.class);
            Assertions.assertThat(status)
                    .as("the session must be COMPLETED after the facilitator ends it")
                    .isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("records a game result, so the leaderboard answers after an early end")
        void shouldRecordAGameResultSoTheLeaderboardAnswers() throws Exception {
            final var table = startedTable();

            endSession(table.sessionId(), table.facilitator().playerToken());

            mockMvc.perform(get(SESSIONS + "/" + table.sessionId() + "/leaderboard")
                            .header(SessionController.PLAYER_TOKEN_HEADER, table.facilitator().playerToken()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("refuses a participant, because ending early is the facilitator's job")
        void shouldRefuseAParticipant() throws Exception {
            final var table = startedTable();

            final var refused = endSession(table.sessionId(), table.seats().get(1).playerToken());

            assertProblem(refused, 403, "Only the facilitator can start play");
        }

        @Test
        @DisplayName("refuses a lobby session that has not started play")
        void shouldRefuseALobbySession() throws Exception {
            final var table = seatedTable();

            final var refused = endSession(table.sessionId(), table.facilitator().playerToken());

            assertProblem(refused, 409, "Session is not in progress");
        }

        @Test
        @DisplayName("refuses a second end call, because the session is already COMPLETED")
        void shouldRefuseADoubleEnd() throws Exception {
            final var table = startedTable();
            endSession(table.sessionId(), table.facilitator().playerToken());

            final var again = endSession(table.sessionId(), table.facilitator().playerToken());

            assertProblem(again, 409, "Session is not in progress");
        }

        @Test
        @DisplayName("refuses a caller with no credential")
        void shouldRefuseAMissingToken() throws Exception {
            final var table = startedTable();

            final var anonymous = endSession(table.sessionId(), null);

            assertProblem(anonymous, 403, "Player not recognised");
        }

        @Test
        @DisplayName("reports an unknown session as absent, not as forbidden")
        void shouldReportAnUnknownSession() throws Exception {
            final var table = startedTable();

            final var elsewhere = endSession(UUID.randomUUID().toString(), table.facilitator().playerToken());

            assertProblem(elsewhere, 404, "Session not found");
        }

        @Test
        @DisplayName("refuses a session identifier that is not a UUID")
        void shouldRefuseANonUuidSession() throws Exception {
            final var table = startedTable();

            final var refused = endSession("not-a-uuid", table.facilitator().playerToken());

            Assertions.assertThat(refused.getResponse().getStatus())
                    .as("an unreadable identifier is refused before anything is looked up")
                    .isEqualTo(400);
            assertProblemJson(refused);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Performs {@code POST /api/v1/sessions/{sessionId}/end} with the given token.
     *
     * @param sessionId   the session to end (may be any string, including non-UUIDs)
     * @param playerToken the caller's credential, or null for an absent header
     * @return the raw result
     * @throws Exception if the request cannot be performed
     */
    private MvcResult endSession(final String sessionId, final String playerToken) throws Exception {
        var request = post(SESSIONS + "/" + sessionId + "/end");
        if (playerToken != null) {
            request = request.header(SessionController.PLAYER_TOKEN_HEADER, playerToken);
        }
        return mockMvc.perform(request).andReturn();
    }

    /**
     * Creates a session with three players seated but play not yet started.
     *
     * @return the table
     * @throws Exception if any fixture request fails
     */
    private Table seatedTable() throws Exception {
        final var facilitator = createSession("Ada");
        final var second = joinSession(facilitator.joinCode(), "Grace");
        final var third = joinSession(facilitator.joinCode(), "Alan");
        return new Table(facilitator.sessionId(), java.util.List.of(facilitator, second, third));
    }

    /**
     * Creates a session with three players seated and play started.
     *
     * @return the started table
     * @throws Exception if any fixture request fails
     */
    private Table startedTable() throws Exception {
        final var table = seatedTable();
        mockMvc.perform(post(SESSIONS + "/" + table.sessionId() + "/start")
                        .header(SessionController.PLAYER_TOKEN_HEADER, table.facilitator().playerToken()))
                .andExpect(status().isOk());
        return table;
    }

    private Admission createSession(final String displayName) throws Exception {
        final var result = mockMvc.perform(post(SESSIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"%s\"}".formatted(displayName)))
                .andExpect(status().isCreated())
                .andReturn();
        return admissionFrom(result.getResponse().getContentAsString());
    }

    private Admission joinSession(final String joinCode, final String displayName) throws Exception {
        final var result = mockMvc.perform(post(SESSIONS + "/" + joinCode + "/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"%s\"}".formatted(displayName)))
                .andExpect(status().isOk())
                .andReturn();
        return admissionFrom(result.getResponse().getContentAsString());
    }

    private static Admission admissionFrom(final String body) {
        final var document = JsonPath.parse(body);
        final var playerId = document.read("$.playerId", String.class);
        final java.util.List<java.util.Map<String, Object>> players = document.read("$.session.players");
        final var seatOrder = players.stream()
                .filter(player -> playerId.equals(player.get("playerId")))
                .map(player -> (Integer) player.get("seatOrder"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("the admitted player is missing from the session state"));
        return new Admission(
                document.read("$.session.sessionId", String.class),
                document.read("$.session.joinCode", String.class),
                playerId,
                document.read("$.playerToken", String.class),
                seatOrder);
    }

    private static void assertProblem(final MvcResult result, final int status, final String title) throws Exception {
        Assertions.assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertProblemJson(result);
        Assertions.assertThat(JsonPath.parse(result.getResponse().getContentAsString()).read("$.title", String.class))
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

    private record Admission(String sessionId, String joinCode, String playerId, String playerToken, int seatOrder) {
    }

    private record Table(String sessionId, java.util.List<Admission> seats) {

        Admission facilitator() {
            return seats.get(0);
        }
    }
}
