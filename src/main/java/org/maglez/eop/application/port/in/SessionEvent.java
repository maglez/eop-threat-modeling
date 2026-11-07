package org.maglez.eop.application.port.in;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A notification that something about a session changed.
 *
 * <p>Deliberately minimal. It says <em>that</em> the session changed and never
 * <em>what</em> it changed to, so that there is exactly one piece of code
 * producing session state and exactly one endpoint reporting it. A recipient
 * re-reads the state endpoint; it does not reconstruct state from a sequence of
 * these (ADR-014).
 *
 * <p>{@code occurredAt} is for display and for logs. It is not a cursor, because
 * there is no event history to resume from — the events are not persisted, and a
 * process restart empties the subscriber list and resets any counter, so an
 * identifier issued by one process names nothing in the next.
 *
 * <p>Pure domain-adjacent type: no Spring, no Jakarta, no persistence annotations.
 *
 * @param type what changed
 * @param sessionId the session that changed
 * @param occurredAt when the change was recorded
 */
public record SessionEvent(SessionEventType type, UUID sessionId, Instant occurredAt) {

    /**
     * Creates a session event.
     *
     * @throws NullPointerException if any argument is {@code null}
     */
    public SessionEvent {
        Objects.requireNonNull(type, "type is required");
        Objects.requireNonNull(sessionId, "sessionId is required");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
    }
}
