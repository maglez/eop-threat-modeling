package org.maglez.eop.inbound.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.Optional;
import org.maglez.eop.config.TrustedProxyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.maglez.eop.outbound.web.InMemoryJoinAttemptLimiter;

/**
 * Decides which address to treat as the caller's.
 *
 * <p>In the deployed topology Caddy is the only published entry point (ADR-017), so the
 * peer address the servlet container sees is Caddy's and the caller's own address only
 * survives in {@code X-Forwarded-For}. Caddy appends, so the last entry of that header is
 * the one Caddy wrote and everything before it is whatever the caller chose to send.
 *
 * <p>That reasoning is only valid if the peer actually is Caddy, and until EOP-26 nothing
 * checked. Reached without a proxy in front — the ordinary local run, or any peer inside the
 * container network — a caller could send the header itself and hand
 * {@link InMemoryJoinAttemptLimiter} whatever key it liked, changing it once per request so
 * that ten failures per minute were never counted against anything. ADR-019 relies on that
 * throttle as a primary security control, so the fix is not defence in depth but the
 * restoration of the control itself.
 *
 * <p>The header is therefore read only when the peer is on an explicit allow-list, and the
 * value it yields must itself be an IP literal — a proxy that wrote something else, or an
 * {@code unknown} placeholder, falls back to the peer rather than becoming a bucket of its
 * own. Both addresses are reduced to one canonical spelling, because two spellings of one
 * client would otherwise be two buckets.
 */
@Component
class ClientAddressResolver {

    private static final Logger LOG = LoggerFactory.getLogger(ClientAddressResolver.class);

    /**
     * Used when, and only when, the servlet container reports no peer at all, meaning
     * {@code getRemoteAddr()} returned null or blank.
     *
     * <p>It deliberately does not cover the other way an address can fail to resolve. A peer
     * that is present but is not an IP literal is returned as {@code peer.strip()}, so that
     * raw string — not this sentinel — becomes the throttle key. Read the name as "no peer
     * was reported", not as "the peer could not be understood"; ADR-021 records the
     * non-literal case as a known limitation.
     *
     * <p>It cannot collide with a real address, because {@link IpLiterals} would reject it,
     * and neither it nor any resolved address can collide with the limiter's own sentinels,
     * which are prefixed with U+0000: that character is not whitespace, so {@code strip()}
     * would not remove it, and a servlet container cannot produce it in a peer address.
     */
    private static final String UNKNOWN = "unknown";

    private static final String FORWARDED_FOR = "X-Forwarded-For";

    private final TrustedProxies trustedProxies;

    ClientAddressResolver(final TrustedProxyProperties properties) {
        Objects.requireNonNull(properties, "properties is required");
        this.trustedProxies = TrustedProxies.of(properties.trustedProxies());
        if (this.trustedProxies.isEmpty()) {
            LOG.info("No trusted proxies configured; X-Forwarded-For will be ignored and the peer address used instead");
        }
        else {
            LOG.info("Trusting forwarding headers from {}", properties.trustedProxies());
        }
    }

    /**
     * Identifies the caller of a request.
     *
     * @param request the request whose caller is being identified
     * @return the caller's address, in a stable spelling suitable for use as a throttle key
     */
    String of(final HttpServletRequest request) {
        Objects.requireNonNull(request, "request is required");
        final var peer = peerAddress(request);
        if (!trustedProxies.includes(peer)) {
            return peer;
        }
        return forwardedFor(request).orElse(peer);
    }

    private static String peerAddress(final HttpServletRequest request) {
        final var peer = request.getRemoteAddr();
        if (peer == null || peer.isBlank()) {
            return UNKNOWN;
        }
        return IpLiterals.canonical(peer).orElseGet(peer::strip);
    }

    private static Optional<String> forwardedFor(final HttpServletRequest request) {
        final var header = request.getHeader(FORWARDED_FOR);
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        final var hops = header.split(",");
        return IpLiterals.canonical(hops[hops.length - 1]);
    }
}
