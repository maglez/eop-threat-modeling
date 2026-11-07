package org.maglez.eop.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The wire names carried by session events.
 *
 * <p>These strings are part of the published contract: a browser subscribes to
 * {@code player-joined} by that exact name, so renaming the constant must not
 * silently rename the event. Pinning them here makes that rename a build failure
 * rather than a front end that stops reacting.
 *
 * <p>Three of the five names are reserved rather than published. Nothing emits
 * {@code hand-dealt}, {@code card-played} or {@code trick-resolved} yet, because
 * wiring the publisher into the trick play use cases is a later slice. They are
 * minted here and in {@code openapi.yml} together, ahead of the code that will
 * emit them, because the name is the part clients depend on: a client may match
 * on a reserved name today and keep working unchanged when the server starts
 * sending it. A later release may begin emitting a reserved name; it may never
 * rename one. The test below reads the contract to make sure the two lists cannot
 * drift apart, which is the failure this pairing exists to prevent.
 */
@DisplayName("SessionEventType")
class SessionEventTypeTest {

    /** The hand authored contract, which fixes every name asserted here. */
    private static final Path CONTRACT = Path.of("docs", "api", "openapi.yml");

    @Test
    @DisplayName("names the arrival of a player in the hyphenated form the browser listens for")
    void shouldNameThePlayerJoinedEvent() {
        assertThat(SessionEventType.PLAYER_JOINED.wireName()).isEqualTo("player-joined");
    }

    @Test
    @DisplayName("names the start of play in the hyphenated form the browser listens for")
    void shouldNameTheGameStartedEvent() {
        assertThat(SessionEventType.GAME_STARTED.wireName()).isEqualTo("game-started");
    }

    @Test
    @DisplayName("names the deal, a play and a resolution, the three trick play events")
    void shouldNameTheTrickPlayEvents() {
        assertThat(SessionEventType.HAND_DEALT.wireName()).isEqualTo("hand-dealt");
        assertThat(SessionEventType.CARD_PLAYED.wireName()).isEqualTo("card-played");
        assertThat(SessionEventType.TRICK_RESOLVED.wireName()).isEqualTo("trick-resolved");
    }

    @Test
    @DisplayName("carries the two lifecycle events, the three trick play events, and game-completed, and nothing else")
    void shouldCarryTheLifecycleAndTrickPlayEvents() {
        assertThat(SessionEventType.values())
                .containsExactly(
                        SessionEventType.PLAYER_JOINED,
                        SessionEventType.GAME_STARTED,
                        SessionEventType.HAND_DEALT,
                        SessionEventType.CARD_PLAYED,
                        SessionEventType.TRICK_RESOLVED,
                        SessionEventType.GAME_COMPLETED);
    }

    @Test
    @DisplayName("spells every name the way the published contract spells it")
    void shouldMatchTheNamesInTheContract() throws IOException {
        final var contract = Files.readString(CONTRACT);

        for (final SessionEventType type : SessionEventType.values()) {
            assertThat(contract)
                    .as("%s carries the wire name %s, which the SessionEvent schema must also list, "
                            + "or a client matching the contract will never see the event",
                            type.name(), type.wireName())
                    .contains("- " + type.wireName());
        }
    }

    @Test
    @DisplayName("does not let the wire name of one event be reused by another")
    void shouldGiveEachEventItsOwnName() {
        final var names = Arrays.stream(SessionEventType.values())
                .map(SessionEventType::wireName)
                .toList();

        assertThat(names)
                .as("two constants sharing a wire name would make the event indistinguishable on the stream")
                .doesNotHaveDuplicates();
    }
}
