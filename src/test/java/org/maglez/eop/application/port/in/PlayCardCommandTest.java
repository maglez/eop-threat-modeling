package org.maglez.eop.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the command that carries a play in from the edge of the application.
 *
 * <p>This record is a security boundary rather than a convenience, so the assertions worth making
 * are about what it refuses and what it normalises. Two of them are about fields that are not here
 * at all: there is no seat and no player identifier, because both are derived from the identity
 * token, and no suit and no rank, because both are read from the deck. Those omissions cannot be
 * asserted directly — a test cannot check that a component is absent — so they are held by the
 * compiler instead, and what is left to test is the handling of the components that do exist.
 *
 * <p>The component list is the interesting one. A play that links no threat is an ordinary
 * outcome rather than an error, so an absent list has to mean "none" and not "invalid", and the
 * list has to be copied on the way in because a trick play rejects a null element and a caller
 * who kept a reference could otherwise add one after the command was built.
 */
@DisplayName("PlayCardCommand")
class PlayCardCommandTest {

    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-7000-8000-0000000000c1");

    private static final UUID CARD_ID = UUID.fromString("00000000-0000-7000-8000-0000000000c2");

    @Test
    @DisplayName("treats absent components as none rather than as a missing value")
    void shouldNormaliseAbsentComponentsToEmpty() {
        final var command = new PlayCardCommand(SESSION_ID, "opaque", CARD_ID, false, null, null);

        assertThat(command.components())
                .as("a play that links no threat is ordinary, so null has to mean none")
                .isEmpty();
        assertThat(command.notes())
                .as("notes are optional and stay absent rather than becoming empty text")
                .isNull();
    }

    @Test
    @DisplayName("copies the components it was given, so a later change cannot reach it")
    void shouldCopyTheComponentsGiven() {
        final var supplied = new ArrayList<String>();
        supplied.add("Browser");
        final var command = new PlayCardCommand(SESSION_ID, "opaque", CARD_ID, true, supplied, "a note");

        supplied.add("Injected later");

        assertThat(command.components())
                .as("the command took a copy, so the caller's list is no longer its list")
                .containsExactly("Browser");
        assertThat(command.threatLinked()).isTrue();
        assertThat(command.notes()).isEqualTo("a note");
    }

    @Test
    @DisplayName("hands back a list nothing outside it can modify")
    void shouldReturnAnUnmodifiableComponentList() {
        final var command =
                new PlayCardCommand(SESSION_ID, "opaque", CARD_ID, true, List.of("Browser"), null);

        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> command.components().add("Injected later"));
    }

    @Test
    @DisplayName("refuses a command with no session")
    void shouldRefuseANullSession() {
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new PlayCardCommand(null, "opaque", CARD_ID, false, List.of(), null))
                .withMessageContaining("sessionId is required");
    }

    @Test
    @DisplayName("refuses a command with no card")
    void shouldRefuseANullCard() {
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new PlayCardCommand(SESSION_ID, "opaque", null, false, List.of(), null))
                .withMessageContaining("cardId is required");
    }

    /**
     * The one null this record accepts on purpose.
     *
     * <p>A missing token and an unrecognised token are answered identically further in, so that
     * neither tells a caller whether the session has that member. Rejecting null here would turn
     * the missing case into a different failure and give away the distinction that the identical
     * answers exist to hide.
     */
    @Test
    @DisplayName("accepts a command with no token, because the refusal belongs further in")
    void shouldAcceptAMissingToken() {
        final var command = new PlayCardCommand(SESSION_ID, null, CARD_ID, false, List.of(), null);

        assertThat(command.playerToken()).isNull();
        assertThat(command.sessionId()).isEqualTo(SESSION_ID);
        assertThat(command.cardId()).isEqualTo(CARD_ID);
    }
}
