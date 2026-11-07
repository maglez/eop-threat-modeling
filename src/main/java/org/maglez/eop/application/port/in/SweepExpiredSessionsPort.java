package org.maglez.eop.application.port.in;

/**
 * Inbound port for the sweepexpiredsessions operation.
 *
 * <p>Implemented by {@code SweepExpiredSessionsService} in the application service layer. A driving
 * adapter calls the method here; the service orchestrates domain and outbound ports.
 */
public interface SweepExpiredSessionsPort {

    /**
     * Finds all sessions whose {@code expires_at} is in the past, transitions each
     * to {@code ABANDONED}, and deletes it.
     *
     * <p>A failure on one session is caught and logged; the sweep continues with
     * the remaining sessions. The failed session will be retried on the next cycle.
     
     */
    void execute();
}
