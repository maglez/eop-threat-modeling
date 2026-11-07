package org.maglez.eop.outbound.scheduling;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.application.port.in.SweepExpiredSessionsPort;

/**
 * Unit tests for {@link ExpiredSessionSweepScheduler}.
 *
 * <p>The scheduler is a thin adapter: it delegates entirely to
 * {@link SweepExpiredSessionsPort}. The only contract to verify is that
 * the scheduled method calls {@code execute()} on the use case.
 */
@DisplayName("ExpiredSessionSweepScheduler")
class ExpiredSessionSweepSchedulerTest {

    private final SweepExpiredSessionsPort sweepPort = mock(SweepExpiredSessionsPort.class);
    private final ExpiredSessionSweepScheduler scheduler = new ExpiredSessionSweepScheduler(sweepPort);

    @Test
    @DisplayName("delegates to SweepExpiredSessionsPort when triggered")
    void shouldDelegateToPort() {
        scheduler.sweepExpiredSessions();

        verify(sweepPort).execute();
    }
}
