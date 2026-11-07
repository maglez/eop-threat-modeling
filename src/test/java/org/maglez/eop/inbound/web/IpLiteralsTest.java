package org.maglez.eop.inbound.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.Inet6Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.maglez.eop.outbound.web.InMemoryJoinAttemptLimiter;

/**
 * Verifies that {@link IpLiterals} accepts only genuine IP literals and reduces
 * equivalent spellings to one canonical form.
 *
 * <p>The canonical-form guarantee is a security property, not a cosmetic one.
 * {@link InMemoryJoinAttemptLimiter} keys its per-address bucket on the string
 * returned by {@link ClientAddressResolver}, so two spellings of one address
 * would be two independent buckets. An attacker who controls the
 * {@code X-Forwarded-For} header could therefore rotate between
 * {@code 10.0.0.1} and {@code ::ffff:10.0.0.1} to get a fresh empty bucket on
 * every request, defeating the ten-failures-per-minute limit entirely. Deleting
 * these tests removes the only machine-checked proof that the canonicalisation
 * actually collapses those two spellings.
 *
 * <p>The DNS-free guarantee matters equally. A hostname in the allow-list or in
 * a forwarded header would become a network call on the request path, and the
 * meaning of the allow-list would depend on what a resolver said at that moment.
 * An allow-list whose membership changes with DNS is not an allow-list.
 */
@DisplayName("IpLiterals")
class IpLiteralsTest {

    private static final String IPV4_PLAIN = "10.0.0.1";

    private static final String IPV4_MAPPED = "::ffff:10.0.0.1";

    private static final String LOOPBACK_V4 = "127.0.0.1";

    private static final String LOOPBACK_V6 = "::1";

    @Nested
    @DisplayName("parse — byte extraction")
    class Parse {

        @Test
        @DisplayName("a plain IPv4 address yields exactly four bytes")
        void shouldReturnFourBytesForIpv4() {
            final var result = IpLiterals.parse(IPV4_PLAIN);

            assertThat(result).isPresent();
            assertThat(result.get()).hasSize(4);
        }

        @Test
        @DisplayName("a true IPv6 address yields exactly sixteen bytes")
        void shouldReturnSixteenBytesForIpv6() {
            final var result = IpLiterals.parse(LOOPBACK_V6);

            assertThat(result).isPresent();
            assertThat(result.get()).hasSize(16);
        }

        @Test
        @DisplayName("an IPv4-mapped IPv6 address is folded to four bytes by the JDK")
        void shouldFoldIpv4MappedToFourBytes() {
            final var result = IpLiterals.parse(IPV4_MAPPED);

            assertThat(result).isPresent();
            assertThat(result.get()).hasSize(4);
        }

        @Test
        @DisplayName("null yields empty")
        void shouldReturnEmptyForNull() {
            assertThat(IpLiterals.parse(null)).isEmpty();
        }

        @Test
        @DisplayName("an empty string yields empty")
        void shouldReturnEmptyForEmptyString() {
            assertThat(IpLiterals.parse("")).isEmpty();
        }

        @Test
        @DisplayName("a blank string yields empty")
        void shouldReturnEmptyForBlankString() {
            assertThat(IpLiterals.parse("   ")).isEmpty();
        }

        @Test
        @DisplayName("a scoped IPv6 address is accepted and yields sixteen bytes")
        void shouldReturnSixteenBytesForScopedIpv6() {
            // fe80::1%0 uses a numeric scope id, which is platform-independent.
            // The JDK strips the scope suffix when producing the canonical string,
            // so two link-local addresses with different scopes but the same host
            // part canonicalise to the same string and share a throttle bucket.
            // This is a known limitation documented here so it is not accidentally
            // "fixed" in a way that introduces DNS lookups or other side effects.
            final var result = IpLiterals.parse("fe80::1%0");

            assertThat(result).isPresent();
            assertThat(result.get()).hasSize(16);
        }
    }

    @Nested
    @DisplayName("canonical — anti-fresh-bucket property")
    class Canonical {

        @Test
        @DisplayName("10.0.0.1 and ::ffff:10.0.0.1 canonicalise to the same string")
        void shouldCollapseIpv4AndIpv4MappedToOneSpelling() {
            final var plain = IpLiterals.canonical(IPV4_PLAIN);
            final var mapped = IpLiterals.canonical(IPV4_MAPPED);

            assertThat(plain).isPresent();
            assertThat(mapped).isPresent();
            assertThat(plain).isEqualTo(mapped);
        }

        @Test
        @DisplayName("a plain IPv4 address round-trips through canonical")
        void shouldRoundTripIpv4() {
            final var result = IpLiterals.canonical(LOOPBACK_V4);

            assertThat(result).isPresent().hasValue(LOOPBACK_V4);
        }

        @Test
        @DisplayName("a bracketed IPv6 address is accepted and canonicalised")
        void shouldAcceptBracketedIpv6() {
            final var bracketed = IpLiterals.canonical("[::1]");
            final var plain = IpLiterals.canonical(LOOPBACK_V6);

            assertThat(bracketed).isPresent();
            assertThat(plain).isPresent();
            assertThat(bracketed).isEqualTo(plain);
        }

        @Test
        @DisplayName("null yields empty")
        void shouldReturnEmptyForNull() {
            assertThat(IpLiterals.canonical(null)).isEmpty();
        }

        @Test
        @DisplayName("an empty string yields empty")
        void shouldReturnEmptyForEmptyString() {
            assertThat(IpLiterals.canonical("")).isEmpty();
        }

        @Test
        @DisplayName("a blank string yields empty")
        void shouldReturnEmptyForBlankString() {
            assertThat(IpLiterals.canonical("  ")).isEmpty();
        }

        @Test
        @DisplayName("a scoped IPv6 address is accepted; the JDK strips the scope in the canonical form")
        void shouldStripScopeSuffixInCanonical() {
            // The JDK's InetAddress.getHostAddress() strips the scope ID, so
            // fe80::1%0 and fe80::1%1 canonicalise to the same string. This is
            // a known limitation: two link-local addresses with different scopes
            // but the same host part share a throttle bucket. Pinning this
            // behaviour here prevents a future refactor from accidentally
            // introducing DNS lookups while trying to "fix" it.
            final var scope0 = IpLiterals.canonical("fe80::1%0");
            final var scope1 = IpLiterals.canonical("fe80::1%1");

            assertThat(scope0).isPresent();
            assertThat(scope1).isPresent();
            assertThat(scope0).isEqualTo(scope1);
        }
    }

    @Nested
    @DisplayName("rejection of non-literals — DNS-free guarantee")
    class Rejection {

        @Test
        @DisplayName("a hostname is rejected, proving no DNS lookup can slip in")
        void shouldRejectHostname() {
            assertThat(IpLiterals.parse("localhost")).isEmpty();
        }

        @Test
        @DisplayName("a domain name is rejected")
        void shouldRejectDomainName() {
            assertThat(IpLiterals.parse("example.com")).isEmpty();
        }

        @Test
        @DisplayName("an octet with a leading zero is rejected to prevent octal ambiguity")
        void shouldRejectLeadingZeroOctet() {
            assertThat(IpLiterals.parse("010.1.1.1")).isEmpty();
        }

        @Test
        @DisplayName("fewer than four octets is rejected")
        void shouldRejectTooFewOctets() {
            assertThat(IpLiterals.parse("1.2.3")).isEmpty();
        }

        @Test
        @DisplayName("more than four octets is rejected")
        void shouldRejectTooManyOctets() {
            assertThat(IpLiterals.parse("1.2.3.4.5")).isEmpty();
        }

        @Test
        @DisplayName("an octet above 255 is rejected")
        void shouldRejectOctetAbove255() {
            assertThat(IpLiterals.parse("1.2.3.256")).isEmpty();
        }

        @Test
        @DisplayName("a negative octet is rejected")
        void shouldRejectNegativeOctet() {
            assertThat(IpLiterals.parse("1.2.3.-1")).isEmpty();
        }

        @Test
        @DisplayName("the utility class cannot be instantiated")
        void shouldThrowOnInstantiation() {
            assertThatCode(() -> {
                final var ctor = IpLiterals.class.getDeclaredConstructor();
                ctor.setAccessible(true);
                ctor.newInstance();
            }).hasCauseInstanceOf(AssertionError.class);
        }
    }

    /**
     * Pins the {@code isLiteralStart} guard in {@link IpLiterals} as the load-bearing
     * mechanism that keeps colon-bearing non-literals off the DNS resolver.
     *
     * <p>A value-only assertion ({@code assertThat(...).isEmpty()}) is insufficient here.
     * {@code parseIpv6} catches {@link java.net.UnknownHostException} and returns empty,
     * so deleting {@code isLiteralStart} from {@code IpLiterals} would still return empty
     * for every input below — the build would stay green while a blocking DNS lookup was
     * silently reintroduced on the request path. The security auditor demonstrated this
     * with a negative control: a copy of {@code IpLiterals} with {@code isLiteralStart}
     * deleted leaked {@code .a:b} to the resolver and took over 10 000 µs. A timing
     * assertion is fragile and rejected here; instead, {@link RecordingInetAddressResolverProvider}
     * is registered via {@code META-INF/services} and armed only for the duration of each
     * test. Any call to {@code InetAddress.getByName} while the spy is armed increments
     * {@link RecordingInetAddressResolverProvider#LOOKUP_COUNT}. Asserting the count stays
     * at zero is the only assertion that would fail if {@code isLiteralStart} were deleted.
     *
     * <p>The five colon-bearing non-literals below were chosen to cover every distinct
     * rejection path in {@code isAddressText}: {@code zzz:80} fails the per-character
     * alphabet check; {@code localhost:80}, {@code host.example.com:80}, and {@code _:_}
     * fail the {@code isLiteralStart} check on the first character; {@code .a:b} is the
     * most important case — a dot is a legal address character so it passes the alphabet
     * check, but a leading dot is not a legal literal start, and only {@code isLiteralStart}
     * rejects it. The genuine literals below verify the guard is not over-tight.
     */
    @Nested
    @DisplayName("isLiteralStart guard — colon-bearing non-literals never reach the resolver")
    class DnsFreeLiteralGuard {

        private static final String ZZZ_COLON = "zzz:80";

        private static final String LOCALHOST_COLON = "localhost:80";

        private static final String HOST_EXAMPLE_COLON = "host.example.com:80";

        private static final String UNDERSCORE_COLON = "_:_";

        private static final String DOT_A_COLON = ".a:b";

        @ParameterizedTest(name = "parse({0}) returns empty without a resolver hit")
        @ValueSource(strings = {ZZZ_COLON, LOCALHOST_COLON, HOST_EXAMPLE_COLON, UNDERSCORE_COLON, DOT_A_COLON})
        @DisplayName("colon-bearing non-literals are rejected before reaching the resolver")
        void shouldRejectColonBearingNonLiteralsWithoutResolverHit(final String input) {
            RecordingInetAddressResolverProvider.LOOKUP_COUNT.set(0);
            RecordingInetAddressResolverProvider.ACTIVE.set(true);
            try {
                final var result = IpLiterals.parse(input);

                assertThat(result)
                        .as("parse(%s) must return empty — isLiteralStart is the guard", input)
                        .isEmpty();
                assertThat(RecordingInetAddressResolverProvider.LOOKUP_COUNT.get())
                        .as("no resolver lookup must occur for %s — deleting isLiteralStart breaks this", input)
                        .isZero();
            }
            finally {
                RecordingInetAddressResolverProvider.ACTIVE.set(false);
            }
        }

        @ParameterizedTest(name = "canonical({0}) returns empty without a resolver hit")
        @ValueSource(strings = {ZZZ_COLON, LOCALHOST_COLON, HOST_EXAMPLE_COLON, UNDERSCORE_COLON, DOT_A_COLON})
        @DisplayName("canonical also rejects colon-bearing non-literals without a resolver hit")
        void shouldRejectColonBearingNonLiteralsInCanonicalWithoutResolverHit(final String input) {
            RecordingInetAddressResolverProvider.LOOKUP_COUNT.set(0);
            RecordingInetAddressResolverProvider.ACTIVE.set(true);
            try {
                final var result = IpLiterals.canonical(input);

                assertThat(result)
                        .as("canonical(%s) must return empty — isLiteralStart is the guard", input)
                        .isEmpty();
                assertThat(RecordingInetAddressResolverProvider.LOOKUP_COUNT.get())
                        .as("no resolver lookup must occur for %s — deleting isLiteralStart breaks this", input)
                        .isZero();
            }
            finally {
                RecordingInetAddressResolverProvider.ACTIVE.set(false);
            }
        }

        @Test
        @DisplayName("::1 is a genuine literal and still parses")
        void shouldParseLoopbackV6() {
            assertThat(IpLiterals.parse(LOOPBACK_V6)).isPresent();
        }

        @Test
        @DisplayName("[::1] bracketed form is a genuine literal and still parses")
        void shouldParseBracketedLoopbackV6() {
            assertThat(IpLiterals.parse("[::1]")).isPresent();
        }

        @Test
        @DisplayName("::ffff:10.0.0.1 IPv4-mapped is a genuine literal and still parses")
        void shouldParseIpv4Mapped() {
            assertThat(IpLiterals.parse(IPV4_MAPPED)).isPresent();
        }

        @Test
        @DisplayName("fe80::1%1 scoped with numeric scope is a genuine literal and still parses")
        void shouldParseScopedWithNumericScope() {
            assertThat(IpLiterals.parse("fe80::1%1")).isPresent();
        }

        /**
         * A named scope is resolved by the platform, not by the character guard, so this asserts
         * the guard admits a named scope rather than asserting anything about a particular name.
         *
         * <p>Getting the precondition right took two attempts and both failures are worth
         * recording. Hard-coding {@code %lo0} is green on macOS and red on the
         * {@code ubuntu-latest} runner that gates merge, where the loopback is {@code lo}.
         * Discovering merely the first interface is no better: the platform needs a
         * <em>scope id</em>, which only exists on an interface that carries an IPv6 link-local
         * address, so {@code fe80::1%en0} fails with {@code no scope_id found} on an interface
         * that has none — and on Linux the loopback carries {@code ::1/128} alone. Picking the
         * first interface therefore passes or fails according to which one happens to sort first,
         * which is an unpredictably red test rather than a reliably green one.
         *
         * <p>So the precondition is the real one: the first interface carrying an IPv6 link-local
         * address, skipped entirely where no interface has one, as on a runner with IPv6 disabled.
         */
        @Test
        @DisplayName("a named scope for an interface with a link-local address is a genuine literal and still parses")
        void shouldParseScopedWithNamedInterface() throws SocketException {
            final var scopedInterfaceName = NetworkInterface.networkInterfaces()
                    .filter(candidate -> candidate.inetAddresses()
                            .anyMatch(address -> address instanceof Inet6Address && address.isLinkLocalAddress()))
                    .map(NetworkInterface::getName)
                    .findFirst();
            assumeTrue(scopedInterfaceName.isPresent(), "no interface carries an IPv6 link-local address");

            assertThat(IpLiterals.parse("fe80::1%" + scopedInterfaceName.get())).isPresent();
        }
    }
}
