package org.maglez.eop.inbound.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.maglez.eop.domain.JoinCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.web.servlet.MockMvc;
import org.maglez.eop.outbound.web.InMemoryJoinAttemptLimiter;

/**
 * End-to-end proof that rotating {@code X-Forwarded-For} does not reset the join
 * throttle when the peer is not a trusted proxy.
 *
 * <p>Before EOP-26 the header was read unconditionally, so a caller could supply
 * a different value on every request and be handed a fresh, empty bucket each time.
 * With ten failures allowed per address per minute, and a join code of forty bits,
 * an attacker who rotated the header could make unlimited guesses from a single
 * machine, working through the keyspace at a rate the limiter exists to deny.
 * ADR-019 treats the throttle as a security control that the code's length bounds
 * but does not replace, so this is not defence in depth — it is the control itself.
 *
 * <p>The test drives more than {@code MAX_FAILURES_PER_ADDRESS} failing join
 * attempts while rotating the header on every request. If the fix is absent the
 * throttle never fires; if it is present the eleventh attempt is refused with
 * HTTP 429. Each attempt uses a distinct join code so the per-code counter cannot
 * be what triggers the refusal — only the per-address counter can.
 *
 * <p>The mirror-image case uses {@code @SpringBootTest(properties)} to configure
 * {@code 127.0.0.1/32} as a trusted proxy. MockMvc's peer is always
 * {@code 127.0.0.1}, so the header is now believed, and rotating it produces
 * independent buckets — proving that the allow-list is actually consulted rather
 * than the header being ignored unconditionally.
 *
 * <p>Each nested class carries its own {@code @SpringBootTest} with a dedicated
 * in-memory database so it gets its own {@link InMemoryJoinAttemptLimiter} singleton,
 * isolated from the shared default context. {@code @DirtiesContext(AFTER_CLASS)} marks
 * each context dirty after the class finishes so it is not retained in the cache.
 */
@DisplayName("ForwardedFor throttle-bypass integration")
class ForwardedForThrottleBypassIntegrationTest {

    /** Mirrors {@code MAX_FAILURES_PER_ADDRESS}, which is private. */
    private static final int MAX_FAILURES_PER_ADDRESS = 10;

    private static final String SESSIONS_PATH = "/api/v1/sessions";

    private static final String FORWARDED_FOR = "X-Forwarded-For";

    /** Distinguishes addresses and codes invented by this class from those of other tests. */
    private static final AtomicInteger SERIAL = new AtomicInteger(50_000);

    /** A well-formed code no session holds, unique to the calling test. */
    static String unheldCode() {
        final var alphabet = JoinCode.ALPHABET;
        var remaining = SERIAL.incrementAndGet();
        final var drawn = new StringBuilder("YYYYYYYY");
        for (int position = 0; position < JoinCode.LENGTH && remaining > 0; position++) {
            drawn.setCharAt(position, alphabet.charAt(remaining % alphabet.length()));
            remaining /= alphabet.length();
        }
        return drawn.toString();
    }

    static String nameRequest(final String name) {
        return "{\"displayName\":\"" + name + "\"}";
    }

    /**
     * Scenario 4: rotating the header does not reset the throttle when the peer is not trusted.
     *
     * <p>Uses a dedicated in-memory database ({@code bypass-test}) so that this class
     * gets its own {@link InMemoryJoinAttemptLimiter} singleton, isolated from the
     * shared default context. {@code @DirtiesContext(classMode = AFTER_CLASS)} marks
     * the context dirty after the test completes so the dedicated context is not
     * retained in the cache.
     */
    @SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:bypass-test;DB_CLOSE_DELAY=-1")
    @AutoConfigureMockMvc
    @DirtiesContext(classMode = ClassMode.AFTER_CLASS)
    @Nested
    @DisplayName("Scenario 4 — rotation attack no longer yields a fresh bucket")
    class RotationAttackBlocked {

        @Autowired
        private MockMvc mockMvc;

        @Test
        @DisplayName("rotating X-Forwarded-For does not reset the throttle when the peer is not trusted")
        void shouldThrottleEvenWhenHeaderIsRotated() throws Exception {
            // Arrange: each attempt uses a distinct forwarded address and a distinct code,
            // so neither the per-code counter nor a single forwarded address can be blamed.
            // The only thing that accumulates is the real peer (127.0.0.1 in MockMvc).
            for (int attempt = 1; attempt <= MAX_FAILURES_PER_ADDRESS; attempt++) {
                final var rotatedHeader = "203.0.113." + attempt;
                final var code = unheldCode();
                mockMvc.perform(post(SESSIONS_PATH + "/" + code + "/players")
                                .header(FORWARDED_FOR, rotatedHeader)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(nameRequest("Attacker")))
                        .andExpect(status().isNotFound());
            }

            // Act: one more attempt with yet another rotated header and a fresh code.
            final var finalHeader = "203.0.113.99";
            final var finalCode = unheldCode();

            // Assert: the throttle fires on the real peer, not on the rotated header.
            mockMvc.perform(post(SESSIONS_PATH + "/" + finalCode + "/players")
                            .header(FORWARDED_FOR, finalHeader)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(nameRequest("Attacker")))
                    .andExpect(status().isTooManyRequests());
        }
    }

    /**
     * Mirror-image: with a trusted proxy configured, rotating the header produces
     * independent buckets, proving the allow-list is consulted rather than the
     * header being ignored unconditionally.
     *
     * <p>Uses {@code @DirtiesContext(classMode = BEFORE_CLASS)} to get a fresh limiter singleton
     * before this class runs.
     */
    @SpringBootTest(properties = "eop.web.trusted-proxies=127.0.0.1/32")
    @AutoConfigureMockMvc
    @DirtiesContext(classMode = ClassMode.BEFORE_CLASS)
    @Nested
    @DisplayName("Scenario 2 end-to-end — header honoured from a configured proxy")
    class HeaderHonouredFromTrustedProxy {

        @Autowired
        private MockMvc trustedMockMvc;

        @Test
        @DisplayName("rotating X-Forwarded-For produces independent buckets when the peer is trusted")
        void shouldProduceIndependentBucketsWhenPeerIsTrusted() throws Exception {
            // Arrange: drive MAX_FAILURES_PER_ADDRESS failures, each with a distinct
            // forwarded address and a distinct code. Because the peer (127.0.0.1) is
            // trusted, the resolver uses the forwarded address as the bucket key.
            // Each distinct forwarded address gets its own fresh bucket, so no single
            // bucket reaches the limit.
            for (int attempt = 1; attempt <= MAX_FAILURES_PER_ADDRESS; attempt++) {
                final var rotatedHeader = "198.51.100." + attempt;
                final var code = unheldCode();
                trustedMockMvc.perform(post(SESSIONS_PATH + "/" + code + "/players")
                                .header(FORWARDED_FOR, rotatedHeader)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(nameRequest("Attacker")))
                        .andExpect(status().isNotFound());
            }

            // Act + Assert: one more attempt with yet another fresh forwarded address
            // is still a 404 (not throttled), because each address has only one failure.
            final var freshHeader = "198.51.100.200";
            final var freshCode = unheldCode();
            trustedMockMvc.perform(post(SESSIONS_PATH + "/" + freshCode + "/players")
                            .header(FORWARDED_FOR, freshHeader)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(nameRequest("Attacker")))
                    .andExpect(status().isNotFound());
        }
    }

    /**
     * Availability direction: one attacker must not lock out everybody else.
     *
     * <p>This is the test that {@code SessionControllerIntegrationTest.ThrottlingGuesses
     * .shouldThrottlePerAddress} used to cover before EOP-26 removed it. That test
     * saturated one address and then asserted a different address still succeeded. The
     * replacement in {@code HeaderHonouredFromTrustedProxy} drives ten <em>distinct</em>
     * addresses with one failure each, which proves the security direction (a saturated
     * bucket is inescapable) but never saturates a single address before checking that a
     * different one still succeeds — so the availability direction was lost.
     *
     * <p>The availability direction is the more likely real-world failure mode: if Caddy's
     * address ever drifts, the header is ignored and every player collapses into one shared
     * bucket, so ten failed joins per minute globally would lock out the eleventh legitimate
     * player. This test pins that property explicitly.
     *
     * <p>Uses a dedicated in-memory database ({@code isolation-test}) so that this class
     * gets its own {@link InMemoryJoinAttemptLimiter} singleton, isolated from the other
     * nested classes. {@code @DirtiesContext(classMode = AFTER_CLASS)} marks the context
     * dirty after the class finishes so it is not retained in the cache.
     */
    @SpringBootTest(properties = {
        "eop.web.trusted-proxies=127.0.0.1/32",
        "spring.datasource.url=jdbc:h2:mem:isolation-test;DB_CLOSE_DELAY=-1"
    })
    @AutoConfigureMockMvc
    @DirtiesContext(classMode = ClassMode.AFTER_CLASS)
    @Nested
    @DisplayName("per-address isolation under a trusted proxy")
    class PerAddressIsolationUnderTrustedProxy {

        /** Mirrors the production constant — the address that will be saturated. */
        private static final String ATTACKER_ADDRESS = "198.51.100.1";

        /** A different address that must not be affected by the attacker's exhausted bucket. */
        private static final String BYSTANDER_ADDRESS = "198.51.100.2";

        @Autowired
        private MockMvc isolationMockMvc;

        @Test
        @DisplayName("saturating one forwarded address does not throttle a different forwarded address")
        void shouldNotThrottleADifferentAddressWhenOneIsSaturated() throws Exception {
            // Arrange: exhaust the bucket for ATTACKER_ADDRESS. Each attempt uses a
            // distinct code so the per-code counter cannot be what triggers the refusal.
            for (int attempt = 1; attempt <= MAX_FAILURES_PER_ADDRESS; attempt++) {
                isolationMockMvc.perform(post(SESSIONS_PATH + "/" + unheldCode() + "/players")
                                .header(FORWARDED_FOR, ATTACKER_ADDRESS)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(nameRequest("Attacker")))
                        .andExpect(status().isNotFound());
            }

            // Act: one more attempt from the attacker's address — must be refused.
            isolationMockMvc.perform(post(SESSIONS_PATH + "/" + unheldCode() + "/players")
                            .header(FORWARDED_FOR, ATTACKER_ADDRESS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(nameRequest("Attacker")))
                    .andExpect(status().isTooManyRequests());

            // Assert: a completely different address still gets the normal not-found
            // response, not the throttle response. One attacker must not lock out everybody.
            isolationMockMvc.perform(post(SESSIONS_PATH + "/" + unheldCode() + "/players")
                            .header(FORWARDED_FOR, BYSTANDER_ADDRESS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(nameRequest("Bystander")))
                    .andExpect(status().isNotFound());
        }
    }
}
