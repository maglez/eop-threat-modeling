package org.maglez.eop.inbound.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.maglez.eop.domain.DisplayName;

/**
 * The body of a request to open a new session.
 *
 * <p>Bean validation here is the outer of two checks. It rejects the obvious cases
 * with a field-level message a form can render, and {@link DisplayName} then
 * revalidates in the domain, where control characters are also refused. Two checks
 * rather than one because the domain type must be safe to construct from anywhere,
 * not only from a validated request body.
 *
 * @param displayName the name the facilitator will be known by
 */
@Schema(name = "CreateSessionRequest", description = "Opens a new session in the lobby state.")
public record CreateSessionRequest(
        @NotBlank
        @Size(max = DisplayName.MAX_LENGTH)
        String displayName) {
}
