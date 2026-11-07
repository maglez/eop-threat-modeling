package org.maglez.eop.inbound.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.domain.InvalidInputException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Proves that Spring <em>selects</em> the right handler, which the unit tests in
 * {@code GlobalExceptionHandlerTest} cannot do.
 *
 * <p>Those tests call each handler method directly, as a pure function. That pins what a handler
 * returns once chosen, but it cannot pin which handler is chosen — and choosing is the whole of
 * EOP-28. Before the fix an {@code @ExceptionHandler(IllegalArgumentException.class)} was more
 * specific than the {@code Exception} catch-all, so a library-thrown rejection was answered with a
 * 400 carrying the library's own text and never reached the only error-level log. A test that calls
 * the catch-all directly passes just as happily on that broken code, because the catch-all was
 * never the faulty part.</p>
 *
 * <p>So this test throws from a real request and lets the framework route it. Both directions are
 * asserted, because only the contrast is evidence: a bare {@code IllegalArgumentException} must now
 * reach the catch-all for a generic 500, while the domain's own rejection type must still reach the
 * 400. Assert only the first and a handler mapped to nothing would pass; assert only the second and
 * the original defect would pass.</p>
 *
 * <p>The endpoints live on a controller published only for this test. Nothing a caller can reach in
 * production throws a bare {@code IllegalArgumentException} any more — that is the fix — so the
 * fault has to be injected to be observed at all.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("dispatching an IllegalArgumentException through the real handler chain")
class IllegalArgumentDispatchIntegrationTest {

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String LIBRARY_MESSAGE = "jdbc:postgresql://10.20.1.7:5432/eop refused";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("a library-thrown one is a generic 500, not a 400 quoting the library")
    void shouldRouteABareIllegalArgumentToTheCatchAll() throws Exception {
        final String body = mockMvc.perform(get(ThrowingController.BARE))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.title").value("Internal server error"))
                .andExpect(jsonPath("$.detail").value("The request could not be completed."))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .as("no part of the library's message may survive anywhere in the response")
                .doesNotContain("postgresql")
                .doesNotContain("10.20.1.7");
    }

    @Test
    @DisplayName("the domain's own rejection is still a 400 carrying the guard clause's message")
    void shouldRouteADomainRejectionToTheBadRequestHandler() throws Exception {
        mockMvc.perform(get(ThrowingController.DOMAIN))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.detail").value("size must be at most 100, was 500"));
    }

    /**
     * Publishes the throwing controller for this test only.
     */
    @TestConfiguration
    static class DispatchConfiguration {

        /**
         * Registers the endpoints that raise each kind of exception.
         *
         * @return the test-only controller
         */
        @Bean
        ThrowingController throwingController() {
            return new ThrowingController();
        }
    }

    /**
     * Raises, from inside a genuine request, the two exception types whose routing is in question.
     *
     * <p>Paths sit outside {@code /api} so they cannot be mistaken for part of the published
     * contract, and so they never collide with a real mapping.</p>
     */
    @RestController
    static class ThrowingController {

        private static final String BARE = "/test-only/bare-illegal-argument";
        private static final String DOMAIN = "/test-only/domain-invalid-input";

        /**
         * Stands in for a rejection thrown by the JDK, Jackson, Hibernate or Spring, whose message is
         * written for a maintainer and must never reach a caller.
         */
        @GetMapping(BARE)
        void throwBareIllegalArgument() {
            throw new IllegalArgumentException(LIBRARY_MESSAGE);
        }

        /**
         * Stands in for one of our own guard clauses, whose message is written for a caller.
         */
        @GetMapping(DOMAIN)
        void throwDomainRejection() {
            throw new InvalidInputException("size must be at most 100, was 500");
        }
    }
}
