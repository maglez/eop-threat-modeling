package org.maglez.eop.inbound.web;

import java.util.List;

/**
 * The set of peer addresses whose forwarding headers this application believes.
 *
 * <p>An empty set trusts nobody, and that is the default (ADR-021). Running the application
 * directly — {@code ./mvnw spring-boot:run}, an integration test, a container reached from
 * a sibling container — therefore ignores {@code X-Forwarded-For} entirely and keys the
 * join throttle on the real peer. That ordering matters: before EOP-26 the header was read
 * unconditionally, so any caller could nominate its own rate-limiter bucket and rotate it
 * once per guess. ADR-019 calls that throttle a primary security control rather than
 * defence in depth, so leaving the decision implicit was not an option.
 *
 * <p>Entries are literal addresses, which mean that host alone, or CIDR blocks. A block
 * whose host bits are not clear is rejected rather than quietly masked, because masking
 * turns the plausible typo {@code 10.0.0.5/8} into a rule that trusts sixteen million
 * hosts. Malformed entries are rejected for the same reason: skipping them would leave an
 * application that starts happily and trusts less than its configuration claims, and the
 * only evidence would be a log line nobody reads. A bad allow-list fails the bean, and a
 * failed bean fails startup, which is noticed immediately.
 */
final class TrustedProxies {

    private static final TrustedProxies NONE = new TrustedProxies(List.of());

    private static final int BITS_PER_BYTE = 8;

    private final List<Range> ranges;

    private TrustedProxies(final List<Range> ranges) {
        this.ranges = ranges;
    }

    /**
     * Builds an allow-list from configured text.
     *
     * @param specifications literal addresses or CIDR blocks; {@code null}, empty and blank
     *                       entries are treated as an absent allow-list
     * @return the allow-list
     * @throws IllegalArgumentException if any entry is not a literal address or a CIDR
     *                                  block with its host bits clear
     */
    static TrustedProxies of(final List<String> specifications) {
        if (specifications == null) {
            return NONE;
        }
        final var ranges = specifications.stream()
                .filter(specification -> specification != null && !specification.isBlank())
                .map(TrustedProxies::parseRange)
                .toList();
        return ranges.isEmpty() ? NONE : new TrustedProxies(ranges);
    }

    /** An allow-list that trusts nobody. */
    static TrustedProxies none() {
        return NONE;
    }

    boolean isEmpty() {
        return ranges.isEmpty();
    }

    /**
     * Decides whether a peer may be believed.
     *
     * @param address a peer address as text
     * @return whether the address falls inside any configured range; always false when
     *         nothing is configured, and false for anything that is not an IP literal
     */
    boolean includes(final String address) {
        if (ranges.isEmpty()) {
            return false;
        }
        return IpLiterals.parse(address)
                .filter(bytes -> ranges.stream().anyMatch(range -> range.contains(bytes)))
                .isPresent();
    }

    private static Range parseRange(final String specification) {
        final var text = specification.strip();
        final var slash = text.indexOf('/');
        final var literal = slash < 0 ? text : text.substring(0, slash);
        final var network = IpLiterals.parse(literal).orElseThrow(() -> reject(specification, "not an IP address"));
        final var addressBits = network.length * BITS_PER_BYTE;
        if (slash < 0) {
            return new Range(network, addressBits);
        }
        final var prefixBits = parsePrefixBits(text.substring(slash + 1), addressBits, specification);
        if (!isNetworkAddress(network, prefixBits)) {
            throw reject(specification, "a CIDR block must have its host bits clear");
        }
        return new Range(network, prefixBits);
    }

    private static int parsePrefixBits(final String text, final int addressBits, final String specification) {
        final int prefixBits;
        try {
            prefixBits = Integer.parseInt(text);
        }
        catch (final NumberFormatException notANumber) {
            throw reject(specification, "the prefix length is not a number");
        }
        if (prefixBits < 0 || prefixBits > addressBits) {
            throw reject(specification, "the prefix length must be between 0 and " + addressBits);
        }
        return prefixBits;
    }

    private static boolean isNetworkAddress(final byte[] network, final int prefixBits) {
        for (int bit = prefixBits; bit < network.length * BITS_PER_BYTE; bit++) {
            final var mask = 1 << (BITS_PER_BYTE - 1 - bit % BITS_PER_BYTE);
            if ((network[bit / BITS_PER_BYTE] & mask) != 0) {
                return false;
            }
        }
        return true;
    }

    private static IllegalArgumentException reject(final String specification, final String reason) {
        return new IllegalArgumentException(
                "eop.web.trusted-proxies entry '" + specification + "' is invalid: " + reason);
    }

    /**
     * One entry of the allow-list. The array is copied on the way in, so a caller that
     * kept a reference to the bytes it passed cannot widen a range after startup validated
     * it.
     */
    private record Range(byte[] network, int prefixBits) {

        private Range {
            network = network.clone();
        }

        /**
         * Copies on the way out as well, so the invariant enforces itself rather than
         * depending on this record staying private and read-only. The accessor a record
         * generates would hand back the internal reference, undoing the constructor's clone
         * the moment anything outside this record read it; a range widened after startup
         * validated it is a silently broader trust boundary. {@link #contains(byte[])} reads
         * the field directly, so the copy is not made on the request path.
         *
         * @return a copy of the network address bytes
         */
        public byte[] network() {
            return network.clone();
        }

        /**
         * A candidate of a different length is a different address family, and an IPv4 rule
         * must not match an IPv6 peer.
         */
        boolean contains(final byte[] candidate) {
            if (candidate.length != network.length) {
                return false;
            }
            final var wholeBytes = prefixBits / BITS_PER_BYTE;
            for (int index = 0; index < wholeBytes; index++) {
                if (candidate[index] != network[index]) {
                    return false;
                }
            }
            final var remainingBits = prefixBits % BITS_PER_BYTE;
            if (remainingBits == 0) {
                return true;
            }
            final var mask = (0xFF << (BITS_PER_BYTE - remainingBits)) & 0xFF;
            return (candidate[wholeBytes] & mask) == (network[wholeBytes] & mask);
        }
    }
}
