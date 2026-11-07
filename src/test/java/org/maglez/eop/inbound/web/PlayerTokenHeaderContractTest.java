package org.maglez.eop.inbound.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.domain.Player;

/**
 * Pins the player credential header name to the hand-authored API contract.
 *
 * <p>Every other test reaches the header through {@link SessionController#PLAYER_TOKEN_HEADER},
 * which means the whole suite would still pass if that constant were changed to something the
 * contract does not declare. Clients follow {@code docs/api/openapi.yml}, so a divergence there
 * is invisible to the tests and total at runtime: the server would read a header no client ever
 * sends and refuse every authenticated request with a 403.
 *
 * <p>The contract is the authoritative document, so the assertion runs in that direction — the
 * constant must name a header the contract declares, not the other way round. The literal is
 * deliberately not repeated here; a test that hard-codes the same string twice pins the code to
 * itself rather than to the contract.
 */
@DisplayName("Player token header contract")
class PlayerTokenHeaderContractTest {

    private static final Path CONTRACT = Path.of("docs", "api", "openapi.yml");

    /** RFC 9110 field name: one or more token characters, no whitespace, no separators. */
    private static final Pattern FIELD_NAME = Pattern.compile("[!#$%&'*+\\-.^_`|~0-9A-Za-z]+");

    @Test
    @DisplayName("is a syntactically valid HTTP field name")
    void headerNameIsAValidHttpFieldName() {
        assertThat(SessionController.PLAYER_TOKEN_HEADER).isNotBlank();
        assertThat(SessionController.PLAYER_TOKEN_HEADER).matches(FIELD_NAME);
    }

    @Test
    @DisplayName("is declared as a header parameter in the OpenAPI contract")
    void headerNameIsDeclaredInTheContract() throws IOException {
        assertThat(CONTRACT)
                .as("the hand-authored API contract must be present for this test to mean anything")
                .isRegularFile();

        final String contract = Files.readString(CONTRACT, StandardCharsets.UTF_8);

        assertThat(contract)
                .as(
                        "SessionController.PLAYER_TOKEN_HEADER names a header that %s does not"
                                + " declare; clients follow the contract, so every authenticated"
                                + " request would be refused",
                        CONTRACT)
                .contains("name: " + SessionController.PLAYER_TOKEN_HEADER);
    }
}
