package org.maglez.eop.domain;

import java.util.Objects;

/**
 * The name a player chose for themselves.
 *
 * <p>Free text, unverified and not unique. There is no account system, so two
 * people may pick the same name and the humans on the call disambiguate them.
 *
 * <p>Unverified does not mean unvalidated (ADR-015). A display name is the only
 * attacker-controlled string in this story that other players are shown, so it
 * is bounded in length, refused when blank, and refused when it carries control
 * characters — a name containing a newline or a terminal escape sequence has no
 * legitimate use and several illegitimate ones.
 *
 * <p>Escaping is deliberately not done here. The value is stored as the user
 * typed it and escaped at the point of rendering, by Jackson for JSON and by
 * React for the DOM. Escaping on the way in would double-encode a perfectly
 * ordinary name containing an ampersand.
 *
 * @param value the name, already trimmed
 */
public record DisplayName(String value) {

    /** Longest name accepted, matching the {@code display_name} column width. */
    public static final int MAX_LENGTH = 40;

    /**
     * Rejects a name that must not be stored.
     *
     * @throws NullPointerException  if the value is null
     * @throws InvalidInputException if the value is blank, too long, or
     *                               contains control characters
     */
    public DisplayName {
        Objects.requireNonNull(value, "value is required");
        if (value.isBlank()) {
            throw new InvalidInputException("A display name must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new InvalidInputException("A display name is at most " + MAX_LENGTH + " characters, was " + value.length());
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new InvalidInputException("A display name must not contain control characters");
            }
        }
    }

    /**
     * Builds a display name from raw input, trimming surrounding whitespace.
     *
     * <p>Trimming happens here rather than in the caller so that a name pasted
     * with a trailing space is not stored as a different name from the same one
     * typed by hand.
     *
     * @param raw the submitted name, possibly padded, possibly null
     * @return the validated name
     * @throws NullPointerException     if the value is null
     * @throws InvalidInputException if the trimmed value is not acceptable
     */
    public static DisplayName of(final String raw) {
        Objects.requireNonNull(raw, "displayName is required");
        return new DisplayName(raw.strip());
    }
}
