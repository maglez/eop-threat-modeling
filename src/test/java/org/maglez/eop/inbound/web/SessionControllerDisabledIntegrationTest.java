package org.maglez.eop.inbound.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.outbound.scheduling.ExpiredSessionSweepScheduler;
import org.maglez.eop.application.port.in.SweepExpiredSessionsPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import org.assertj.core.api.Assertions;

/**
 * Verifies that the feature flag actually withholds the feature.
 *
 * <p>The suite runs with {@code eop.features.session-lifecycle=true} because a suite
 * that ran with the feature off would be testing the absence of it. This one class
 * overrides the property back to false, which costs a second Spring context, and
 * that cost is the price of knowing the flag works — a flag nobody tests in its off
 * position is a flag that will not be trusted in an incident.
 *
 * <p>The assertion is deliberately about the bean as well as the routes. Asserting
 * only on 404s would still pass if the controller existed but every handler happened
 * to be mapped elsewhere; asserting the bean is absent pins the mechanism, which is
 * that {@code @ConditionalOnProperty} withholds the code rather than the code
 * branching at request time (ADR-013).
 */
@SpringBootTest(properties = "eop.features.session-lifecycle=false")
@AutoConfigureMockMvc
@DisplayName("Session endpoints with the feature flag off")
class SessionControllerDisabledIntegrationTest {

    private static final String SESSIONS = "/api/v1/sessions";
    private static final String SOME_SESSION = UUID.randomUUID().toString();
    private static final String SOME_CODE = "ABC23456";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("does not create the controller at all")
    void shouldNotRegisterTheController() {
        Assertions.assertThat(context.getBeanNamesForType(SessionController.class)).isEmpty();
    }

    @Test
    @DisplayName("does not create the stream controller either")
    void shouldNotRegisterTheStreamController() {
        Assertions.assertThat(context.getBeanNamesForType(SessionStreamController.class)).isEmpty();
    }

    @Test
    @DisplayName("does not create the sweep scheduler when the flag is off")
    void shouldNotRegisterTheSweepScheduler() {
        Assertions.assertThat(context.getBeanNamesForType(ExpiredSessionSweepScheduler.class)).isEmpty();
        Assertions.assertThat(context.getBeanNamesForType(SweepExpiredSessionsPort.class)).isEmpty();
    }

    @Test
    @DisplayName("creating a session is not a route")
    void shouldNotExposeCreation() throws Exception {
        mockMvc.perform(post(SESSIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Ada\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("joining a session is not a route")
    void shouldNotExposeJoining() throws Exception {
        mockMvc.perform(post(SESSIONS + "/" + SOME_CODE + "/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Grace\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("reading a session is not a route")
    void shouldNotExposeState() throws Exception {
        mockMvc.perform(get(SESSIONS + "/" + SOME_SESSION))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("starting play is not a route")
    void shouldNotExposeStarting() throws Exception {
        mockMvc.perform(post(SESSIONS + "/" + SOME_SESSION + "/start"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the event stream is not a route")
    void shouldNotExposeTheEventStream() throws Exception {
        mockMvc.perform(get(SESSIONS + "/" + SOME_SESSION + "/events"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the card endpoints are unaffected: the flag withholds one feature, not the application")
    void shouldLeaveTheRestOfTheApiAlone() throws Exception {
        mockMvc.perform(get("/api/v1/cards"))
                .andExpect(status().isOk());
    }
}
