package org.maglez.eop.outbound.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SecureRandomIdentityTokenGenerator}.
 *
 * <p>An identity token is the only credential distinguishing one player from another, and a
 * player from the facilitator, so predicting one is impersonation and in the facilitator's case
 * privilege escalation. As with the join code generator alongside it, the property that matters
 * most — unpredictability — is the one no behavioural assertion can observe: a generator seeded
 * from {@code java.util.Random} emits tokens of the right length, in the right alphabet, that
 * decode to the right number of bytes and never repeat. It passes every test below except the
 * last, which reads the source text instead. The pattern comes from
 * {@code IdentityTokenHashTest.shouldCompareInConstantTime}.
 *
 * <p>The length and decoded-size assertions are the two halves of one property and are worth
 * keeping separate. Base64url over 32 bytes without padding is 43 characters, since 32 bytes is
 * 256 bits and 256 divided by 6 rounds up to 43; asserting the character length pins the wire
 * format callers see, while decoding and counting bytes pins the entropy behind it. A generator
 * quietly reduced to 16 bytes would still emit a plausible-looking token, and only the second
 * assertion notices.
 *
 * <p>The no-repeat assertion is probabilistic and safe by an enormous margin: over a thousand
 * draws from a 2<sup>256</sup> space the birthday bound is around 10<sup>-71</sup>. It is not
 * testing a uniqueness guarantee the generator makes — it fails for a generator that has
 * collapsed onto a constant, which every other assertion here would pass.
 */
@DisplayName("SecureRandomIdentityTokenGenerator")
class SecureRandomIdentityTokenGeneratorTest {

    /** Draws per aggregate assertion. See the class comment for the arithmetic. */
    private static final int DRAWS = 1_000;

    /**
     * Base64url length of 32 bytes with padding stripped: 256 bits over 6 bits per character,
     * rounded up. Written as a literal because the generator's byte count is private.
     */
    private static final int EXPECTED_LENGTH = 43;

    /** Entropy the token must carry, in bytes, mirroring the generator's own constant. */
    private static final int EXPECTED_ENTROPY_BYTES = 32;

    private final SecureRandomIdentityTokenGenerator generator = new SecureRandomIdentityTokenGenerator();

    @Test
    @DisplayName("emits every token as 43 unpadded base64url characters")
    void shouldEmitEveryTokenAtBase64UrlLength() {
        final Set<Integer> lengths = new HashSet<>();

        for (int draw = 0; draw < DRAWS; draw++) {
            lengths.add(generator.nextToken().length());
        }

        assertThat(lengths)
                .as("Asserted over many draws rather than one because base64 length depends on the "
                        + "input size, not the input value, so a single draw would not reveal a "
                        + "length that varies. 43 is the unpadded encoding of 32 bytes; a token "
                        + "arriving at 44 characters would mean the padding suppression was lost, "
                        + "putting a '=' into a value that travels in URLs.")
                .containsExactly(EXPECTED_LENGTH);
    }

    @Test
    @DisplayName("uses only URL-safe base64 characters, with no padding")
    void shouldUseOnlyUrlSafeCharacters() {
        for (int draw = 0; draw < DRAWS; draw++) {
            final String issued = generator.nextToken();

            assertThat(issued)
                    .as("The token is handed to its owner in a URL and a header, so the standard "
                            + "base64 alphabet will not do: '+' and '/' need escaping and '=' "
                            + "terminates a query parameter in some parsers. This asserts the "
                            + "url-safe encoder is the one in use, which the length assertion alone "
                            + "does not establish since both alphabets encode to the same length.")
                    .matches("[A-Za-z0-9_-]+");
        }
    }

    @Test
    @DisplayName("carries 32 bytes of entropy, recovered by decoding the token")
    void shouldCarryDeclaredEntropy() {
        final Set<Integer> decodedSizes = new HashSet<>();

        for (int draw = 0; draw < DRAWS; draw++) {
            decodedSizes.add(Base64.getUrlDecoder().decode(generator.nextToken()).length);
        }

        assertThat(decodedSizes)
                .as("Decoding and counting bytes is what pins the entropy, as distinct from the "
                        + "character length above which pins the wire format. A generator reduced to "
                        + "16 bytes would emit a shorter but entirely plausible token; this is the "
                        + "assertion that notices. Decoding also proves the value is well-formed "
                        + "base64url rather than merely drawn from its alphabet.")
                .containsExactly(EXPECTED_ENTROPY_BYTES);
    }

    @Test
    @DisplayName("does not repeat a token across a thousand draws")
    void shouldNotRepeatATokenAcrossAThousandDraws() {
        final Set<String> drawn = new HashSet<>();

        for (int draw = 0; draw < DRAWS; draw++) {
            drawn.add(generator.nextToken());
        }

        assertThat(drawn)
                .as("Fails for a generator that has collapsed onto a constant or a short cycle, "
                        + "which the length, alphabet and entropy assertions above would all pass. "
                        + "Two players issued the same token would be indistinguishable to every "
                        + "check that reads one.")
                .hasSize(DRAWS);
    }

    @Test
    @DisplayName("seeds from SecureRandom, not from the predictable java.util.Random")
    void shouldSeedFromSecureRandom() throws IOException {
        final Path source = Path.of("src/main/java/org/maglez/eop/outbound/security/SecureRandomIdentityTokenGenerator.java");
        final String text = Files.readString(source, StandardCharsets.UTF_8);

        assertThat(text)
                .as("java.util.Random is a 48-bit linear congruential generator whose state is "
                        + "recoverable from a handful of outputs, so 256 bits of nominal entropy "
                        + "would collapse to at most 48 bits of real entropy and an attacker holding "
                        + "one token could compute the tokens issued around it — including the "
                        + "facilitator's. Storing the token as a plain SHA-256 digest is only "
                        + "defensible because the input is unguessable, so this assertion also "
                        + "protects that decision. No behavioural assertion can tell the two sources "
                        + "apart. Both the field type and the instantiation are asserted because a "
                        + "downgrade needs both edits: SecureRandom extends Random, so the narrower "
                        + "declared type will not hold a plain Random.")
                .contains("private final SecureRandom random;")
                .contains("this.random = new SecureRandom();")
                .doesNotContain("new Random()")
                .doesNotContain("new java.util.Random(");
    }
}
