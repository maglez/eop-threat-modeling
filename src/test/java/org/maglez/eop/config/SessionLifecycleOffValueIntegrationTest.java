package org.maglez.eop.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.inbound.web.SessionController;
import org.maglez.eop.application.port.in.CreateSessionPort;
import org.maglez.eop.application.port.in.GetSessionStatePort;
import org.maglez.eop.application.port.in.JoinSessionPort;
import org.maglez.eop.application.port.in.PlayCardPort;
import org.maglez.eop.application.port.in.ResolvePlayerPort;
import org.maglez.eop.application.port.in.StartSessionPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Asserts that {@code eop.features.session-lifecycle=off} withholds the lobby rather than enabling it.
 *
 * <p>This class exists because until EOP-48 it would have failed. The condition on
 * {@link SessionController} named the property without naming the value it wanted, and
 * {@link org.springframework.boot.autoconfigure.condition.ConditionalOnProperty} in that form matches
 * any value present that is not literally {@code false}. So {@code off} — along with {@code no},
 * {@code 0} and {@code disabled} — switched the five session routes <em>on</em>. The flag was
 * fail-open, against the plain requirement in {@code .opencode/rules/security.md} that a control
 * fails securely, and ADR-013 now requires {@code havingValue = "true"} on every flag in the
 * repository so that a flag's off position is every value except one rather than one value except
 * everything.
 *
 * <p>{@code off} is the spelling worth pinning down, of the several that used to fail open. It is the
 * word an operator reaches for when they want a kill switch, and in YAML 1.1 it is a boolean false,
 * so a hand-edited {@code application.yml} reading {@code session-lifecycle: off} looks correct to
 * every reader and to the YAML specification while enabling the feature. A regression here would be
 * invisible at review.
 *
 * <p>The sibling {@code SessionControllerDisabledIntegrationTest} covers the same flag spelled
 * {@code false}, and it is left exactly as it was. It passed before this fix and after it, which is
 * the point: the literal spelling was never the broken one, so a test using it could not have caught
 * this, and only a second spelling proves the difference. The two classes cost two Spring contexts
 * because the property override is part of the context cache key, and that is the price of testing
 * the flag rather than trusting it.
 *
 * <p>Bean absence is asserted as well as the 404s, and the pair is not redundant. A 404 alone would
 * also be produced by a controller that existed with its handlers mapped elsewhere, which is a design
 * ADR-013 forbids and — more to the point here — is close to what the fail-open version actually did:
 * the routes were live, so a status-only test would have been the one thing that could not tell the
 * bug from the fix. The four use-case beans are checked for the same reason at one layer down. They
 * were registered unconditionally before EOP-48, so the beans that open and mutate a session existed
 * whatever the flag said and only the route was ever withheld; gating them is what makes the off
 * position a property of the context.
 *
 * <p>That reasoning was measured rather than assumed. Running this class against the pre-EOP-48
 * production code failed six of its twelve tests: the five bean assertions, and exactly one of the
 * five route assertions — {@code POST /api/v1/sessions} answered <strong>201 Created</strong> with the
 * flag set to {@code off}, persisting a session. The other four routes returned 404 anyway, because a
 * random session identifier and an unissued join code are absent whether or not the controller is,
 * so those four statuses are identical for the bug and for the fix. A status-only test would
 * therefore have caught this on one route out of five and only because that route creates something;
 * had the lobby been read-only it would have caught nothing at all.
 *
 * <p>The last two tests are the counterweight, and the first of them is load-bearing rather than
 * decorative. {@link ResolvePlayerPort} is ungated on purpose: it writes nothing, and the
 * trick-play use cases depend on it too. This context is precisely the combination that would expose
 * a mistake there — the lobby flag off while trick play is on, as the suite leaves it — so if
 * resolve-player were ever gated on the lobby flag, trick play would become unsatisfiable and this
 * context would fail to start rather than fail an assertion.
 */
@SpringBootTest(properties = "eop.features.session-lifecycle=off")
@AutoConfigureMockMvc
@DisplayName("Session lifecycle with the feature flag set to off")
class SessionLifecycleOffValueIntegrationTest {

    /** The lobby collection route, from which the other four paths are built. */
    private static final String SESSIONS = "/api/v1/sessions";

    /** A session identifier that need not exist: the routes are gone before any lookup happens. */
    private static final String SOME_SESSION = UUID.randomUUID().toString();

    /** A well-formed join code, so a 404 cannot be mistaken for a rejected code. */
    private static final String SOME_CODE = "ABC23456";

    /** The smallest body the create and join routes accept, so the 404 is not a validation failure. */
    private static final String A_NAME = "{\"displayName\":\"Ada\"}";

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("does not create the controller, so the routes are absent rather than disabled")
    void shouldNotRegisterTheSessionController() {
        Assertions.assertThat(context.getBeanNamesForType(SessionController.class)).isEmpty();
    }

    @Test
    @DisplayName("does not create the create-session use case at all")
    void shouldNotRegisterTheCreateSessionPort() {
        Assertions.assertThat(context.getBeanNamesForType(CreateSessionPort.class)).isEmpty();
    }

    @Test
    @DisplayName("does not create the join-session use case at all")
    void shouldNotRegisterTheJoinSessionPort() {
        Assertions.assertThat(context.getBeanNamesForType(JoinSessionPort.class)).isEmpty();
    }

    @Test
    @DisplayName("does not create the start-session use case at all")
    void shouldNotRegisterTheStartSessionPort() {
        Assertions.assertThat(context.getBeanNamesForType(StartSessionPort.class)).isEmpty();
    }

    /**
     * Asserts the read of a session's own state is withheld along with the three writers.
     *
     * <p>It only reports what a caller's own credential already entitles them to see, so nothing about
     * it is dangerous. It is gated all the same, because with the lobby off no session can have been
     * created and the route could never answer anything but a refusal.
     */
    @Test
    @DisplayName("does not create the session-state use case at all")
    void shouldNotRegisterTheGetSessionStatePort() {
        Assertions.assertThat(context.getBeanNamesForType(GetSessionStatePort.class)).isEmpty();
    }

    /** Asserts the create route is not served, body and all. */
    @Test
    @DisplayName("withholds the create-session route")
    void shouldNotServeTheCreateRoute() throws Exception {
        mockMvc.perform(post(SESSIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(A_NAME))
                .andExpect(status().isNotFound());
    }

    /** Asserts the join route is not served, well-formed code and body and all. */
    @Test
    @DisplayName("withholds the join-session route")
    void shouldNotServeTheJoinRoute() throws Exception {
        mockMvc.perform(post(SESSIONS + "/" + SOME_CODE + "/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(A_NAME))
                .andExpect(status().isNotFound());
    }

    /** Asserts the session-state route is not served. */
    @Test
    @DisplayName("withholds the session-state route")
    void shouldNotServeTheStateRoute() throws Exception {
        mockMvc.perform(get(SESSIONS + "/" + SOME_SESSION))
                .andExpect(status().isNotFound());
    }

    /** Asserts the start route is not served. */
    @Test
    @DisplayName("withholds the start-session route")
    void shouldNotServeTheStartRoute() throws Exception {
        mockMvc.perform(post(SESSIONS + "/" + SOME_SESSION + "/start"))
                .andExpect(status().isNotFound());
    }

    /** Asserts the event stream is not served. */
    @Test
    @DisplayName("withholds the session event stream")
    void shouldNotServeTheEventStream() throws Exception {
        mockMvc.perform(get(SESSIONS + "/" + SOME_SESSION + "/events"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("leaves the shared resolve-player use case and trick play alone")
    void shouldLeaveTheSharedDependencyAndTrickPlayAlone() {
        Assertions.assertThat(context.getBeanNamesForType(ResolvePlayerPort.class))
                .as("resolve-player is ungated on purpose: it writes nothing and trick play depends on it")
                .isNotEmpty();

        Assertions.assertThat(context.getBeanNamesForType(PlayCardPort.class))
                .as("trick play is behind its own flag, which this test does not touch")
                .isNotEmpty();
    }

    /** Asserts the flag withholds one feature rather than the application. */
    @Test
    @DisplayName("the rest of the application is unaffected: the card catalogue is still served")
    void shouldStillServeTheCardRoutes() throws Exception {
        mockMvc.perform(get("/api/v1/cards"))
                .andExpect(status().isOk());
    }
}
