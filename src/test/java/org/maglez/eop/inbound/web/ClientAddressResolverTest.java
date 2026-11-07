package org.maglez.eop.inbound.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.maglez.eop.config.TrustedProxyProperties;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Verifies that {@link ClientAddressResolver} returns the correct address under
 * every combination of peer trust and forwarding header.
 *
 * <p>The resolver is the gate between the network and the join throttle. If it
 * returns the wrong address, the throttle keys its bucket on the wrong value and
 * the per-address limit is either applied to the wrong party or bypassed entirely.
 * Before EOP-26 the header was read unconditionally, so any caller could supply
 * its own throttle key and rotate it once per request to get a fresh empty bucket
 * every time. Deleting these tests removes the only machine-checked proof that the
 * gate is closed: that the header is ignored when the peer is not trusted, and
 * that the peer address is used as the fallback when the header is absent or
 * non-literal.
 *
 * <p>No Spring context is loaded. The resolver is a plain component and
 * {@link MockHttpServletRequest} from {@code spring-test} provides a fast,
 * in-memory servlet request without starting a container.
 */
@DisplayName("ClientAddressResolver")
class ClientAddressResolverTest {

    private static final String PROXY_ADDR = "192.168.1.1";

    private static final String CLIENT_ADDR = "203.0.113.42";

    private static final String OTHER_CLIENT = "198.51.100.7";

    private static final String FORWARDED_FOR = "X-Forwarded-For";

    private static ClientAddressResolver resolverWithNoTrustedProxies() {
        return new ClientAddressResolver(new TrustedProxyProperties(List.of(), 5));
    }

    private static ClientAddressResolver resolverTrusting(final String cidr) {
        return new ClientAddressResolver(new TrustedProxyProperties(List.of(cidr), 5));
    }

    @Nested
    @DisplayName("Scenario 1 — header ignored when the peer is not a trusted proxy")
    class UntrustedPeer {

        @Test
        @DisplayName("a direct caller sending X-Forwarded-For is resolved to its real peer address")
        void shouldIgnoreHeaderFromUntrustedPeer() {
            final var resolver = resolverWithNoTrustedProxies();
            final var request = new MockHttpServletRequest();
            request.setRemoteAddr(CLIENT_ADDR);
            request.addHeader(FORWARDED_FOR, "10.0.0.1");

            final var address = resolver.of(request);

            assertThat(address).isEqualTo(CLIENT_ADDR);
        }

        @Test
        @DisplayName("rotating X-Forwarded-For does not change the resolved address for an untrusted peer")
        void shouldReturnSamePeerRegardlessOfRotatedHeader() {
            final var resolver = resolverWithNoTrustedProxies();

            final var request1 = new MockHttpServletRequest();
            request1.setRemoteAddr(CLIENT_ADDR);
            request1.addHeader(FORWARDED_FOR, "10.0.0.1");

            final var request2 = new MockHttpServletRequest();
            request2.setRemoteAddr(CLIENT_ADDR);
            request2.addHeader(FORWARDED_FOR, "10.0.0.2");

            assertThat(resolver.of(request1)).isEqualTo(resolver.of(request2));
        }
    }

    @Nested
    @DisplayName("Scenario 2 — header honoured from a configured proxy")
    class TrustedPeer {

        @Test
        @DisplayName("the last entry of a forwarded chain is returned as the client address")
        void shouldReturnLastForwardedEntry() {
            final var resolver = resolverTrusting(PROXY_ADDR + "/32");
            final var request = new MockHttpServletRequest();
            request.setRemoteAddr(PROXY_ADDR);
            request.addHeader(FORWARDED_FOR, OTHER_CLIENT + ", " + CLIENT_ADDR);

            final var address = resolver.of(request);

            assertThat(address).isEqualTo(CLIENT_ADDR);
        }

        @Test
        @DisplayName("a single-hop forwarded header returns that one entry")
        void shouldReturnSingleHopEntry() {
            final var resolver = resolverTrusting(PROXY_ADDR + "/32");
            final var request = new MockHttpServletRequest();
            request.setRemoteAddr(PROXY_ADDR);
            request.addHeader(FORWARDED_FOR, CLIENT_ADDR);

            assertThat(resolver.of(request)).isEqualTo(CLIENT_ADDR);
        }

        @Test
        @DisplayName("a non-literal forwarded value falls back to the peer address")
        void shouldFallBackToPeerWhenForwardedValueIsNotLiteral() {
            final var resolver = resolverTrusting(PROXY_ADDR + "/32");
            final var request = new MockHttpServletRequest();
            request.setRemoteAddr(PROXY_ADDR);
            request.addHeader(FORWARDED_FOR, "bogus");

            assertThat(resolver.of(request)).isEqualTo(PROXY_ADDR);
        }

        @Test
        @DisplayName("an absent forwarded header falls back to the peer address")
        void shouldFallBackToPeerWhenHeaderAbsent() {
            final var resolver = resolverTrusting(PROXY_ADDR + "/32");
            final var request = new MockHttpServletRequest();
            request.setRemoteAddr(PROXY_ADDR);

            assertThat(resolver.of(request)).isEqualTo(PROXY_ADDR);
        }

        @Test
        @DisplayName("a blank forwarded header falls back to the peer address")
        void shouldFallBackToPeerWhenHeaderBlank() {
            final var resolver = resolverTrusting(PROXY_ADDR + "/32");
            final var request = new MockHttpServletRequest();
            request.setRemoteAddr(PROXY_ADDR);
            request.addHeader(FORWARDED_FOR, "   ");

            assertThat(resolver.of(request)).isEqualTo(PROXY_ADDR);
        }
    }

    @Nested
    @DisplayName("Scenario 3 — default-denied: no trusted proxy means header is always ignored")
    class DefaultDenied {

        @Test
        @DisplayName("with no trusted proxies configured, X-Forwarded-For is ignored and getRemoteAddr is used")
        void shouldUseRemoteAddrWhenNoProxiesConfigured() {
            final var resolver = resolverWithNoTrustedProxies();
            final var request = new MockHttpServletRequest();
            request.setRemoteAddr(CLIENT_ADDR);
            request.addHeader(FORWARDED_FOR, OTHER_CLIENT);

            assertThat(resolver.of(request)).isEqualTo(CLIENT_ADDR);
        }
    }

    @Nested
    @DisplayName("edge cases — null and blank peer addresses")
    class EdgeCases {

        @Test
        @DisplayName("a null remote address yields the sentinel 'unknown'")
        void shouldReturnUnknownForNullRemoteAddr() {
            final var resolver = resolverWithNoTrustedProxies();
            final var request = new MockHttpServletRequest();
            request.setRemoteAddr(null);

            assertThat(resolver.of(request)).isEqualTo("unknown");
        }

        @Test
        @DisplayName("a blank remote address yields the sentinel 'unknown'")
        void shouldReturnUnknownForBlankRemoteAddr() {
            final var resolver = resolverWithNoTrustedProxies();
            final var request = new MockHttpServletRequest();
            request.setRemoteAddr("   ");

            assertThat(resolver.of(request)).isEqualTo("unknown");
        }

        @Test
        @DisplayName("an IPv4-mapped IPv6 peer canonicalises to the same value as the plain IPv4 form")
        void shouldCanonicaliseEquivalentPeerSpellings() {
            final var resolver = resolverWithNoTrustedProxies();

            final var requestPlain = new MockHttpServletRequest();
            requestPlain.setRemoteAddr("203.0.113.1");

            final var requestMapped = new MockHttpServletRequest();
            requestMapped.setRemoteAddr("::ffff:203.0.113.1");

            assertThat(resolver.of(requestPlain)).isEqualTo(resolver.of(requestMapped));
        }

        @Test
        @DisplayName("a null properties record is rejected at construction time")
        void shouldRejectNullProperties() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new ClientAddressResolver(null));
        }

        @Test
        @DisplayName("a forwarded chain of several hops takes the last entry")
        void shouldTakeLastEntryFromMultiHopChain() {
            final var resolver = resolverTrusting(PROXY_ADDR + "/32");
            final var request = new MockHttpServletRequest();
            request.setRemoteAddr(PROXY_ADDR);
            request.addHeader(FORWARDED_FOR, "10.0.0.1, 10.0.0.2, " + CLIENT_ADDR);

            assertThat(resolver.of(request)).isEqualTo(CLIENT_ADDR);
        }

        @Test
        @DisplayName("a non-literal remote address is returned as its stripped string, not as 'unknown'")
        void shouldReturnStrippedStringForNonLiteralRemoteAddr() {
            // peerAddress() calls IpLiterals.canonical(peer).orElseGet(peer::strip),
            // so a hostname that is not an IP literal is returned verbatim (stripped).
            // This pins the documented fallback contract so a refactor cannot silently
            // change it to 'unknown' and collapse all non-literal peers into one bucket.
            final var resolver = resolverWithNoTrustedProxies();
            final var request = new MockHttpServletRequest();
            request.setRemoteAddr("  internal-host  ");

            assertThat(resolver.of(request)).isEqualTo("internal-host");
        }
    }
}
