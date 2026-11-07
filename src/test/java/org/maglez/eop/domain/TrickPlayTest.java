package org.maglez.eop.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.maglez.eop.domain.DeckFixture.card;
import static org.maglez.eop.domain.TrickPlayBuilder.aTrickPlay;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("TrickPlay")
class TrickPlayTest {

    @Nested
    @DisplayName("rejects a malformed play at construction")
    class Validation {

        @Test
        @DisplayName("null play identifier")
        void shouldRejectNullTrickPlayId() {
            assertThatNullPointerException()
                    .isThrownBy(() -> aTrickPlay().withTrickPlayId(null).build())
                    .withMessageContaining("trickPlayId");
        }

        @Test
        @DisplayName("null player identifier")
        void shouldRejectNullPlayerId() {
            assertThatNullPointerException()
                    .isThrownBy(() -> aTrickPlay().withPlayerId(null).build())
                    .withMessageContaining("playerId");
        }

        @Test
        @DisplayName("null card")
        void shouldRejectNullCard() {
            assertThatNullPointerException()
                    .isThrownBy(() -> aTrickPlay().withCard(null).build())
                    .withMessageContaining("card");
        }

        @Test
        @DisplayName("null play time")
        void shouldRejectNullPlayedAt() {
            assertThatNullPointerException()
                    .isThrownBy(() -> aTrickPlay().withPlayedAt(null).build())
                    .withMessageContaining("playedAt");
        }

        @Test
        @DisplayName("null component list, because absent components are an empty list")
        void shouldRejectNullComponents() {
            assertThatNullPointerException()
                    .isThrownBy(() -> aTrickPlay().withComponents(null).build())
                    .withMessageContaining("components");
        }

        @Test
        @DisplayName("a seat below zero")
        void shouldRejectNegativeSeat() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> aTrickPlay().withSeatOrder(-1).build());
        }

        @Test
        @DisplayName("a seat beyond the table, because there are only six")
        void shouldRejectSeatBeyondTable() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> aTrickPlay().withSeatOrder(GameSession.MAXIMUM_PLAYERS).build());
        }

        @Test
        @DisplayName("more components than the bound allows")
        void shouldRejectTooManyComponents() {
            final List<String> tooMany = IntStream.rangeClosed(0, TrickPlay.MAX_COMPONENTS)
                    .mapToObj(index -> "Component " + index)
                    .toList();

            assertThatExceptionOfType(InvalidInputException.class)
                    .isThrownBy(() -> aTrickPlay().withComponents(tooMany).build());
        }

        @Test
        @DisplayName("a null component name")
        void shouldRejectNullComponentName() {
            assertThatNullPointerException()
                    .isThrownBy(() -> aTrickPlay().withComponents(Arrays.asList("Payments API", null)).build());
        }

        @Test
        @DisplayName("a blank component name, because an empty box names nothing")
        void shouldRejectBlankComponentName() {
            assertThatExceptionOfType(InvalidInputException.class)
                    .isThrownBy(() -> aTrickPlay().withComponents(List.of("   ")).build());
        }

        @Test
        @DisplayName("an over-long component name")
        void shouldRejectOverLongComponentName() {
            final String tooLong = "c".repeat(TrickPlay.MAX_COMPONENT_NAME_LENGTH + 1);

            assertThatExceptionOfType(InvalidInputException.class)
                    .isThrownBy(() -> aTrickPlay().withComponents(List.of(tooLong)).build());
        }

        @Test
        @DisplayName("an over-long note")
        void shouldRejectOverLongNotes() {
            final String tooLong = "n".repeat(TrickPlay.MAX_NOTES_LENGTH + 1);

            assertThatExceptionOfType(InvalidInputException.class)
                    .isThrownBy(() -> aTrickPlay().withNotes(tooLong).build());
        }
    }

    @Nested
    @DisplayName("normalises what the player typed")
    class Normalisation {

        @Test
        @DisplayName("strips surrounding whitespace from each component name")
        void shouldStripComponentNames() {
            final TrickPlay play = aTrickPlay().withComponents(List.of("  Payments API  ")).build();

            assertThat(play.components()).containsExactly("Payments API");
        }

        @Test
        @DisplayName("treats a blank note as no note at all")
        void shouldTreatBlankNoteAsAbsent() {
            final TrickPlay play = aTrickPlay().withNotes("   ").build();

            assertThat(play.notes()).isNull();
            assertThat(play.notesIfGiven()).isEmpty();
        }

        @Test
        @DisplayName("strips a note that was given")
        void shouldStripNote() {
            final TrickPlay play = aTrickPlay().withNotes("  The token is never checked.  ").build();

            assertThat(play.notesIfGiven()).contains("The token is never checked.");
        }

        @Test
        @DisplayName("exposes an unmodifiable component list")
        void shouldExposeUnmodifiableComponents() {
            final TrickPlay play = aTrickPlay().build();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> play.components().clear());
        }
    }

    @Nested
    @DisplayName("accepts a play that links no threat, because that is a legal outcome and not an error")
    class UnlinkedThreat {

        @Test
        @DisplayName("a play with no threat linked is a valid play")
        void shouldAcceptUnlinkedThreat() {
            final TrickPlay play = aTrickPlay().withThreatLinked(false).withComponents(List.of()).build();

            assertThat(play.threatLinked()).isFalse();
            assertThat(play.components()).isEmpty();
        }

        @Test
        @DisplayName("and still competes for the trick, because failing to link scores nothing rather than forfeiting")
        void shouldStillCompeteForTheTrick() {
            final Card led = card(StrideCategory.SPOOFING, Rank.KING);
            final TrickPlay play = aTrickPlay().withCard(led).withThreatLinked(false).build();

            assertThat(play.canTakeTrick(StrideCategory.SPOOFING)).isTrue();
        }

        @Test
        @DisplayName("a linked threat naming no component is accepted, because a live session must not be blocked")
        void shouldAcceptLinkedThreatWithoutComponents() {
            final TrickPlay play = aTrickPlay().withThreatLinked(true).withComponents(List.of()).build();

            assertThat(play.threatLinked()).isTrue();
            assertThat(play.components()).isEmpty();
        }
    }

    @Nested
    @DisplayName("knows whether its card can take the trick")
    class TakingTheTrick {

        @Test
        @DisplayName("a card of the led suit can")
        void ledSuitCanTake() {
            final TrickPlay play = aTrickPlay().withCard(card(StrideCategory.REPUDIATION, Rank.FOUR)).build();

            assertThat(play.canTakeTrick(StrideCategory.REPUDIATION)).isTrue();
        }

        @Test
        @DisplayName("a trump can, whatever was led")
        void trumpCanTake() {
            final TrickPlay play = aTrickPlay()
                    .withCard(card(StrideCategory.ELEVATION_OF_PRIVILEGE, Rank.TWO))
                    .build();

            assertThat(play.canTakeTrick(StrideCategory.REPUDIATION)).isTrue();
        }

        @Test
        @DisplayName("a card that is neither cannot, however high it is")
        void offSuitCannotTake() {
            final TrickPlay play = aTrickPlay().withCard(card(StrideCategory.DENIAL_OF_SERVICE, Rank.KING)).build();

            assertThat(play.canTakeTrick(StrideCategory.REPUDIATION)).isFalse();
        }
    }

    @Nested
    @DisplayName("refuses text that would break something downstream")
    class UnsafeText {

        @Test
        @DisplayName("a NUL byte in a component name, which PostgreSQL cannot store at all")
        void shouldRejectNulByteInComponent() {
            assertThatExceptionOfType(InvalidInputException.class)
                    .isThrownBy(() -> aTrickPlay().withComponents(List.of("payments\u0000api")).build())
                    .withMessageContaining("control characters");
        }

        @Test
        @DisplayName("a NUL byte in a note")
        void shouldRejectNulByteInNotes() {
            assertThatExceptionOfType(InvalidInputException.class)
                    .isThrownBy(() -> aTrickPlay().withNotes("the token\u0000is never checked").build())
                    .withMessageContaining("control characters");
        }

        @Test
        @DisplayName("a carriage return in a note, because a player must not be able to forge a log line")
        void shouldRejectCarriageReturnInNotes() {
            assertThatExceptionOfType(InvalidInputException.class)
                    .isThrownBy(() -> aTrickPlay().withNotes("ok\r\nWARN  admin granted").build())
                    .withMessageContaining("control characters");
        }

        @Test
        @DisplayName("a newline in a component name")
        void shouldRejectNewlineInComponent() {
            assertThatExceptionOfType(InvalidInputException.class)
                    .isThrownBy(() -> aTrickPlay().withComponents(List.of("payments\napi")).build())
                    .withMessageContaining("control characters");
        }

        @Test
        @DisplayName("a right-to-left override, which would display a component name other than the one stored")
        void shouldRejectBidiOverrideInComponent() {
            assertThatExceptionOfType(InvalidInputException.class)
                    .isThrownBy(() -> aTrickPlay().withComponents(List.of("safe\u202eelbisiv")).build())
                    .withMessageContaining("bidirectional");
        }

        @Test
        @DisplayName("a right-to-left mark in a note")
        void shouldRejectBidiMarkInNotes() {
            assertThatExceptionOfType(InvalidInputException.class)
                    .isThrownBy(() -> aTrickPlay().withNotes("looks\u200fharmless").build())
                    .withMessageContaining("bidirectional");
        }

        @Test
        @DisplayName("every character Unicode marks as a bidirectional control, not merely the override")
        void shouldRejectEveryBidirectionalControl() {
            final char[] controls = {
                '\u061c', '\u200e', '\u200f',
                '\u202a', '\u202b', '\u202c', '\u202d', '\u202e',
                '\u2066', '\u2067', '\u2068', '\u2069',
            };

            for (final char control : controls) {
                assertThatExceptionOfType(InvalidInputException.class)
                        .as("component containing U+%04X", (int) control)
                        .isThrownBy(() -> aTrickPlay().withComponents(List.of("a" + control + "b")).build())
                        .withMessageContaining("bidirectional");
            }
        }

        @Test
        @DisplayName("names the position, so a player can find the character they cannot see")
        void shouldNameThePosition() {
            assertThatExceptionOfType(InvalidInputException.class)
                    .isThrownBy(() -> aTrickPlay().withComponents(List.of("ab\u0000cd")).build())
                    .withMessageContaining("position 2");
        }

        @Test
        @DisplayName("still accepts ordinary punctuation and accented letters")
        void shouldAcceptOrdinaryText() {
            final TrickPlay play = aTrickPlay()
                    .withComponents(List.of("Café-Payments API (v2), \"edge\""))
                    .withNotes("Réf: the caller's identity is never verified — see §3.")
                    .build();

            assertThat(play.components()).containsExactly("Café-Payments API (v2), \"edge\"");
            assertThat(play.notesIfGiven()).isPresent();
        }
    }

    @Nested
    @DisplayName("renders itself without repeating what a player typed")
    class Privacy {

        @Test
        @DisplayName("names neither the note, nor any component, nor the threat prompt")
        void shouldNotRenderPlayerText() {
            final Card played = card(StrideCategory.TAMPERING, Rank.NINE);
            final TrickPlay play = aTrickPlay()
                    .withCard(played)
                    .withComponents(List.of("Ledger writer"))
                    .withNotes("Anyone can rewrite a settled entry.")
                    .build();

            final String rendered = play.toString();

            assertThat(rendered)
                    .doesNotContain("Ledger writer")
                    .doesNotContain("Anyone can rewrite a settled entry.")
                    .doesNotContain(played.threatPrompt())
                    .contains("components=1")
                    .contains("notes=given");
        }

        @Test
        @DisplayName("says so plainly when no note was given")
        void shouldSayWhenNoNoteWasGiven() {
            assertThat(aTrickPlay().withNotes(null).build().toString()).contains("notes=none");
        }

        @Test
        @DisplayName("still names the card, because a played card is face up on the table")
        void shouldNameTheCard() {
            final TrickPlay play = aTrickPlay().withCard(card(StrideCategory.SPOOFING, Rank.KING)).build();

            assertThat(play.toString()).contains("SPOOFING K");
        }
    }

    @Nested
    @DisplayName("bounds the bidirectional refusal to the characters that actually reorder text")
    class BidiBoundaries {

        @Test
        @DisplayName("accepts the character just above the isolate range, so the upper bound is a bound and not an open end")
        void shouldAcceptJustAboveTheIsolateRange() {
            final TrickPlay play = aTrickPlay().withComponents(List.of("gateway\u206a")).build();

            assertThat(play.components()).hasSize(1);
        }

        @Test
        @DisplayName("accepts the character just below the embedding range")
        void shouldAcceptJustBelowTheEmbeddingRange() {
            final TrickPlay play = aTrickPlay().withComponents(List.of("gateway\u2029")).build();

            assertThat(play.components()).hasSize(1);
        }
    }
}
