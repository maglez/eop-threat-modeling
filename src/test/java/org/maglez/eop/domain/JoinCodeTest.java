package org.maglez.eop.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("JoinCode")
class JoinCodeTest {

    private static final String CANONICAL = "7QK2FMV9";

    @Nested
    @DisplayName("the strict constructor, for generated and stored codes")
    class StrictConstruction {

        @Test
        @DisplayName("accepts a canonical code")
        void shouldAcceptCanonicalCode() {
            assertThat(new JoinCode(CANONICAL).value()).isEqualTo(CANONICAL);
        }

        @Test
        @DisplayName("rejects null")
        void shouldRejectNull() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new JoinCode(null))
                    .withMessageContaining("value");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "7QK2FMV", "7QK2FMV99"})
        @DisplayName("rejects anything that is not exactly eight characters")
        void shouldRejectWrongLength(final String candidate) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new JoinCode(candidate))
                    .withMessageContaining("exactly 8 characters");
        }

        @ParameterizedTest
        @ValueSource(strings = {"7QK2FMVI", "7QK2FMVL", "7QK2FMVO", "7QK2FMVU"})
        @DisplayName("rejects each ambiguous character the alphabet leaves out")
        void shouldRejectExcludedCharacters(final String candidate) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new JoinCode(candidate))
                    .withMessageContaining("Crockford base32");
        }

        @Test
        @DisplayName("rejects lower case, because the canonical form is upper case")
        void shouldRejectLowerCase() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new JoinCode(CANONICAL.toLowerCase(java.util.Locale.ROOT)))
                    .withMessageContaining("Crockford base32");
        }

        @Test
        @DisplayName("the alphabet is thirty-two symbols and omits I, L, O and U")
        void shouldExcludeMisreadCharactersFromAlphabet() {
            assertThat(JoinCode.ALPHABET).hasSize(32).doesNotContain("I", "L", "O", "U");
            assertThat(JoinCode.LENGTH).isEqualTo(8);
        }

        @Test
        @DisplayName("eight characters over a thirty-two symbol alphabet is exactly forty bits")
        void shouldCarryFortyBitsOfEntropy() {
            final double bitsPerCharacter = Math.log(JoinCode.ALPHABET.length()) / Math.log(2);
            assertThat(bitsPerCharacter * JoinCode.LENGTH)
                    .as("EOP-24 widened the code from six characters (thirty bits) to eight, "
                            + "because thirty bits is enumerable by a distributed attacker in days (ADR-019)")
                    .isEqualTo(40.0, org.assertj.core.data.Offset.offset(1.0e-9));
        }
    }

    @Nested
    @DisplayName("parsing what a human typed")
    class Parsing {

        @Test
        @DisplayName("upper cases and strips surrounding whitespace")
        void shouldFoldCaseAndWhitespace() {
            assertThat(JoinCode.parse("  7qk2fmv9  ")).contains(new JoinCode(CANONICAL));
        }

        @Test
        @DisplayName("folds I and L to one, and O to zero, as a video call mangles them")
        void shouldFoldMisreadCharacters() {
            assertThat(JoinCode.parse("IL0K2FV9")).contains(new JoinCode("110K2FV9"));
            assertThat(JoinCode.parse("7QKOFMV9")).contains(new JoinCode("7QK0FMV9"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "7QK2FMV", "7QK2FMV99", "7QK2FMV!", "7QK2FMVU"})
        @DisplayName("returns empty rather than throwing, for every unusable input")
        void shouldReturnEmptyForUnusableInput(final String candidate) {
            assertThat(JoinCode.parse(candidate)).isEmpty();
        }

        @Test
        @DisplayName("returns empty for null")
        void shouldReturnEmptyForNull() {
            assertThat(JoinCode.parse(null)).isEmpty();
        }

        @Test
        @DisplayName("a code that was valid at six characters is no longer a code")
        void shouldReturnEmptyForALegacySixCharacterCode() {
            assertThat(JoinCode.parse("7QK2FM")).isEmpty();
            assertThat(JoinCode.parse("7qk2fm")).isEmpty();
        }

        @Test
        @DisplayName("U is not folded, so a code containing one is simply not a code")
        void shouldNotFoldU() {
            assertThat(JoinCode.parse("7QK2FMVU")).isEmpty();
            assertThat(JoinCode.parse("7qk2fmvu")).isEmpty();
        }
    }
}
