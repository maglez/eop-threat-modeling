package org.maglez.eop.inbound.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.maglez.eop.domain.IdentityTokenHash;
import org.maglez.eop.domain.JoinCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.maglez.eop.domain.Player;

import org.maglez.eop.outbound.web.InMemoryJoinAttemptLimiter;

/**
 * Verifies the session endpoints end to end: the request travels through the
 * controller, the use cases, the real generators and a real database, and the
 * response is checked against the committed contract in {@code docs/api/openapi.yml}.
 *
 * <p>Nothing here stubs a collaborator. A test that mocked the repository would
 * assert that the controller calls the code the controller calls, which is a
 * tautology; the questions worth asking of this layer — does a refused credential
 * become a 403 rather than a 400, does a full table become a 409 rather than a
 * leaked invariant, is every unusable join code answered identically — can only be
 * answered by the assembled application.
 *
 * <p>The credential header is reached through {@link SessionController#PLAYER_TOKEN_HEADER}
 * rather than spelled out. Repeating the name here would let a rename pass the
 * build while breaking every client.
 *
 * <p>Before EOP-26, every join that was expected to fail carried its own
 * {@code X-Forwarded-For} address so that failures would not accumulate against
 * the shared peer ({@code 127.0.0.1} in MockMvc). That strategy only ever worked
 * because the header was trusted unconditionally — the exact vulnerability EOP-26
 * was filed to close. With the fix in place the header is ignored (no trusted proxy
 * is configured in the test properties), so the old per-test address rotation became
 * a no-op that silently lied about isolation. The {@link ThrottlingGuesses} nested
 * class now carries its own {@code @SpringBootTest} with a dedicated in-memory
 * database ({@code throttle-test}). That datasource URL is load-bearing for test
 * isolation: it makes the nested class's Spring context cache key unique, so Spring
 * allocates a separate {@link InMemoryJoinAttemptLimiter} singleton for it rather
 * than sharing the one from the outer class's context. Without that distinct URL the
 * two contexts would share a limiter and failures from one test would bleed into
 * another. {@code @DirtiesContext(BEFORE_EACH_TEST_METHOD)} gives each test method
 * within the class a fresh limiter so that the ten-failure window resets between
 * methods. The helper that used to generate per-test addresses has been renamed to
 * {@code unusedAddressHint()} and its Javadoc updated to record that the value it
 * returns is sent as a header that the application now ignores; it is kept only
 * where removing it would require restructuring the call site, and callers that do
 * not need it have been updated to drop it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Session endpoints")
class SessionControllerIntegrationTest {

    private static final String SESSIONS = "/api/v1/sessions";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String FORWARDED_FOR = "X-Forwarded-For";

    /** A lower-case 64 character run: the shape of a stored credential digest. */
    private static final String ANY_DIGEST = ".*\\b[0-9a-f]{64}\\b.*";

    /** The address window the limiter allows before it starts refusing. */
    private static final int TOLERATED_FAILURES = 10;

    /** Distinguishes the addresses and codes one test invents from another's. */
    private static final AtomicInteger SERIAL = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * What a caller keeps after being admitted to a session.
     *
     * @param sessionId   the session it was admitted to
     * @param joinCode    the code that seats further players
     * @param playerId    the caller's own identifier
     * @param playerToken the caller's credential, in plaintext, as only it ever sees it
     * @param body        the whole response, for tests asking what was disclosed
     */
    private record Admission(String sessionId, String joinCode, String playerId, String playerToken, String body) {
    }

    // ---------------------------------------------------------------- helpers

    private Admission createSession(final String displayName) throws Exception {
        final var body = mockMvc.perform(post(SESSIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nameRequest(displayName)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return admissionFrom(body);
    }

    private Admission joinSession(final String joinCode, final String displayName) throws Exception {
        final var body = mockMvc.perform(post(SESSIONS + "/" + joinCode + "/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nameRequest(displayName)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return admissionFrom(body);
    }

    private ResultActions attemptJoin(final String joinCode, final String address) throws Exception {
        return mockMvc.perform(post(SESSIONS + "/" + joinCode + "/players")
                .header(FORWARDED_FOR, address)
                .contentType(MediaType.APPLICATION_JSON)
                .content(nameRequest("Hopeful")));
    }

    private ResultActions readState(final String sessionId, final String playerToken) throws Exception {
        final var request = get(SESSIONS + "/" + sessionId);
        if (playerToken != null) {
            request.header(SessionController.PLAYER_TOKEN_HEADER, playerToken);
        }
        return mockMvc.perform(request);
    }

    private ResultActions startPlay(final String sessionId, final String playerToken) throws Exception {
        return mockMvc.perform(post(SESSIONS + "/" + sessionId + "/start")
                .header(SessionController.PLAYER_TOKEN_HEADER, playerToken));
    }

    private static String nameRequest(final String displayName) {
        return "{\"displayName\":\"%s\"}".formatted(displayName);
    }

    private static Admission admissionFrom(final String body) {
        final var json = JsonPath.parse(body);
        return new Admission(
                json.read("$.session.sessionId"),
                json.read("$.session.joinCode"),
                json.read("$.playerId"),
                json.read("$.playerToken"),
                body);
    }

    /** A well-formed code no session holds, unique to the calling test. */
    private static String unheldCode() {
        final var alphabet = JoinCode.ALPHABET;
        var remaining = SERIAL.incrementAndGet();
        final var drawn = new StringBuilder("ZZZZZZZZ");
        for (int position = 0; position < JoinCode.LENGTH && remaining > 0; position++) {
            drawn.setCharAt(position, alphabet.charAt(remaining % alphabet.length()));
            remaining /= alphabet.length();
        }
        return drawn.toString();
    }

    /**
     * Returns a unique address string that is sent as {@code X-Forwarded-For}.
     *
     * <p>Before EOP-26 this was the test suite's isolation mechanism: each failing
     * join attempt carried a distinct address so failures would not accumulate against
     * the shared MockMvc peer ({@code 127.0.0.1}). That strategy only worked because
     * the header was trusted unconditionally — the exact vulnerability EOP-26 closed.
     * With the fix in place the header is ignored (no trusted proxy is configured),
     * so the value returned here has no effect on throttle-bucket assignment. The
     * helper is kept only where removing it would require restructuring the call site;
     * it must not be read as providing any isolation guarantee.
     */
    private static String unusedAddressHint() {
        return "203.0.113." + SERIAL.incrementAndGet();
    }

    // ------------------------------------------------------------------ tests

    @Nested
    @DisplayName("creating a lobby")
    class CreatingALobby {

        @Test
        @DisplayName("seats the creator as the facilitator and says where the session lives")
        void shouldOpenALobby() throws Exception {
            final var created = mockMvc.perform(post(SESSIONS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(nameRequest("Ada")))
                    .andExpect(status().isCreated())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.playerToken").isNotEmpty())
                    .andExpect(jsonPath("$.playerId").isNotEmpty())
                    .andExpect(jsonPath("$.session.status").value("LOBBY"))
                    .andExpect(jsonPath("$.session.joinCode").isNotEmpty())
                    .andExpect(jsonPath("$.session.players.length()").value(1))
                    .andExpect(jsonPath("$.session.players[0].displayName").value("Ada"))
                    .andExpect(jsonPath("$.session.players[0].seatOrder").value(0))
                    .andExpect(jsonPath("$.session.players[0].role").value("FACILITATOR"))
                    .andExpect(jsonPath("$.session.players[0].connectionStatus").value("CONNECTED"))
                    .andExpect(jsonPath("$.session.createdAt").isNotEmpty())
                    .andExpect(jsonPath("$.session.updatedAt").isNotEmpty())
                    .andReturn();
            final var admission = admissionFrom(created.getResponse().getContentAsString());

            assertThat(created.getResponse().getHeader("Location")).isEqualTo(SESSIONS + "/" + admission.sessionId());
            assertThat(admission.joinCode()).hasSize(JoinCode.LENGTH);
            assertThat(JoinCode.parse(admission.joinCode())).isPresent();
        }

        @Test
        @DisplayName("trims the display name rather than seating a padded one")
        void shouldTrimTheDisplayName() throws Exception {
            mockMvc.perform(post(SESSIONS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(nameRequest("  Ada  ")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.session.players[0].displayName").value("Ada"));
        }

        @Test
        @DisplayName("refuses a blank display name before the domain is asked")
        void shouldRejectABlankDisplayName() throws Exception {
            mockMvc.perform(post(SESSIONS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(nameRequest("   ")))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.detail").isNotEmpty());
        }

        @Test
        @DisplayName("refuses a display name one character past the limit")
        void shouldRejectAnOverlongDisplayName() throws Exception {
            mockMvc.perform(post(SESSIONS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(nameRequest("A".repeat(41))))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.detail").isNotEmpty());
        }

        @Test
        @DisplayName("refuses a request with no body at all")
        void shouldRejectAMissingBody() throws Exception {
            mockMvc.perform(post(SESSIONS).contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("joining a lobby")
    class JoiningALobby {

        @Test
        @DisplayName("seats the joiner in the next seat, matching the code however it was typed")
        void shouldSeatAJoiner() throws Exception {
            final var facilitator = createSession("Ada");

            mockMvc.perform(post(SESSIONS + "/" + facilitator.joinCode().toLowerCase(java.util.Locale.ROOT)
                            + "/players")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(nameRequest("Grace")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.session.sessionId").value(facilitator.sessionId()))
                    .andExpect(jsonPath("$.session.joinCode").value(facilitator.joinCode()))
                    .andExpect(jsonPath("$.session.players.length()").value(2))
                    .andExpect(jsonPath("$.session.players[1].displayName").value("Grace"))
                    .andExpect(jsonPath("$.session.players[1].seatOrder").value(1))
                    .andExpect(jsonPath("$.session.players[1].role").value("PARTICIPANT"));
        }

        @Test
        @DisplayName("mints a credential of its own for the joiner")
        void shouldMintASeparateCredential() throws Exception {
            final var facilitator = createSession("Ada");

            final var joiner = joinSession(facilitator.joinCode(), "Grace");

            assertThat(joiner.playerToken()).isNotBlank().isNotEqualTo(facilitator.playerToken());
            assertThat(joiner.playerId()).isNotEqualTo(facilitator.playerId());
        }

        @Test
        @DisplayName("a joiner reads the same table the facilitator does, from the database")
        void shouldResyncFromStoredState() throws Exception {
            final var facilitator = createSession("Ada");
            final var joiner = joinSession(facilitator.joinCode(), "Grace");

            readState(facilitator.sessionId(), joiner.playerToken())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sessionId").value(facilitator.sessionId()))
                    .andExpect(jsonPath("$.joinCode").value(facilitator.joinCode()))
                    .andExpect(jsonPath("$.status").value("LOBBY"))
                    .andExpect(jsonPath("$.players.length()").value(2))
                    .andExpect(jsonPath("$.players[0].seatOrder").value(0))
                    .andExpect(jsonPath("$.players[1].seatOrder").value(1))
                    .andExpect(jsonPath("$.players[1].displayName").value("Grace"));
        }

        @Test
        @DisplayName("refuses a code no session holds")
        void shouldRefuseAnUnknownCode() throws Exception {
            attemptJoin(unheldCode(), unusedAddressHint())
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.title").value("No such session"))
                    .andExpect(jsonPath("$.detail").value("No session matches that join code."));
        }

        @Test
        @DisplayName("answers every unusable code with the same status, title and detail, so the endpoint is not an oracle")
        void shouldNotDiscloseWhichCodesAreReal() throws Exception {
            final var wellFormed = unheldCode();
            final var attempts = List.of(
                    attemptJoin(wellFormed, unusedAddressHint()),
                    attemptJoin("12345", unusedAddressHint()),
                    attemptJoin("1234IUVW", unusedAddressHint()),
                    // A code that was well formed before EOP-24 widened the length. It has to be
                    // refused exactly like the rest: JoinCode.parse rejects the length and never
                    // reaches the strict constructor, so this is a 404 and not the 500 it would be
                    // if a six-character value ever reached GameSessionJpaEntity.toDomain.
                    attemptJoin("7QK2FM", unusedAddressHint()));

            final var described = new ArrayList<String>();
            for (final var attempt : attempts) {
                final var response = attempt.andExpect(status().isNotFound()).andReturn().getResponse();
                final var json = JsonPath.parse(response.getContentAsString());
                described.add("%d|%s|%s".formatted(
                        response.getStatus(),
                        json.read("$.title", String.class),
                        json.read("$.detail", String.class)));
            }

            assertThat(described).containsOnly(described.get(0));
        }

        @Test
        @DisplayName("the only field that varies between refusals is the caller's own request path")
        void shouldOnlyEchoWhatTheCallerSent() throws Exception {
            final var attempted = unheldCode();

            final var body = attemptJoin(attempted, unusedAddressHint())
                    .andExpect(status().isNotFound())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            final var instance = JsonPath.parse(body).read("$.instance", String.class);
            assertThat(instance).isEqualTo(SESSIONS + "/" + attempted + "/players");
        }

        @Test
        @DisplayName("refuses the seventh player rather than leaking a seat invariant as a 400")
        void shouldRefuseTheSeventhPlayer() throws Exception {
            final var facilitator = createSession("Ada");
            for (int seat = 1; seat < 6; seat++) {
                joinSession(facilitator.joinCode(), "Player " + seat);
            }

            attemptJoin(facilitator.joinCode(), unusedAddressHint())
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.title").value("Session is full"))
                    .andExpect(jsonPath("$.detail").value("This session has no available seats. Try a different join code."));
        }

        @Test
        @DisplayName("refuses a joiner once play has begun")
        void shouldRefuseAJoinAfterPlayStarted() throws Exception {
            final var facilitator = createSession("Ada");
            joinSession(facilitator.joinCode(), "Grace");
            joinSession(facilitator.joinCode(), "Alan");
            startPlay(facilitator.sessionId(), facilitator.playerToken()).andExpect(status().isOk());

            attemptJoin(facilitator.joinCode(), unusedAddressHint())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.title").value("Session is not in the lobby"))
                    .andExpect(jsonPath("$.detail").value("This session is no longer in the lobby."));
        }

        @Test
        @DisplayName("refuses a blank joiner name")
        void shouldRejectABlankJoinerName() throws Exception {
            final var facilitator = createSession("Ada");

            mockMvc.perform(post(SESSIONS + "/" + facilitator.joinCode() + "/players")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(nameRequest("")))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.detail").isNotEmpty());
        }
    }

    @Nested
    @DisplayName("reading a session")
    class ReadingASession {

        @Test
        @DisplayName("refuses an unrecognised credential with a 403, not a 404")
        void shouldRefuseAnUnrecognisedCredential() throws Exception {
            final var facilitator = createSession("Ada");

            readState(facilitator.sessionId(), "not-a-credential-anyone-holds")
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.title").value("Player not recognised"))
                    .andExpect(jsonPath("$.detail").isNotEmpty());
        }

        @Test
        @DisplayName("a missing credential is refused exactly as a wrong one is")
        void shouldTreatAMissingCredentialAsAWrongOne() throws Exception {
            final var facilitator = createSession("Ada");

            final var absent = readState(facilitator.sessionId(), null).andExpect(status().isForbidden());
            final var wrong = readState(facilitator.sessionId(), "not-a-credential-anyone-holds")
                    .andExpect(status().isForbidden());

            assertThat(absent.andReturn().getResponse().getContentAsString())
                    .isEqualTo(wrong.andReturn().getResponse().getContentAsString());
        }

        @Test
        @DisplayName("an unknown session is a 404, whatever credential was offered")
        void shouldRefuseAnUnknownSession() throws Exception {
            readState(UUID.randomUUID().toString(), "not-a-credential-anyone-holds")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.title").value("Session not found"));
        }

        @Test
        @DisplayName("an identifier that is not a UUID is a 400, handled by the framework")
        void shouldRejectAMalformedIdentifier() throws Exception {
            readState("not-a-uuid", "not-a-credential-anyone-holds")
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.detail").isNotEmpty());
        }

        @Test
        @DisplayName("an expired session is a 403 with title 'Session expired', not a 404")
        void shouldRefuseAnExpiredSessionWithForbidden() throws Exception {
            // Arrange — create a session and then back-date its expires_at to the past
            final var facilitator = createSession("Ada");
            final UUID sessionId = UUID.fromString(facilitator.sessionId());
            jdbc.update(
                    "UPDATE game_session SET expires_at = TIMESTAMPADD(HOUR, -1, CURRENT_TIMESTAMP) WHERE id = ?",
                    sessionId);

            // Act + Assert — the expiry guard fires before the token check
            readState(facilitator.sessionId(), facilitator.playerToken())
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.title").value("Session expired"))
                    .andExpect(jsonPath("$.detail")
                            .value("The session has expired. Please start a new session."));
        }
    }

    @Nested
    @DisplayName("starting play")
    class StartingPlay {

        @Test
        @DisplayName("the facilitator closes a lobby of three")
        void shouldStartWithThreePlayers() throws Exception {
            final var facilitator = createSession("Ada");
            joinSession(facilitator.joinCode(), "Grace");
            joinSession(facilitator.joinCode(), "Alan");

            startPlay(facilitator.sessionId(), facilitator.playerToken())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sessionId").value(facilitator.sessionId()))
                    .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                    .andExpect(jsonPath("$.players.length()").value(3));

            readState(facilitator.sessionId(), facilitator.playerToken())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        }

        @Test
        @DisplayName("two players is not a game")
        void shouldRefuseToStartWithTwoPlayers() throws Exception {
            final var facilitator = createSession("Ada");
            joinSession(facilitator.joinCode(), "Grace");

            startPlay(facilitator.sessionId(), facilitator.playerToken())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.title").value("Not enough players to start"))
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("at least 3")));
        }

        @Test
        @DisplayName("a participant cannot start play, even at a table that is ready")
        void shouldRefuseAParticipantStarting() throws Exception {
            final var facilitator = createSession("Ada");
            final var participant = joinSession(facilitator.joinCode(), "Grace");
            joinSession(facilitator.joinCode(), "Alan");

            startPlay(facilitator.sessionId(), participant.playerToken())
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.title").value("Only the facilitator can start play"));
        }

        @Test
        @DisplayName("starting an already started session is refused")
        void shouldRefuseToStartTwice() throws Exception {
            final var facilitator = createSession("Ada");
            joinSession(facilitator.joinCode(), "Grace");
            joinSession(facilitator.joinCode(), "Alan");
            startPlay(facilitator.sessionId(), facilitator.playerToken()).andExpect(status().isOk());

            startPlay(facilitator.sessionId(), facilitator.playerToken())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.title").value("Session is not in the lobby"))
                    .andExpect(jsonPath("$.detail").value("This session is no longer in the lobby."));
        }

        @Test
        @DisplayName("a stranger cannot start play")
        void shouldRefuseAStrangerStarting() throws Exception {
            final var facilitator = createSession("Ada");

            startPlay(facilitator.sessionId(), "not-a-credential-anyone-holds")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.title").value("Player not recognised"));
        }
    }

    /**
     * Tests that exercise the join throttle.
     *
     * <p>Before EOP-26, each test in this class used a unique {@code X-Forwarded-For}
     * address so that failures would not accumulate against the shared MockMvc peer
     * ({@code 127.0.0.1}). That strategy only worked because the header was trusted
     * unconditionally — the exact vulnerability EOP-26 was filed to close. With the
     * fix in place the header is ignored (no trusted proxy is configured), so all
     * failures now accumulate against {@code 127.0.0.1}.
     *
     * <p>This class carries its own {@code @SpringBootTest} with a dedicated in-memory
     * database ({@code throttle-test}). That datasource URL is load-bearing for test
     * isolation: it makes this nested class's Spring context cache key unique, so Spring
     * allocates a separate {@link InMemoryJoinAttemptLimiter} singleton for it rather
     * than sharing the one from the outer class's context. Without that distinct URL the
     * two contexts would share a limiter and failures from one test would bleed into
     * another. Do not remove or rename the datasource property — it is not cosmetic.
     *
     * <p>{@code @DirtiesContext(BEFORE_EACH_TEST_METHOD)} gives each test method within
     * this class a fresh limiter so that the ten-failure window resets between methods.
     * It does not provide any after-class isolation; that is handled by the unique
     * datasource URL above, which ensures this class's context is never shared with
     * the outer class or with {@code SessionResilienceIntegrationTest}.
     *
     * <p>The per-address isolation property (one exhausted address must not throttle
     * another) is proved end-to-end in
     * {@code ForwardedForThrottleBypassIntegrationTest.PerAddressIsolationUnderTrustedProxy},
     * which saturates one address and then asserts a different address still succeeds.
     */
    @SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:throttle-test;DB_CLOSE_DELAY=-1")
    @AutoConfigureMockMvc
    @Nested
    @DisplayName("throttling guesses")
    @DirtiesContext(classMode = ClassMode.BEFORE_EACH_TEST_METHOD)
    class ThrottlingGuesses {

        @Autowired
        private MockMvc throttleMvc;

        @Test
        @DisplayName("tolerates ten wrong codes from one address and then refuses with a retry hint")
        void shouldThrottleAFloodOfGuesses() throws Exception {
            final var hint = unusedAddressHint();
            final var code = unheldCode();

            for (int attempt = 1; attempt <= TOLERATED_FAILURES; attempt++) {
                throttleMvc.perform(post(SESSIONS + "/" + code + "/players")
                                .header(FORWARDED_FOR, hint)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(nameRequest("Hopeful")))
                        .andExpect(status().isNotFound());
            }

            final var refused = throttleMvc.perform(post(SESSIONS + "/" + code + "/players")
                            .header(FORWARDED_FOR, hint)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(nameRequest("Hopeful")))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(429))
                    .andExpect(jsonPath("$.title").value("Too many join attempts"))
                    .andExpect(jsonPath("$.detail").isNotEmpty())
                    .andReturn()
                    .getResponse();

            // The limiter reads the wall clock, and the ten failures above took real
            // time to make, so the hint is a whole number of seconds short of the
            // window rather than exactly the window. Pinning 60 would make the test
            // fail on a slow machine; what matters is that the hint is present, is a
            // whole number, and does not exceed the window.
            assertThat(refused.getHeader("Retry-After"))
                    .isNotNull()
                    .satisfies(h -> assertThat(Long.parseLong(h)).isBetween(1L, 60L));
        }

        @Test
        @DisplayName("a throttled address can still be seated at a table it knows, because success is not charged")
        void shouldNotChargeSuccessfulJoins() throws Exception {
            final var sessionBody = throttleMvc.perform(post(SESSIONS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(nameRequest("Ada")))
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            final var facilitator = admissionFrom(sessionBody);
            final var hint = unusedAddressHint();

            for (int attempt = 1; attempt <= TOLERATED_FAILURES; attempt++) {
                throttleMvc.perform(post(SESSIONS + "/" + facilitator.joinCode() + "/players")
                                .header(FORWARDED_FOR, hint)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(nameRequest("Guest " + attempt)))
                        .andExpect(attempt <= 5 ? status().isOk() : status().isConflict());
            }

            throttleMvc.perform(post(SESSIONS + "/" + unheldCode() + "/players")
                            .header(FORWARDED_FOR, hint)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(nameRequest("Hopeful")))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("no response ever carries a stored credential digest")
    void shouldNeverDiscloseADigest() throws Exception {
        final var facilitator = createSession("Ada");
        final var participant = joinSession(facilitator.joinCode(), "Grace");
        final var third = joinSession(facilitator.joinCode(), "Alan");

        final var bodies = new ArrayList<String>();
        bodies.add(facilitator.body());
        bodies.add(participant.body());
        bodies.add(third.body());
        bodies.add(readState(facilitator.sessionId(), participant.playerToken())
                .andReturn().getResponse().getContentAsString());
        bodies.add(startPlay(facilitator.sessionId(), facilitator.playerToken())
                .andReturn().getResponse().getContentAsString());

        final var digests = List.of(
                IdentityTokenHash.of(facilitator.playerToken()).value(),
                IdentityTokenHash.of(participant.playerToken()).value(),
                IdentityTokenHash.of(third.playerToken()).value());

        assertThat(bodies).allSatisfy(body -> {
            assertThat(body).doesNotMatch(ANY_DIGEST);
            assertThat(digests).allSatisfy(digest -> assertThat(body).doesNotContain(digest));
        });
    }

    @Test
    @DisplayName("a player only ever receives its own credential, never another's")
    void shouldNotDiscloseAnotherPlayersCredential() throws Exception {
        final var facilitator = createSession("Ada");
        final var participant = joinSession(facilitator.joinCode(), "Grace");

        assertThat(participant.body()).doesNotContain(facilitator.playerToken());
        assertThat(readState(facilitator.sessionId(), facilitator.playerToken())
                .andReturn().getResponse().getContentAsString())
                .doesNotContain(facilitator.playerToken())
                .doesNotContain(participant.playerToken());
    }

    /**
     * Verifies that {@code POST /api/v1/sessions} returns HTTP 429 with a
     * {@code Retry-After} header once the per-address creation limit is breached.
     *
     * <p>Uses a dedicated Spring context with {@code session-creation-limit=1} so
     * the limit is crossed after a single creation without affecting the outer
     * class's shared context (which runs with {@code Integer.MAX_VALUE}).
     *
     * <p>{@code @DirtiesContext(BEFORE_EACH_TEST_METHOD)} gives each test method a
     * fresh limiter so the single-creation window resets between methods.
     */
    @SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:creation-throttle-test;DB_CLOSE_DELAY=-1",
        "eop.web.session-creation-limit=1"
    })
    @AutoConfigureMockMvc
    @Nested
    @DisplayName("throttling session creation")
    @DirtiesContext(classMode = ClassMode.BEFORE_EACH_TEST_METHOD)
    class ThrottlingCreation {

        @Autowired
        private MockMvc throttleMvc;

        @Test
        @DisplayName("second creation from the same address is refused with 429 and Retry-After")
        void shouldThrottleCreationAfterLimit() throws Exception {
            // First creation succeeds.
            throttleMvc.perform(post(SESSIONS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(nameRequest("Alice")))
                    .andExpect(status().isCreated());

            // Second creation from the same address (127.0.0.1 in MockMvc) is refused.
            final var refused = throttleMvc.perform(post(SESSIONS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(nameRequest("Bob")))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(429))
                    .andExpect(jsonPath("$.title").value("Too many requests"))
                    .andExpect(jsonPath("$.detail").isNotEmpty())
                    .andReturn()
                    .getResponse();

            assertThat(refused.getHeader("Retry-After"))
                    .isNotNull()
                    .satisfies(h -> assertThat(Long.parseLong(h)).isBetween(1L, 60L));
        }

        @Test
        @DisplayName("a use-case failure refunds the slot so the next creation is not refused")
        void shouldRefundSlotOnUseCaseFailure() throws Exception {
            // A display name containing a control character passes @Valid (not blank, within
            // length) but is rejected by DisplayName.of() inside the controller body — after
            // recordCreation has already reserved the slot. The controller's catch block must
            // call refundCreation so the slot is returned and the next valid creation succeeds.
            //
            // The JSON body uses the JSON escape sequence \u0001 (written as \\u0001 in the Java
            // string literal) so that Jackson can parse the body and decode the character before
            // passing it to DisplayName.of(). A raw U+0001 byte in the JSON body would be
            // invalid JSON and would be rejected by Jackson before the controller is entered,
            // which would make this test vacuous.
            final String bodyWithControlChar = "{\"displayName\":\"Alice\\u0001\"}";

            // First creation: the use case fails (control character in name) → slot refunded.
            throttleMvc.perform(post(SESSIONS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyWithControlChar))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Invalid request"));

            // Second creation: slot was refunded, so this succeeds with 201.
            throttleMvc.perform(post(SESSIONS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(nameRequest("Bob")))
                    .andExpect(status().isCreated());
        }
    }
}
