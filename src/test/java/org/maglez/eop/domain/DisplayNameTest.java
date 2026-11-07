package org.maglez.eop.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DisplayName}.
 *
 * <p>JUnit's own {@code @DisplayName} annotation collides with the type under
 * test, so it is written out in full here. Qualifying the annotation rather than
 * the domain type keeps the assertions — the part worth reading — uncluttered.
 */
@org.junit.jupiter.api.DisplayName("DisplayName")
class DisplayNameTest {

    @Test
    @org.junit.jupiter.api.DisplayName("trims surrounding whitespace, so a pasted name is the same name")
    void shouldTrimSurroundingWhitespace() {
        assertThat(DisplayName.of("  Ada Lovelace \n").value()).isEqualTo("Ada Lovelace");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("keeps a name that needs escaping rather than escaping it here")
    void shouldNotEscapeOnTheWayIn() {
        assertThat(DisplayName.of("Bell & Co <ops>").value()).isEqualTo("Bell & Co <ops>");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("rejects null")
    void shouldRejectNull() {
        assertThatNullPointerException()
                .isThrownBy(() -> DisplayName.of(null))
                .withMessageContaining("displayName");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("rejects a name that is only whitespace")
    void shouldRejectBlank() {
        assertThatExceptionOfType(InvalidInputException.class)
                .isThrownBy(() -> DisplayName.of("   "))
                .withMessageContaining("must not be blank");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("rejects a name one character longer than the column")
    void shouldRejectOverlongName() {
        final String tooLong = "x".repeat(DisplayName.MAX_LENGTH + 1);
        assertThatExceptionOfType(InvalidInputException.class)
                .isThrownBy(() -> DisplayName.of(tooLong))
                .withMessageContaining("at most 40 characters, was 41");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("accepts a name of exactly the maximum length")
    void shouldAcceptNameAtMaximumLength() {
        final String atLimit = "x".repeat(DisplayName.MAX_LENGTH);
        assertThat(DisplayName.of(atLimit).value()).hasSize(DisplayName.MAX_LENGTH);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("rejects an embedded newline, which no real name needs")
    void shouldRejectEmbeddedNewline() {
        assertThatExceptionOfType(InvalidInputException.class)
                .isThrownBy(() -> DisplayName.of("Ada\nLovelace"))
                .withMessageContaining("control characters");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("rejects a terminal escape sequence")
    void shouldRejectTerminalEscape() {
        assertThatExceptionOfType(InvalidInputException.class)
                .isThrownBy(() -> DisplayName.of("Ada\u001b[31m"))
                .withMessageContaining("control characters");
    }
}
