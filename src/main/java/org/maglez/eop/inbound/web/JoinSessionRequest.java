package org.maglez.eop.inbound.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.maglez.eop.domain.DisplayName;

/**
 * The body of a request to join an existing session.
 *
 * <p>The join code is not here. It is in the path, because the facilitator's
 * workflow is pasting a link into a chat window and a link cannot carry a body.
 *
 * @param displayName the name the joining player will be known by
 */
@Schema(name = "JoinSessionRequest", description = "Takes a seat at the session identified by the path.")
public record JoinSessionRequest(
        @NotBlank
        @Size(max = DisplayName.MAX_LENGTH)
        String displayName) {
}
