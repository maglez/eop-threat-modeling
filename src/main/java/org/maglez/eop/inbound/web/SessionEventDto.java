package org.maglez.eop.inbound.web;

import io.swagger.v3.oas.annotations.media.Schema;
import org.maglez.eop.application.port.in.SessionEvent;

/**
 * A change notification as it appears on the event stream.
 *
 * <p>Deliberately minimal. It says <em>that</em> the session changed and never
 * <em>what</em> it changed to, so there is exactly one code path producing state and
 * exactly one endpoint reporting it. A client that receives one of these re-reads
 * {@code GET /api/v1/sessions/{sessionId}} (ADR-014).
 *
 * <p>Carrying state here would be an obvious convenience and a real trap: two
 * producers of the same information drift, and the one used on the recovery path
 * would be the one nobody exercises.
 *
 * @param type       the event name, matching the contract's enumeration
 * @param sessionId  the session that changed
 * @param occurredAt when the change was recorded, ISO-8601 in UTC. Not a cursor:
 *                   there is no event history to resume from, so nothing can be
 *                   requested "since" this value.
 */
@Schema(name = "SessionEvent", description = "A notification that a session changed. Carries no state.")
public record SessionEventDto(String type, String sessionId, String occurredAt) {

    /**
     * Converts an application event into its wire form.
     *
     * @param event the event to render
     * @return the wire representation
     */
    public static SessionEventDto from(final SessionEvent event) {
        return new SessionEventDto(
                event.type().wireName(),
                event.sessionId().toString(),
                event.occurredAt().toString());
    }
}
