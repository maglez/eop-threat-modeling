package org.maglez.eop.inbound.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.maglez.eop.domain.GameSession;

/**
 * Everything a client needs to render a session, as stored in the database.
 *
 * <p>This is the only shape in which state is published. The event stream
 * announces that something changed and never what it changed to, so there is one
 * producer of state and one endpoint reporting it, and the path a client takes
 * after a reconnect is the same path it took on first load (ADR-014).
 *
 * <p>Players appear in ascending seat order because that is the order play moves
 * around the table. A client must not re-sort them, and in particular must not
 * order them by anything derived from when they joined.
 *
 * @param sessionId identifier that appears in the shareable URL; not a secret
 * @param joinCode  the code to share, returned only to a caller already recognised
 * @param status    where the session is in its lifecycle
 * @param players   the seated players, ascending by seat order
 * @param createdAt when the lobby opened, ISO-8601 in UTC
 * @param updatedAt when the session last changed, ISO-8601 in UTC
 */
@Schema(name = "SessionState", description = "A session and its players as stored in the database.")
public record SessionStateDto(
        String sessionId,
        String joinCode,
        String status,
        List<PlayerDto> players,
        String createdAt,
        String updatedAt) {

    /**
     * Copies the players defensively so the state cannot be mutated after construction.
     *
     * <p>A record is only a value if every component is one. Without this the list handed in stays
     * shared with whoever built it, so a caller could reorder the table after the object claiming to
     * describe it had been created — and seat order is the one thing this shape promises
     * (ADR-014). {@link java.util.List#copyOf} also refuses a null list, which is the right answer
     * for a session state with no players at all: a lobby always seats its facilitator.
     *
     * @throws NullPointerException if the players are null
     */
    public SessionStateDto {
        players = List.copyOf(players);
    }

    /**
     * Converts a domain session into its transport form.
     *
     * @param session the domain aggregate
     * @return the transport object
     */
    public static SessionStateDto from(final GameSession session) {
        return new SessionStateDto(
                session.sessionId().toString(),
                session.joinCode().value(),
                session.status().name(),
                session.players().stream().map(PlayerDto::from).toList(),
                session.createdAt().toString(),
                session.updatedAt().toString());
    }
}
