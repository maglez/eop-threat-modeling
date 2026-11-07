package org.maglez.eop.inbound.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link TrustedProxies} is genuinely default-denied and that its
 * CIDR arithmetic is correct at every boundary.
 *
 * <p>The allow-list is a primary security control. An off-by-one in the prefix
 * mask would silently extend trust to addresses that were never configured, and
 * the only observable symptom would be that an attacker could rotate
 * {@code X-Forwarded-For} to escape the join throttle — the exact defect EOP-26
 * was filed to close. Deleting these tests removes the only machine-checked proof
 * that the boundary arithmetic is correct and that the default is truly denied.
 *
 * <p>The immutability test matters for the same reason: a caller that kept a
 * reference to the list it passed in and later mutated it could widen the allow-list
 * after startup validation had already accepted it.
 */
@DisplayName("TrustedProxies")
class TrustedProxiesTest {

    private static final String PROXY_ADDR = "192.168.1.1";

    private static final String PROXY_CIDR = "192.168.1.0/24";

    private static final String CLIENT_ADDR = "203.0.113.5";

    private static final String INSIDE_CIDR = "192.168.1.200";

    private static final String OUTSIDE_CIDR = "192.168.2.1";

    private static final String ERR_PREFIX = "eop.web.trusted-proxies";

    @Nested
    @DisplayName("default-denied — nothing is trusted without explicit configuration")
    class DefaultDenied {

        @Test
        @DisplayName("none() trusts nobody")
        void shouldTrustNobodyWithNone() {
            final var proxies = TrustedProxies.none();

            assertThat(proxies.isEmpty()).isTrue();
            assertThat(proxies.includes(PROXY_ADDR)).isFalse();
        }

        @Test
        @DisplayName("of(null) trusts nobody")
        void shouldTrustNobodyWithNull() {
            final var proxies = TrustedProxies.of(null);

            assertThat(proxies.isEmpty()).isTrue();
            assertThat(proxies.includes(PROXY_ADDR)).isFalse();
        }

        @Test
        @DisplayName("of(empty list) trusts nobody")
        void shouldTrustNobodyWithEmptyList() {
            final var proxies = TrustedProxies.of(List.of());

            assertThat(proxies.isEmpty()).isTrue();
            assertThat(proxies.includes(PROXY_ADDR)).isFalse();
        }

        @Test
        @DisplayName("null and blank entries are skipped rather than rejected")
        void shouldSkipNullAndBlankEntries() {
            final var entries = new ArrayList<String>();
            entries.add(null);
            entries.add("   ");
            entries.add("");

            assertThatCode(() -> TrustedProxies.of(entries)).doesNotThrowAnyException();
            assertThat(TrustedProxies.of(entries).isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("literal address entries")
    class LiteralEntries {

        @Test
        @DisplayName("a literal entry matches only that exact host")
        void shouldMatchOnlyTheConfiguredHost() {
            final var proxies = TrustedProxies.of(List.of(PROXY_ADDR));

            assertThat(proxies.includes(PROXY_ADDR)).isTrue();
        }

        @Test
        @DisplayName("a literal entry does not match a neighbouring address")
        void shouldNotMatchNeighbour() {
            final var proxies = TrustedProxies.of(List.of(PROXY_ADDR));

            assertThat(proxies.includes("192.168.1.2")).isFalse();
        }

        @Test
        @DisplayName("a literal entry does not match a client address")
        void shouldNotMatchClientAddress() {
            final var proxies = TrustedProxies.of(List.of(PROXY_ADDR));

            assertThat(proxies.includes(CLIENT_ADDR)).isFalse();
        }
    }

    @Nested
    @DisplayName("CIDR block entries")
    class CidrEntries {

        @Test
        @DisplayName("an address inside the CIDR block is included")
        void shouldIncludeAddressInsideBlock() {
            final var proxies = TrustedProxies.of(List.of(PROXY_CIDR));

            assertThat(proxies.includes(INSIDE_CIDR)).isTrue();
        }

        @Test
        @DisplayName("an address outside the CIDR block is excluded")
        void shouldExcludeAddressOutsideBlock() {
            final var proxies = TrustedProxies.of(List.of(PROXY_CIDR));

            assertThat(proxies.includes(OUTSIDE_CIDR)).isFalse();
        }

        @Test
        @DisplayName("the network address itself is included")
        void shouldIncludeNetworkAddress() {
            final var proxies = TrustedProxies.of(List.of(PROXY_CIDR));

            assertThat(proxies.includes("192.168.1.0")).isTrue();
        }

        @Test
        @DisplayName("the broadcast address is included")
        void shouldIncludeBroadcastAddress() {
            final var proxies = TrustedProxies.of(List.of(PROXY_CIDR));

            assertThat(proxies.includes("192.168.1.255")).isTrue();
        }

        @Test
        @DisplayName("/0 matches every IPv4 address")
        void shouldMatchEverythingWithSlashZero() {
            final var proxies = TrustedProxies.of(List.of("0.0.0.0/0"));

            assertThat(proxies.includes(CLIENT_ADDR)).isTrue();
            assertThat(proxies.includes(PROXY_ADDR)).isTrue();
        }

        @Test
        @DisplayName("/32 matches only the single host")
        void shouldMatchOnlySingleHostWithSlash32() {
            final var proxies = TrustedProxies.of(List.of("203.0.113.5/32"));

            assertThat(proxies.includes(CLIENT_ADDR)).isTrue();
            assertThat(proxies.includes("203.0.113.6")).isFalse();
        }

        @Test
        @DisplayName("an IPv4 CIDR does not match an IPv6 address")
        void shouldNotMatchIpv6WithIpv4Cidr() {
            final var proxies = TrustedProxies.of(List.of(PROXY_CIDR));

            assertThat(proxies.includes("::1")).isFalse();
        }

        @Test
        @DisplayName("an IPv6 CIDR does not match an IPv4 address")
        void shouldNotMatchIpv4WithIpv6Cidr() {
            final var proxies = TrustedProxies.of(List.of("::1/128"));

            assertThat(proxies.includes(PROXY_ADDR)).isFalse();
        }

        // Non-byte-aligned prefix tests — these exercise the mask arithmetic at lines 169-170
        // of TrustedProxies.java, which is completely unexercised by the byte-aligned prefixes
        // above (/0, /24, /32, /128 all give remainingBits==0 and return early at line 166).
        // A wrong mask here silently widens the trust boundary for X-Forwarded-For, allowing
        // an attacker to rotate their rate-limiter bucket — the exact defect EOP-26 closed.

        @Test
        @DisplayName("trusts an address inside a /25 block (lower half: 10.0.0.0–10.0.0.127)")
        void shouldTrustAddressInsideLowerHalfSlash25() {
            final var proxies = TrustedProxies.of(List.of("10.0.0.0/25"));

            assertThat(proxies.includes("10.0.0.1")).isTrue();
            assertThat(proxies.includes("10.0.0.127")).isTrue();
        }

        @Test
        @DisplayName("rejects an address in the upper half of a /25 block — boundary: 10.0.0.127 in, 10.0.0.128 out")
        void shouldRejectAddressAtBoundaryOfSlash25() {
            final var proxies = TrustedProxies.of(List.of("10.0.0.0/25"));

            assertThat(proxies.includes("10.0.0.127")).isTrue();
            assertThat(proxies.includes("10.0.0.128")).isFalse();
            assertThat(proxies.includes("10.0.0.255")).isFalse();
        }

        @Test
        @DisplayName("trusts an address inside a /25 block (upper half: 10.0.0.128–10.0.0.255)")
        void shouldTrustAddressInsideUpperHalfSlash25() {
            final var proxies = TrustedProxies.of(List.of("10.0.0.128/25"));

            assertThat(proxies.includes("10.0.0.128")).isTrue();
            assertThat(proxies.includes("10.0.0.200")).isTrue();
        }

        @Test
        @DisplayName("rejects an address in the lower half when the /25 block starts at 128 — boundary: 10.0.0.128 in, 10.0.0.127 out")
        void shouldRejectAddressAtBoundaryOfUpperHalfSlash25() {
            final var proxies = TrustedProxies.of(List.of("10.0.0.128/25"));

            assertThat(proxies.includes("10.0.0.128")).isTrue();
            assertThat(proxies.includes("10.0.0.127")).isFalse();
            assertThat(proxies.includes("10.0.0.0")).isFalse();
        }

        @Test
        @DisplayName("trusts an address inside a /12 block where the whole-byte loop runs before the mask decides")
        void shouldTrustAddressInsideSlash12() {
            // 10.16.0.0/12: wholeBytes=1 (loop checks byte[0]==10), remainingBits=4, mask=0xF0
            // byte[1] of network is 0x10; candidate byte[1] & 0xF0 must equal 0x10
            final var proxies = TrustedProxies.of(List.of("10.16.0.0/12"));

            assertThat(proxies.includes("10.16.0.1")).isTrue();
            assertThat(proxies.includes("10.31.255.255")).isTrue();
        }

        @Test
        @DisplayName("rejects an address outside a /12 block — verifies the whole-byte loop and mask both contribute")
        void shouldRejectAddressOutsideSlash12() {
            // 10.32.0.0 has byte[1]=0x20; 0x20 & 0xF0 = 0x20 != 0x10 — mask rejects it
            // 10.15.255.255 has byte[1]=0x0F; 0x0F & 0xF0 = 0x00 != 0x10 — mask rejects it
            // 11.16.0.0 has byte[0]=11 != 10 — whole-byte loop rejects it
            final var proxies = TrustedProxies.of(List.of("10.16.0.0/12"));

            assertThat(proxies.includes("10.32.0.0")).isFalse();
            assertThat(proxies.includes("10.15.255.255")).isFalse();
            assertThat(proxies.includes("11.16.0.0")).isFalse();
        }

        @Test
        @DisplayName("trusts an address inside a /30 block (mask 0xFC — narrow four-host range)")
        void shouldTrustAddressInsideSlash30() {
            // 192.168.1.0/30: wholeBytes=3, remainingBits=6, mask=0xFC
            // Covers .0, .1, .2, .3 only
            final var proxies = TrustedProxies.of(List.of("192.168.1.0/30"));

            assertThat(proxies.includes("192.168.1.1")).isTrue();
            assertThat(proxies.includes("192.168.1.2")).isTrue();
            assertThat(proxies.includes("192.168.1.3")).isTrue();
        }

        @Test
        @DisplayName("rejects an address just outside a /30 block — boundary: 192.168.1.3 in, 192.168.1.4 out")
        void shouldRejectAddressJustOutsideSlash30() {
            final var proxies = TrustedProxies.of(List.of("192.168.1.0/30"));

            assertThat(proxies.includes("192.168.1.3")).isTrue();
            assertThat(proxies.includes("192.168.1.4")).isFalse();
        }

        @Test
        @DisplayName("trusts an address inside an IPv6 /49 block (mask 0x80 on byte 6)")
        void shouldTrustAddressInsideIpv6Slash49() {
            // 2001:db8::/49: wholeBytes=6, remainingBits=1, mask=0x80
            // byte[6] of network is 0x00; candidate byte[6] & 0x80 must equal 0x00
            final var proxies = TrustedProxies.of(List.of("2001:db8::/49"));

            assertThat(proxies.includes("2001:db8::1")).isTrue();
        }

        @Test
        @DisplayName("rejects an IPv6 address in the upper half of a /49 block — boundary: byte 6 high bit")
        void shouldRejectAddressOutsideIpv6Slash49() {
            // 2001:db8:0:8000::1 has byte[6]=0x80; 0x80 & 0x80 = 0x80 != 0x00 — mask rejects it
            final var proxies = TrustedProxies.of(List.of("2001:db8::/49"));

            assertThat(proxies.includes("2001:db8:0:8000::1")).isFalse();
        }
    }

    @Nested
    @DisplayName("rejection of invalid entries")
    class InvalidEntries {

        @Test
        @DisplayName("host bits set in CIDR is rejected with the configured property name in the message")
        void shouldRejectHostBitsSet() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TrustedProxies.of(List.of("10.0.0.5/8")))
                    .withMessageContaining(ERR_PREFIX)
                    .withMessageContaining("10.0.0.5/8");
        }

        @Test
        @DisplayName("prefix length above 32 for IPv4 is rejected")
        void shouldRejectPrefixAbove32() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TrustedProxies.of(List.of("10.0.0.0/33")))
                    .withMessageContaining(ERR_PREFIX);
        }

        @Test
        @DisplayName("prefix length above 128 for IPv6 is rejected")
        void shouldRejectIpv6PrefixAbove128() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TrustedProxies.of(List.of("::1/129")))
                    .withMessageContaining(ERR_PREFIX);
        }

        @Test
        @DisplayName("non-numeric prefix length is rejected")
        void shouldRejectNonNumericPrefix() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TrustedProxies.of(List.of("10.0.0.0/abc")))
                    .withMessageContaining(ERR_PREFIX);
        }

        @Test
        @DisplayName("negative prefix length is rejected")
        void shouldRejectNegativePrefix() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TrustedProxies.of(List.of("10.0.0.0/-1")))
                    .withMessageContaining(ERR_PREFIX);
        }

        @Test
        @DisplayName("a non-IP string is rejected")
        void shouldRejectNonIpString() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TrustedProxies.of(List.of("not-an-ip")))
                    .withMessageContaining(ERR_PREFIX);
        }

        @Test
        @DisplayName("a hostname with CIDR notation is rejected")
        void shouldRejectHostnameWithCidr() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TrustedProxies.of(List.of("example.com/24")))
                    .withMessageContaining(ERR_PREFIX);
        }
    }

    @Nested
    @DisplayName("immutability — the allow-list cannot be widened after construction")
    class Immutability {

        @Test
        @DisplayName("mutating the list passed to of() does not change what the instance includes")
        void shouldNotBeAffectedByMutationOfSourceList() {
            final var mutable = new ArrayList<String>();
            mutable.add(PROXY_ADDR);
            final var proxies = TrustedProxies.of(mutable);

            mutable.add("10.0.0.1");

            assertThat(proxies.includes("10.0.0.1")).isFalse();
        }
    }
}
