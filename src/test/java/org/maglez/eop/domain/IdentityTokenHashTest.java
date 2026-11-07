package org.maglez.eop.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link IdentityTokenHash}.
 *
 * <p>The hashing is asserted against a published SHA-256 vector rather than
 * against a second implementation, so a change of algorithm fails the build
 * instead of quietly invalidating every stored digest.
 */
@DisplayName("IdentityTokenHash")
class IdentityTokenHashTest {

    /** SHA-256 of the three bytes {@code abc}, from FIPS 180-4. */
    private static final String SHA256_OF_ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Nested
    @DisplayName("hashing a plaintext token")
    class Hashing {

        @Test
        @DisplayName("produces the published SHA-256 vector in lower-case hex")
        void shouldMatchPublishedVector() {
            assertThat(IdentityTokenHash.of("abc").value()).isEqualTo(SHA256_OF_ABC);
        }

        @Test
        @DisplayName("produces the same digest for the same token")
        void shouldBeDeterministic() {
            assertThat(IdentityTokenHash.of("a-token")).isEqualTo(IdentityTokenHash.of("a-token"));
        }

        @Test
        @DisplayName("produces different digests for tokens differing by one character")
        void shouldDifferForDifferentTokens() {
            assertThat(IdentityTokenHash.of("a-token")).isNotEqualTo(IdentityTokenHash.of("a-tokeN"));
        }

        @Test
        @DisplayName("rejects a null token rather than hashing the string \"null\"")
        void shouldRejectNullToken() {
            assertThatNullPointerException().isThrownBy(() -> IdentityTokenHash.of(null));
        }

        @Test
        @DisplayName("always produces sixty-four hex characters")
        void shouldAlwaysProduceFixedLength() {
            assertThat(IdentityTokenHash.of("x").value()).hasSize(IdentityTokenHash.HEX_LENGTH);
        }
    }

    @Nested
    @DisplayName("the strict constructor, used when reading a stored digest")
    class StrictConstruction {

        @Test
        @DisplayName("accepts a canonical digest")
        void shouldAcceptCanonicalDigest() {
            assertThat(new IdentityTokenHash(SHA256_OF_ABC).value()).isEqualTo(SHA256_OF_ABC);
        }

        @Test
        @DisplayName("rejects null")
        void shouldRejectNull() {
            assertThatNullPointerException().isThrownBy(() -> new IdentityTokenHash(null));
        }

        @Test
        @DisplayName("rejects a digest of the wrong length")
        void shouldRejectWrongLength() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new IdentityTokenHash(SHA256_OF_ABC.substring(1)))
                    .withMessageContaining("exactly 64 hex characters");
        }

        @Test
        @DisplayName("rejects upper-case hex, so one token cannot yield two stored forms")
        void shouldRejectUpperCaseHex() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new IdentityTokenHash(SHA256_OF_ABC.toUpperCase(java.util.Locale.ROOT)))
                    .withMessageContaining("lower-case hex");
        }

        @Test
        @DisplayName("rejects a non-hex character")
        void shouldRejectNonHexCharacter() {
            final String withZed = "z" + SHA256_OF_ABC.substring(1);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new IdentityTokenHash(withZed))
                    .withMessageContaining("lower-case hex");
        }
    }

    @Test
    @DisplayName("never prints the digest, so a log line cannot become a credential")
    void shouldRedactItselfInToString() {
        final IdentityTokenHash hash = IdentityTokenHash.of("abc");

        assertThat(hash).hasToString("IdentityTokenHash[redacted]");
        assertThat(hash.toString()).doesNotContain(SHA256_OF_ABC);
    }

    @Nested
    @DisplayName("comparing two digests")
    class Comparison {

        @Test
        @DisplayName("treats the same digest as equal, whichever instance holds it")
        void shouldEqualAnIdenticalDigest() {
            final IdentityTokenHash left = IdentityTokenHash.of("abc");
            final IdentityTokenHash right = new IdentityTokenHash(SHA256_OF_ABC);

            assertThat(left).isEqualTo(right);
            assertThat(left).hasSameHashCodeAs(right);
        }

        @Test
        @DisplayName("equals the very same instance without consulting the digest")
        void shouldEqualItself() {
            final IdentityTokenHash hash = new IdentityTokenHash(SHA256_OF_ABC);

            assertThat(hash.equals(hash))
                    .as("the identity fast path in equals must be exercised, so that deleting it is a "
                            + "detectable change rather than a silently surviving mutation")
                    .isTrue();
        }

        @Test
        @DisplayName("treats a digest differing in the first character as unequal")
        void shouldRejectADigestDifferingAtTheStart() {
            final IdentityTokenHash hash = new IdentityTokenHash(SHA256_OF_ABC);
            final IdentityTokenHash differsFirst = new IdentityTokenHash("c" + SHA256_OF_ABC.substring(1));

            assertThat(hash).isNotEqualTo(differsFirst);
        }

        @Test
        @DisplayName("treats a digest differing in the last character as unequal")
        void shouldRejectADigestDifferingAtTheEnd() {
            final IdentityTokenHash hash = new IdentityTokenHash(SHA256_OF_ABC);
            final String body = SHA256_OF_ABC.substring(0, IdentityTokenHash.HEX_LENGTH - 1);
            final IdentityTokenHash differsLast = new IdentityTokenHash(body + "c");

            assertThat(hash).isNotEqualTo(differsLast);
        }

        @Test
        @DisplayName("is not equal to null or to another type")
        void shouldRejectForeignValues() {
            final IdentityTokenHash hash = new IdentityTokenHash(SHA256_OF_ABC);
            // Held as Object so the assertion reads as "equals rejects a foreign type" rather than as a
            // comparison of two unrelated static types, which is a different (and accidental) claim.
            final Object sameDigestAsAPlainString = SHA256_OF_ABC;

            assertThat(hash).isNotEqualTo(null);
            assertThat(hash).isNotEqualTo(sameDigestAsAPlainString);
        }

        @Test
        @DisplayName("compares with MessageDigest.isEqual, not the String.equals a record would generate")
        void shouldCompareInConstantTime() throws IOException {
            final Path source = Path.of("src/main/java/org/maglez/eop/domain/IdentityTokenHash.java");
            final String text = Files.readString(source, StandardCharsets.UTF_8);

            assertThat(text)
                    .as("A record's generated equals compares the digest with String.equals, which returns on the "
                            + "first differing byte. The override exists to remove that timing signal, so losing it "
                            + "would silently reinstate the behaviour ADR-015's successor credentials must not inherit.")
                    .contains("MessageDigest.isEqual(asciiBytes(), candidate.asciiBytes())");
        }
    }
}
