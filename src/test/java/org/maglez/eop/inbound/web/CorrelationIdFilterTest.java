package org.maglez.eop.inbound.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for {@link CorrelationIdFilter} (EOP-117).
 *
 * <p>No Spring context is loaded. The filter is constructed directly and exercised
 * with Spring's {@link MockHttpServletRequest} / {@link MockHttpServletResponse} so
 * that every assertion runs in sub-second time.
 *
 * <p>The key behaviours under test are:
 * <ul>
 *   <li>A caller-supplied {@code X-Correlation-Id} header is honoured and placed in the MDC.</li>
 *   <li>When the header is absent a UUID is generated and placed in the MDC.</li>
 *   <li>The correlation ID is echoed back in the response header.</li>
 *   <li>The MDC entry is cleared after the filter chain completes, even if the chain throws.</li>
 * </ul>
 */
@DisplayName("CorrelationIdFilter")
class CorrelationIdFilterTest {

    private CorrelationIdFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new CorrelationIdFilter();
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        // Ensure the MDC is clean before each test regardless of test ordering.
        MDC.remove(CorrelationIdFilter.MDC_KEY);
    }

    @AfterEach
    void tearDown() {
        // Belt-and-braces: clear the MDC after each test so a failing assertion
        // cannot pollute the next test via thread-local state.
        MDC.remove(CorrelationIdFilter.MDC_KEY);
    }

    @Nested
    @DisplayName("when the request carries an X-Correlation-Id header")
    class WithSuppliedHeader {

        @Test
        @DisplayName("uses the supplied value as the MDC correlation ID")
        void usesSuppledHeaderValueInMdc() throws ServletException, IOException {
            // Arrange
            final String suppliedId = "test-correlation-id-abc123";
            request.addHeader(CorrelationIdFilter.HEADER_NAME, suppliedId);

            // Act — capture the MDC value from inside the chain
            final String[] mdcDuringChain = new String[1];
            final FilterChain capturingChain = (req, res) ->
                    mdcDuringChain[0] = MDC.get(CorrelationIdFilter.MDC_KEY);
            filter.doFilter(request, response, capturingChain);

            // Assert
            assertThat(mdcDuringChain[0])
                    .as("MDC correlationId during chain execution")
                    .isEqualTo(suppliedId);
        }

        @Test
        @DisplayName("echoes the supplied value back in the response header")
        void echoesSuppliedHeaderInResponse() throws ServletException, IOException {
            // Arrange
            final String suppliedId = "echo-me-back";
            request.addHeader(CorrelationIdFilter.HEADER_NAME, suppliedId);

            // Act
            filter.doFilter(request, response, new MockFilterChain());

            // Assert
            assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME))
                    .as("X-Correlation-Id response header")
                    .isEqualTo(suppliedId);
        }
    }

    @Nested
    @DisplayName("when the request has no X-Correlation-Id header")
    class WithoutHeader {

        @Test
        @DisplayName("generates a UUID and places it in the MDC")
        void generatesUuidAndPlacesInMdc() throws ServletException, IOException {
            // Arrange — no header set on request

            // Act
            final String[] mdcDuringChain = new String[1];
            final FilterChain capturingChain = (req, res) ->
                    mdcDuringChain[0] = MDC.get(CorrelationIdFilter.MDC_KEY);
            filter.doFilter(request, response, capturingChain);

            // Assert
            assertThat(mdcDuringChain[0])
                    .as("MDC correlationId during chain execution")
                    .isNotNull()
                    .isNotBlank()
                    // UUID format: 8-4-4-4-12 hex digits
                    .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        }

        @Test
        @DisplayName("echoes the generated UUID back in the response header")
        void echoesGeneratedUuidInResponse() throws ServletException, IOException {
            // Arrange — no header set

            // Act
            filter.doFilter(request, response, new MockFilterChain());

            // Assert
            assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME))
                    .as("X-Correlation-Id response header")
                    .isNotNull()
                    .isNotBlank()
                    .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        }

        @Test
        @DisplayName("generates a different UUID for each request")
        void generatesDifferentUuidPerRequest() throws ServletException, IOException {
            // Arrange
            final MockHttpServletRequest request2 = new MockHttpServletRequest();
            final MockHttpServletResponse response2 = new MockHttpServletResponse();

            // Act
            filter.doFilter(request, response, new MockFilterChain());
            filter.doFilter(request2, response2, new MockFilterChain());

            // Assert
            assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME))
                    .as("first request correlation ID")
                    .isNotEqualTo(response2.getHeader(CorrelationIdFilter.HEADER_NAME));
        }
    }

    @Nested
    @DisplayName("MDC lifecycle")
    class MdcLifecycle {

        @Test
        @DisplayName("clears the MDC entry after the filter chain completes normally")
        void clearsMdcAfterNormalCompletion() throws ServletException, IOException {
            // Arrange
            request.addHeader(CorrelationIdFilter.HEADER_NAME, "will-be-cleared");

            // Act
            filter.doFilter(request, response, new MockFilterChain());

            // Assert — MDC must be empty after the filter returns
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY))
                    .as("MDC correlationId after filter completes")
                    .isNull();
        }

        @Test
        @DisplayName("clears the MDC entry even when the filter chain throws")
        void clearsMdcAfterChainThrows() {
            // Arrange
            request.addHeader(CorrelationIdFilter.HEADER_NAME, "throw-test");
            final FilterChain throwingChain = (req, res) -> {
                throw new ServletException("simulated downstream failure");
            };

            // Act
            try {
                filter.doFilter(request, response, throwingChain);
            } catch (ServletException | IOException ignored) {
                // expected — we only care about the MDC state after the throw
            }

            // Assert — MDC must be cleared even after an exception
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY))
                    .as("MDC correlationId after chain throws")
                    .isNull();
        }

        @Test
        @DisplayName("MDC is empty before the filter runs (baseline check)")
        void mdcIsEmptyBeforeFilterRuns() {
            // This test verifies the test harness itself: setUp() must clear the MDC.
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY))
                    .as("MDC correlationId before filter runs")
                    .isNull();
        }
    }
}
