package org.maglez.eop.inbound.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;


/**
 * Integration tests for {@link CorrelationIdFilter} wired into the full Spring context (EOP-117).
 *
 * <p>These tests verify that the filter is correctly registered by
 * {@code ObservabilityConfiguration} and that it participates in the real request pipeline:
 * <ul>
 *   <li>A caller-supplied {@code X-Correlation-Id} is echoed back in the response.</li>
 *   <li>When no header is supplied the response still carries a generated UUID.</li>
 *   <li>The MDC is cleared after the request completes (no thread-local leak).</li>
 * </ul>
 *
 * <p>The {@code /health} endpoint is used as the probe because it is always present,
 * requires no authentication and exercises the full filter chain.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("CorrelationIdFilter integration")
class CorrelationIdIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Nested
    @DisplayName("when the request carries an X-Correlation-Id header")
    class WithSuppliedHeader {

        @Test
        @DisplayName("echoes the supplied correlation ID in the response header")
        void echoesSuppliedCorrelationId() throws Exception {
            final String suppliedId = "integration-test-id-xyz";

            mockMvc.perform(get("/health")
                            .header(CorrelationIdFilter.HEADER_NAME, suppliedId))
                    .andExpect(status().isOk())
                    .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, suppliedId));
        }
    }

    @Nested
    @DisplayName("when the request has no X-Correlation-Id header")
    class WithoutHeader {

        @Test
        @DisplayName("generates a UUID and returns it in the response header")
        void generatesAndReturnsCorrelationId() throws Exception {
            final MvcResult result = mockMvc.perform(get("/health"))
                    .andExpect(status().isOk())
                    .andExpect(header().exists(CorrelationIdFilter.HEADER_NAME))
                    .andReturn();

            final String returnedId = result.getResponse()
                    .getHeader(CorrelationIdFilter.HEADER_NAME);

            assertThat(returnedId)
                    .as("generated correlation ID in response header")
                    .isNotNull()
                    .isNotBlank()
                    .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        }
    }

    @Nested
    @DisplayName("MDC lifecycle")
    class MdcLifecycle {

        @Test
        @DisplayName("MDC is cleared after the request completes (no thread-local leak)")
        void mdcIsClearedAfterRequest() throws Exception {
            // Perform a request that will populate the MDC during execution.
            mockMvc.perform(get("/health")
                            .header(CorrelationIdFilter.HEADER_NAME, "leak-check-id"))
                    .andExpect(status().isOk());

            // After the request the MDC entry must be absent on this thread.
            // MockMvc dispatches synchronously on the calling thread, so the
            // finally-block in CorrelationIdFilter will have run before we reach here.
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY))
                    .as("MDC correlationId after request completes")
                    .isNull();
        }
    }
}
