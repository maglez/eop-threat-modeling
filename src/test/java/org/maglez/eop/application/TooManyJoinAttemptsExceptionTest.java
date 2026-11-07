package org.maglez.eop.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The refusal raised when a caller has guessed too often.
 *
 * <p>The delay is carried on the exception rather than recomputed by the web layer,
 * because the throttle is the only thing that knows when the oldest failure in the
 * window expires. The web layer turns it into a {@code Retry-After} header, so a
 * delay that arrived as anything other than whole seconds would be silently
 * truncated there; keeping the duration intact here is what lets that conversion be
 * asserted once, at the boundary.
 */
@DisplayName("TooManyJoinAttemptsException")
class TooManyJoinAttemptsExceptionTest {

    @Test
    @DisplayName("carries the delay so the web layer can advertise it")
    void shouldCarryTheDelay() {
        final var refusal = new TooManyJoinAttemptsException(Duration.ofSeconds(37));

        assertThat(refusal.retryAfter()).isEqualTo(Duration.ofSeconds(37));
    }

    @Test
    @DisplayName("states the delay in seconds, the unit the header uses")
    void shouldStateTheDelayInSeconds() {
        final var refusal = new TooManyJoinAttemptsException(Duration.ofSeconds(37));

        assertThat(refusal).hasMessage("Too many join attempts; retry after 37 seconds");
    }

    @Test
    @DisplayName("rounds a sub-second delay down in the message but keeps it whole on the accessor")
    void shouldKeepTheDurationIntactEvenWhenTheMessageRoundsIt() {
        final var refusal = new TooManyJoinAttemptsException(Duration.ofMillis(1500));

        assertThat(refusal).hasMessage("Too many join attempts; retry after 1 seconds");
        assertThat(refusal.retryAfter()).isEqualTo(Duration.ofMillis(1500));
    }

    @Test
    @DisplayName("refuses to be raised without a delay, because a refusal with no advice is useless")
    void shouldRejectAMissingDelay() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TooManyJoinAttemptsException(null))
                .withMessageContaining("retryAfter");
    }
}
