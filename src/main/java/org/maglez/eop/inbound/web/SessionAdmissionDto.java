package org.maglez.eop.inbound.web;

import io.swagger.v3.oas.annotations.media.Schema;
import org.maglez.eop.application.port.in.SessionAdmission;

/**
 * The response to creating or joining a session.
 *
 * <p>One shape for both, because the two payloads are identical and two names for
 * one shape drift apart.
 *
 * <p>This is the only response in the API that carries a credential, and the only
 * time that credential is ever transmitted. The application stores its digest and
 * nothing else, so a client that loses the value cannot be sent it again — it can
 * only rejoin as a new player (ADR-015).
 *
 * @param playerToken the opaque credential, to be kept in session storage and sent
 *                    in the {@code X-EoP-Player-Token} header
 * @param playerId    which of the seated players the caller is, supplied so the
 *                    client does not have to guess by matching a non-unique name
 * @param session     the session as it now stands
 */
@Schema(
        name = "SessionAdmission",
        description = "A newly created or joined session, together with the caller's credential.")
public record SessionAdmissionDto(
        String playerToken,
        String playerId,
        SessionStateDto session) {

    /**
     * Converts an admission into its transport form.
     *
     * @param admission the application-layer result
     * @return the transport object
     */
    public static SessionAdmissionDto from(final SessionAdmission admission) {
        return new SessionAdmissionDto(
                admission.playerToken(),
                admission.playerId().toString(),
                SessionStateDto.from(admission.session()));
    }
}
