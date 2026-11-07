package org.maglez.eop.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CardNotFoundException")
class CardNotFoundExceptionTest {

    @Test
    @DisplayName("names the identifier that matched nothing, so the log says which card was asked for")
    void shouldCarryTheRequestedIdentifier() {
        final UUID missing = UUID.fromString("0f0f0f0f-0f0f-4f0f-8f0f-0f0f0f0f0f0f");

        final CardNotFoundException exception = new CardNotFoundException(missing);

        assertThat(exception.cardId()).isEqualTo(missing);
        assertThat(exception.getMessage()).contains(missing.toString());
    }
}
