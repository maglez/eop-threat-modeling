package org.maglez.eop.inbound.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.maglez.eop.config.ReadRateLimitProperties;
import org.maglez.eop.config.TrustedProxyProperties;
import org.maglez.eop.application.RateLimitedException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.maglez.eop.domain.Player;

/**
 * Unit tests for the read rate limiter's interceptor (EOP-88). No Spring
 * context: the interceptor is constructed directly so the limiter key can be
 * probed with hand-built requests.
 *
 * <p>The tests that matter most here are the ones asserting that no request
 * header can influence the key. That is acceptance criterion 3 of EOP-88, and it
 * exists because the join-code throttle once took its key from a client-supplied
 * {@code X-Forwarded-For}: a fresh header value per request meant a fresh empty
 * bucket, so the control counted nothing (EOP-26, ADR-021).
 */
@DisplayName("ReadRateLimitInterceptor")
class ReadRateLimitInterceptorTest {

    private static final String CALLER = "203.0.113.7";
    private static final String OTHER_CALLER = "203.0.113.8";
    private static final String SPOOFED = "198.51.100.9";
    private static final int GENEROUS_KEY_TABLE = 100;

    /**
     * A frozen clock. None of these tests exercises expiry, so holding time still keeps them exactly
     * deterministic: no entry can age out of the window part-way through a test.
     */
    private static final Instant FROZEN = Instant.parse("2026-08-22T10:00:00Z");

    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        response = new MockHttpServletResponse();
    }

    /**
     * Builds an interceptor with an empty trusted-proxy list, which is both the
     * shipped default and the test-suite value: with no trusted peer, forwarding
     * headers are ignored outright.
     */
    private static ReadRateLimitInterceptor interceptor(final int limit) {
        final var resolver = new ClientAddressResolver(new TrustedProxyProperties(List.of(), 5));
        return new ReadRateLimitInterceptor(resolver, Clock.fixed(FROZEN, ZoneOffset.UTC),
                new ReadRateLimitProperties(limit, GENEROUS_KEY_TABLE));
    }

    private static MockHttpServletRequest read(final String method, final String remoteAddress) {
        final var request = new MockHttpServletRequest(method, "/api/v1/cards");
        request.setRemoteAddr(remoteAddress);
        return request;
    }

    @Nested
    @DisplayName("counts reads")
    class CountsReads {

        @Test
        @DisplayName("admits reads up to the limit and returns true so the handler runs")
        void shouldAdmitUpToTheLimit() throws Exception {
            final var subject = interceptor(2);

            assertThat(subject.preHandle(read("GET", CALLER), response, new Object())).isTrue();
            assertThat(subject.preHandle(read("GET", CALLER), response, new Object())).isTrue();

            // The third call must be refused. Without this assertion the test would still pass if
            // preHandle returned true while counting nothing at all.
            assertThatExceptionOfType(RateLimitedException.class)
                    .isThrownBy(() -> subject.preHandle(read("GET", CALLER), response, new Object()));
        }

        @Test
        @DisplayName("refuses the read after the limit, by throwing so the single advice renders problem+json")
        void shouldRefuseBeyondTheLimit() throws Exception {
            final var subject = interceptor(1);
            subject.preHandle(read("GET", CALLER), response, new Object());

            assertThatExceptionOfType(RateLimitedException.class)
                    .isThrownBy(() -> subject.preHandle(read("GET", CALLER), response, new Object()));
        }

        @Test
        @DisplayName("HEAD is counted too: it reaches the same handler and costs the same work")
        void shouldCountHeadRequests() throws Exception {
            final var subject = interceptor(1);
            subject.preHandle(read("HEAD", CALLER), response, new Object());

            assertThatExceptionOfType(RateLimitedException.class)
                    .isThrownBy(() -> subject.preHandle(read("GET", CALLER), response, new Object()));
        }

        @Test
        @DisplayName("one caller's exhaustion does not refuse another caller")
        void shouldCountCallersSeparately() throws Exception {
            final var subject = interceptor(1);
            subject.preHandle(read("GET", CALLER), response, new Object());

            assertThat(subject.preHandle(read("GET", OTHER_CALLER), response, new Object())).isTrue();
        }
    }

    @Nested
    @DisplayName("ignores writes, which have their own dedicated limiters")
    class IgnoresWrites {

        @ParameterizedTest(name = "{0} is not counted")
        @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE", "OPTIONS"})
        @DisplayName("a write passes through without consuming a read allowance")
        void shouldNotCountWrites(final String method) throws Exception {
            final var subject = interceptor(1);

            // Exhausting the limit would take a single call if writes were counted.
            for (int attempt = 0; attempt < 5; attempt++) {
                assertThat(subject.preHandle(read(method, CALLER), response, new Object())).isTrue();
            }

            assertThat(subject.preHandle(read("GET", CALLER), response, new Object())).isTrue();
        }
    }

    @Nested
    @DisplayName("the key cannot be influenced by a request header (EOP-88 AC 3, EOP-26, ADR-021)")
    class KeyIsNotHeaderDerived {

        @ParameterizedTest(name = "{0} cannot mint a fresh bucket")
        @ValueSource(strings = {
            "X-Forwarded-For",
            "X-Forwarded",
            "Forwarded",
            "X-Real-IP",
            "X-Client-IP",
            "Client-IP",
            "True-Client-IP",
            "CF-Connecting-IP",
            "X-Cluster-Client-IP",
            "X-Originating-IP",
            "X-Remote-Addr",
            "X-EoP-Player-Token"
        })
        @DisplayName("a header value is not consulted, so the exhausted caller stays exhausted")
        void shouldIgnoreForwardingHeaders(final String header) throws Exception {
            final var subject = interceptor(1);
            subject.preHandle(read("GET", CALLER), response, new Object());

            final var forged = read("GET", CALLER);
            forged.addHeader(header, SPOOFED);

            assertThatExceptionOfType(RateLimitedException.class)
                    .isThrownBy(() -> subject.preHandle(forged, response, new Object()));
        }

        @Test
        @DisplayName("a different header value on every request does not evade the limit")
        void shouldNotBeEvadedByRotatingTheHeader() throws Exception {
            final var subject = interceptor(3);

            for (int attempt = 0; attempt < 3; attempt++) {
                final var request = read("GET", CALLER);
                request.addHeader("X-Forwarded-For", "198.51.100." + attempt);
                assertThat(subject.preHandle(request, response, new Object())).isTrue();
            }

            final var fourth = read("GET", CALLER);
            fourth.addHeader("X-Forwarded-For", "198.51.100.99");

            assertThatExceptionOfType(RateLimitedException.class)
                    .isThrownBy(() -> subject.preHandle(fourth, response, new Object()));
        }

        @Test
        @DisplayName("a forged header does not consume a different caller's allowance either")
        void shouldNotLetAHeaderChargeAnotherCaller() throws Exception {
            final var subject = interceptor(1);

            final var forged = read("GET", CALLER);
            forged.addHeader("X-Forwarded-For", OTHER_CALLER);
            subject.preHandle(forged, response, new Object());

            // If the header had been believed, the allowance charged would have been
            // OTHER_CALLER's and this read would be refused.
            assertThat(subject.preHandle(read("GET", OTHER_CALLER), response, new Object())).isTrue();
        }

        @Test
        @DisplayName("a request with no usable peer address is still counted, in its own bucket")
        void shouldCountARequestWithNoPeerAddress() throws Exception {
            final var subject = interceptor(1);
            final var request = new MockHttpServletRequest("GET", "/api/v1/cards");
            request.setRemoteAddr(null);
            subject.preHandle(request, response, new Object());

            final var second = new MockHttpServletRequest("GET", "/api/v1/cards");
            second.setRemoteAddr(null);

            assertThatExceptionOfType(RateLimitedException.class)
                    .isThrownBy(() -> subject.preHandle(second, response, new Object()));
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("takes its limit from configuration rather than a constant")
        void shouldHonourTheConfiguredLimit() {
            final var subject = interceptor(4);

            assertThatCode(() -> {
                for (int attempt = 0; attempt < 4; attempt++) {
                    subject.preHandle(read("GET", CALLER), response, new Object());
                }
            }).doesNotThrowAnyException();

            // Assert the ceiling as well as the floor. Four admissions alone would prove only that
            // the limit is at least four, which any larger configured value would also satisfy.
            assertThatExceptionOfType(RateLimitedException.class)
                    .isThrownBy(() -> subject.preHandle(read("GET", CALLER), response, new Object()));
        }
    }
}
