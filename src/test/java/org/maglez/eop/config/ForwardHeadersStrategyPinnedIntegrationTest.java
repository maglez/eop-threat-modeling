package org.maglez.eop.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.web.server.autoconfigure.ServerProperties.ForwardHeadersStrategy;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Pins {@code server.forward-headers-strategy=none} and proves it is load-bearing (EOP-26).
 *
 * <p>With {@code NATIVE}, Tomcat's {@code RemoteIpValve} default {@code internalProxies}
 * includes {@code 172.16.0.0/12} — which contains this project's own {@code 172.28.0.0/24}
 * Compose subnet — and {@code 127.0.0.0/8}, which covers local {@code ./mvnw spring-boot:run}.
 * With {@code FRAMEWORK}, Spring's {@code ForwardedHeaderFilter} overrides
 * {@code getRemoteAddr()} with no trust check at all. Either alternative would launder
 * {@code X-Forwarded-For} into the one input {@link org.maglez.eop.inbound.web.ClientAddressResolver}
 * trusts, silently re-opening EOP-26 without any test failing.
 *
 * <p>Two assertions are made here, and the reasoning for each is worth stating:
 *
 * <p><strong>Property assertion</strong> ({@link #shouldPinForwardHeadersStrategyToNone()}):
 * asserts the bound {@link ServerProperties} value is {@code NONE}. This is the cheapest
 * possible gate — it fires the moment the property is deleted or changed, before any
 * behavioural consequence is visible. It is a property-string assertion, which is weaker
 * than a behavioural one, but it is also the earliest possible signal.
 *
 * <p><strong>Behavioural assertion</strong>
 * ({@link #shouldNotHonourForwardedForHeaderWithoutTrustedProxy()}): sends a join request
 * with {@code X-Forwarded-For: 198.51.100.1} from the MockMvc peer ({@code 127.0.0.1}).
 * With {@code NATIVE} or {@code FRAMEWORK}, the container or filter would rewrite
 * {@code getRemoteAddr()} to {@code 198.51.100.1} before {@code ClientAddressResolver}
 * sees it. Because no trusted proxy is configured, the resolver would then use the peer
 * address directly — but if the container already rewrote it, the peer address is now the
 * forwarded value, not the real one. The test saturates the bucket for {@code 198.51.100.1}
 * and then asserts that a second distinct address ({@code 198.51.100.2}) is also throttled
 * after ten failures — which can only happen if both addresses collapsed into the same
 * real peer bucket ({@code 127.0.0.1}), proving the header was ignored. If the strategy
 * were {@code NATIVE} or {@code FRAMEWORK}, the two addresses would produce independent
 * buckets and the second address would not be throttled, failing the assertion.
 * An observable-behaviour assertion is worth more than a property-string assertion because
 * it catches the failure mode rather than just the configuration change that causes it.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:fwd-strategy-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@DisplayName("forward-headers-strategy is pinned to NONE")
class ForwardHeadersStrategyPinnedIntegrationTest {

    /** The property that must stay pinned. Mirrors the key in application.yml. */
    private static final String STRATEGY_PROPERTY = "server.forward-headers-strategy";

    /** Documentation-range address used as the forwarded header value. */
    private static final String ADDRESS_A = "198.51.100.1";

    /** A second documentation-range address, distinct from ADDRESS_A. */
    private static final String ADDRESS_B = "198.51.100.2";

    private static final String SESSIONS_PATH = "/api/v1/sessions";

    private static final String FORWARDED_FOR = "X-Forwarded-For";

    /** Mirrors {@code MAX_FAILURES_PER_ADDRESS}, which is private in the production class. */
    private static final int MAX_FAILURES_PER_ADDRESS = 10;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ServerProperties serverProperties;

    @Test
    @DisplayName("ServerProperties resolves forward-headers-strategy to NONE — flipping it breaks this build")
    void shouldPinForwardHeadersStrategyToNone() {
        assertThat(serverProperties.getForwardHeadersStrategy())
                .as("server.forward-headers-strategy must be NONE (EOP-26): "
                        + "NATIVE trusts 172.16.0.0/12 (contains our Compose subnet) and 127.0.0.0/8; "
                        + "FRAMEWORK rewrites getRemoteAddr() with no trust check at all")
                .isEqualTo(ForwardHeadersStrategy.NONE);
    }

    @Test
    @DisplayName("X-Forwarded-For is not honoured without a trusted proxy — both addresses share the real peer bucket")
    void shouldNotHonourForwardedForHeaderWithoutTrustedProxy() throws Exception {
        final var joinCode = "ZZZZZZZZ";

        // Arrange: exhaust the bucket for ADDRESS_A. If the strategy were NATIVE or
        // FRAMEWORK, the container would rewrite getRemoteAddr() to ADDRESS_A, and
        // ClientAddressResolver (with no trusted proxy) would use that rewritten peer
        // directly — giving ADDRESS_A its own independent bucket.
        for (int attempt = 1; attempt <= MAX_FAILURES_PER_ADDRESS; attempt++) {
            mockMvc.perform(post(SESSIONS_PATH + "/" + joinCode + "/players")
                            .header(FORWARDED_FOR, ADDRESS_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"displayName\":\"Attacker\"}"))
                    .andExpect(status().isNotFound());
        }

        // Assert: ADDRESS_B is also throttled after the same ten failures, because
        // both addresses collapsed into the real peer bucket (127.0.0.1 in MockMvc).
        // If the strategy were NATIVE or FRAMEWORK, ADDRESS_B would have its own
        // empty bucket and would return 404, not 429 — so this assertion would fail,
        // catching the misconfiguration before it reaches production.
        mockMvc.perform(post(SESSIONS_PATH + "/" + joinCode + "/players")
                        .header(FORWARDED_FOR, ADDRESS_B)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Bystander\"}"))
                .andExpect(status().isTooManyRequests());
    }
}
