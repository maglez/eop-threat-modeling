package org.maglez.eop.inbound.web;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies that exceeding the read rate limit produces an RFC 9457 problem
 * detail rather than a bare status or Spring's default error body (EOP-88).
 *
 * <p>Two things about the setup are deliberate. The limit is lowered for this
 * class only, through {@code @SpringBootTest(properties = …)}, so the rest of the
 * suite keeps the very high limit set in
 * {@code src/test/resources/application.properties} and is never refused because
 * of test ordering. And isolation comes from {@link DirtiesContext}: a fresh
 * context per test method means a fresh counter, so one test cannot leave the
 * next one throttled.
 *
 * <p>What isolation must <em>not</em> come from is a different
 * {@code X-Forwarded-For} per test. Sending one would be forging the limiter key,
 * which is the vulnerability EOP-26 fixed rather than a test technique
 * (ADR-021). Every request here therefore arrives from the same address, exactly
 * as a real caller's would.
 */
@SpringBootTest(properties = "eop.web.read-rate-limit.limit=" + ReadRateLimitIntegrationTest.LIMIT)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@DisplayName("read rate limiting")
class ReadRateLimitIntegrationTest {

    /**
     * The limit applied to this class's context. Small enough to exhaust in a few
     * requests, and far below the shipped default of 300, which is what makes the
     * limit demonstrably configurable.
     */
    static final int LIMIT = 3;

    private static final String CARDS = "/api/v1/cards";
    private static final String PROBLEM_JSON = "application/problem+json";

    /**
     * A whole number of seconds, at least one. {@code docs/api/openapi.yml} documents
     * {@code Retry-After} as an integer with {@code minimum: 1}, and the counter floors
     * the value at one second because {@code Retry-After: 0} reads as "no wait
     * required" and would invite an immediate retry.
     */
    private static final String POSITIVE_INTEGER = "^[1-9][0-9]*$";

    @Autowired
    private MockMvc mockMvc;

    private void exhaustTheLimit() throws Exception {
        for (int read = 0; read < LIMIT; read++) {
            mockMvc.perform(get(CARDS)).andExpect(status().isOk());
        }
    }

    private String sessionRoute(final String suffix) {
        return "/api/v1/sessions/" + UUID.randomUUID() + suffix;
    }

    @Test
    @DisplayName("the read after the limit is a 429 problem detail carrying Retry-After")
    void shouldReturnProblemDetailWhenTheReadLimitIsExceeded() throws Exception {
        exhaustTheLimit();

        mockMvc.perform(get(CARDS))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(header().string("Retry-After", matchesPattern(POSITIVE_INTEGER)))
                // Deliberately no assertion on $.type. RFC 9457 section 3.1 makes that member
                // optional with a default of about:blank, and Spring omits it while it holds
                // that default -- so it is absent from every problem body the application
                // sends. The openapi schema listed it as required until EOP-88 corrected it.
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.title").value("Too many requests"))
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    @Test
    @DisplayName("GET /leaderboard is covered: it is refused before the handler decides anything")
    void shouldCoverTheLeaderboardRoute() throws Exception {
        exhaustTheLimit();

        // A random session id with no player token would otherwise be a 403 or a 404.
        // Receiving 429 instead proves the limiter runs ahead of the handler on the
        // route EOP-88 names, without needing a completed game to reach it.
        mockMvc.perform(get(sessionRoute("/leaderboard")))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(429));
    }

    @Test
    @DisplayName("the pattern covers reads the tests never name individually")
    void shouldCoverEveryReadUnderTheApiPrefix() throws Exception {
        exhaustTheLimit();

        // Registration is by the /api/v1/** pattern rather than a list of routes, and
        // that is the property worth pinning: these four are refused for the same
        // reason a route added tomorrow will be, not because anyone enumerated them.
        for (final var route : new String[] {"", "/hand", "/score", "/tricks/current"}) {
            mockMvc.perform(get(sessionRoute(route)))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.status").value(429));
        }

        mockMvc.perform(get(CARDS + "/SPOOFING-1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429));
    }

    @Test
    @DisplayName("HEAD is counted, because it reaches the same handler at the same cost")
    void shouldCountHeadRequests() throws Exception {
        for (int read = 0; read < LIMIT; read++) {
            mockMvc.perform(head(CARDS)).andExpect(status().isOk());
        }

        // The allowance is shared, so a GET after the limit is spent on HEAD is refused.
        mockMvc.perform(get(CARDS))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429));
    }

    @Test
    @DisplayName("the event stream is excluded, so subscribing does not consume the read allowance")
    void shouldNotCountTheEventStream() throws Exception {
        // The status is pinned rather than merely asserted not to be 429. An unknown session
        // is a 404 here rather than the 403 the leaderboard gives, because this route checks
        // the session exists before it resolves the player -- and pinning it means a status
        // that changed for some other reason cannot be mistaken for evidence of exclusion.
        for (int attempt = 0; attempt < LIMIT + 2; attempt++) {
            mockMvc.perform(get(sessionRoute("/events")))
                    .andExpect(status().isNotFound());
        }

        mockMvc.perform(get(CARDS)).andExpect(status().isOk());
    }
}
