package org.maglez.eop.application.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.maglez.eop.application.port.in.ResolvePlayerPort;
import org.maglez.eop.application.port.in.SweepExpiredSessionsPort;
import org.maglez.eop.application.port.out.SessionRepository;

/**
 * Sweeps expired sessions out of the database.
 *
 * <p>A session is expired when its {@code expires_at} timestamp is before the
 * current instant. The sweep transitions each such session to
 * {@link org.maglez.eop.domain.SessionStatus#ABANDONED} and then deletes it;
 * the {@code ON DELETE CASCADE} on {@code fk_player_game_session} removes the
 * player rows automatically.
 *
 * <p>This use case is the policy layer for the sweep. The scheduling trigger
 * lives in the adapter layer ({@code adapter/scheduling/}), which calls
 * {@link #execute()} on the configured interval.
 *
 * <p>The sweep is not a real-time revocation mechanism —
 * {@link ResolvePlayerPort} is the real-time guard. The sweep is a
 * housekeeping operation that removes rows that are already unreachable.
 */
public class SweepExpiredSessionsService implements SweepExpiredSessionsPort {

    private static final Logger LOG = LoggerFactory.getLogger(SweepExpiredSessionsService.class);

    private final SessionRepository sessionRepository;
    private final Clock clock;

    /**
     * Creates the use case.
     *
     * @param sessionRepository the port used to find and delete expired sessions
     * @param clock the clock used to determine the current instant — injected so
     *     that tests can fix the instant and avoid wall-clock races
     */
    public SweepExpiredSessionsService(final SessionRepository sessionRepository, final Clock clock) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * Finds all sessions whose {@code expires_at} is in the past, transitions each
     * to {@code ABANDONED}, and deletes it.
     *
     * <p>A failure on one session is caught and logged; the sweep continues with
     * the remaining sessions. The failed session will be retried on the next cycle.
     */
    public void execute() {
        final Instant now = Instant.now(clock);
        final List<UUID> expired = sessionRepository.findExpiredSessionIds(now);

        if (expired.isEmpty()) {
            LOG.debug("Session sweep: no expired sessions found");
            return;
        }

        LOG.info("Session sweep: found {} expired session(s) — transitioning to ABANDONED and deleting",
                expired.size());

        int deleted = 0;
        for (final UUID sessionId : expired) {
            try {
                sessionRepository.abandonAndDelete(sessionId);
                deleted++;
                LOG.info("Session sweep: abandoned and deleted session {}", sessionId);
            }
            catch (final RuntimeException ex) {
                // Log and continue — a single failure must not abort the whole sweep.
                LOG.warn("Session sweep: failed to abandon/delete session {} — will retry next cycle",
                        sessionId, ex);
            }
        }

        LOG.info("Session sweep: deleted {}/{} expired session(s)", deleted, expired.size());
    }
}
