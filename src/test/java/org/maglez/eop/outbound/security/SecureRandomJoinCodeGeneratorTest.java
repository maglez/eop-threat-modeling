package org.maglez.eop.outbound.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.domain.JoinCode;

/**
 * Unit tests for {@link SecureRandomJoinCodeGenerator}.
 *
 * <p>A join code is the only gate on entering a session, so the property that matters most
 * here — that the draw is unpredictable — is the one property no behavioural assertion can
 * reach. A generator seeded from {@code java.util.Random} produces codes of the right length,
 * drawn from the right alphabet, that never repeat across a thousand draws; it satisfies every
 * test below except the last one. That is why the last one reads the source text. The pattern
 * is borrowed from {@code IdentityTokenHashTest.shouldCompareInConstantTime}, which pins a
 * constant-time comparison for the same reason: the defect it guards against is invisible from
 * outside the class.
 *
 * <p>Two of the assertions are probabilistic and both are safe by a wide margin. Across a
 * thousand draws from the 32<sup>8</sup> (about 1.1 x 10<sup>12</sup>) keyspace, the birthday
 * bound puts the chance of a coincidental repeat at roughly 4.5 x 10<sup>-7</sup> — orders of
 * magnitude below the chance of the machine running the test failing mid-run. The chance of a
 * particular alphabet symbol being absent from the eight thousand characters those draws
 * produce is (31/32)<sup>8000</sup>, which is far smaller still. Both are written over enough
 * draws to fail for the defects they are aimed at — a generator collapsed onto a constant, and
 * an off-by-one bound that leaves the last symbol unreachable — rather than for bad luck.
 */
@DisplayName("SecureRandomJoinCodeGenerator")
class SecureRandomJoinCodeGeneratorTest {

    /**
     * Draws per aggregate assertion. See the class comment for the arithmetic that makes this
     * number large enough to catch the intended defects and small enough to be reliable.
     */
    private static final int DRAWS = 1_000;

    private final SecureRandomJoinCodeGenerator generator = new SecureRandomJoinCodeGenerator();

    @Test
    @DisplayName("draws every code at exactly JoinCode.LENGTH characters")
    void shouldDrawEveryCodeAtDeclaredLength() {
        final Set<Integer> lengths = new HashSet<>();

        for (int draw = 0; draw < DRAWS; draw++) {
            lengths.add(generator.nextJoinCode().value().length());
        }

        assertThat(lengths)
                .as("Asserted over many draws rather than one so that a length which varies with "
                        + "the drawn value cannot pass by luck.")
                .containsExactly(JoinCode.LENGTH);
    }

    @Test
    @DisplayName("draws from exactly the Crockford base32 alphabet, no symbol missing or extra")
    void shouldDrawFromExactlyTheDeclaredAlphabet() {
        final Set<Character> observed = new HashSet<>();

        for (int draw = 0; draw < DRAWS; draw++) {
            for (final char character : generator.nextJoinCode().value().toCharArray()) {
                observed.add(character);
            }
        }

        assertThat(observed)
                .as("Equality in both directions catches two different defects. An extra character "
                        + "means the generator has drifted from the entity's alphabet, which "
                        + "JoinCode.parse would then refuse to read back. A missing one means an "
                        + "off-by-one in the bound passed to nextInt has quietly shrunk the "
                        + "keyspace, costing entropy while still producing a valid code.")
                .containsExactlyInAnyOrderElementsOf(declaredAlphabet());
    }

    @Test
    @DisplayName("does not repeat a code across a thousand draws")
    void shouldNotRepeatACodeAcrossAThousandDraws() {
        final Set<String> drawn = new HashSet<>();

        for (int draw = 0; draw < DRAWS; draw++) {
            drawn.add(generator.nextJoinCode().value());
        }

        assertThat(drawn)
                .as("Uniqueness in storage is enforced by uq_game_session_join_code, so this is not "
                        + "asserting a guarantee the generator itself makes. It fails for a "
                        + "generator that has collapsed onto a constant or a short cycle, which the "
                        + "length and alphabet assertions above would both pass.")
                .hasSize(DRAWS);
    }

    @Test
    @DisplayName("seeds from SecureRandom, not from the predictable java.util.Random")
    void shouldSeedFromSecureRandom() throws IOException {
        final Path source = Path.of("src/main/java/org/maglez/eop/outbound/security/SecureRandomJoinCodeGenerator.java");
        final String text = Files.readString(source, StandardCharsets.UTF_8);

        assertThat(text)
                .as("java.util.Random is a 48-bit linear congruential generator whose internal state "
                        + "is recoverable from a handful of outputs, so a swap would let an attacker "
                        + "who has seen one join code compute the next. The join-attempt limiter does "
                        + "not close that: it bounds guessing, and against a recovered LCG state "
                        + "there is nothing to guess. No behavioural assertion can tell the two "
                        + "sources apart, so the source text is the only place the property can be "
                        + "pinned. Both the field type and the instantiation are asserted because a "
                        + "downgrade needs both edits — SecureRandom extends Random, so the narrower "
                        + "declared type will not hold a plain Random.")
                .contains("private final SecureRandom random;")
                .contains("this.random = new SecureRandom();")
                .doesNotContain("new Random()")
                .doesNotContain("new java.util.Random(");
    }

    private static Set<Character> declaredAlphabet() {
        final Set<Character> characters = new HashSet<>();
        for (final char character : JoinCode.ALPHABET.toCharArray()) {
            characters.add(character);
        }
        return characters;
    }
}
