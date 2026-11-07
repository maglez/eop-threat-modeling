package org.maglez.eop.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.maglez.eop.domain.GameSessionBuilder.aSession;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.maglez.eop.domain.GameSession;
import org.maglez.eop.domain.SessionStatus;
import org.maglez.eop.domain.Trick;
import org.maglez.eop.application.port.out.InMemorySessionRepository;
import org.maglez.eop.application.port.out.InMemoryTrickRepository;
import org.maglez.eop.application.port.out.RecordingSessionEventPublisher;
import org.maglez.eop.domain.GameSessionBuilder;

/**
 * Tests {@link TrickJournal} directly, which is a deliberately narrow brief.
 *
 * <p>The cascade this class owns — record the resolution, announce it, complete the session,
 * persist the result best-effort, announce the game over — is already driven end to end by
 * {@link PlayCardServiceTest} and {@link ResolveTrickServiceTest}, which between them assert the
 * strict {@code recordResolution, publish, recordCompleted, publish} ordering, the concurrent
 * completion that must be swallowed rather than surfaced, and the persist failure that must be
 * logged rather than thrown. Those tests reach every branch of this class through its two callers,
 * so repeating them here would restate the same expectations against the same doubles.
 *
 * <p>What is left is what is true of the journal and of neither caller: the two operations that
 * deliberately say nothing. Both are easy to "fix" into announcing something, and neither caller
 * would notice, because a surplus frame breaks no assertion either one makes.
 */
@DisplayName("TrickJournal")
class TrickJournalTest {

    private static final Instant NOW = Instant.parse("2026-09-04T10:15:30Z");

    private final List<String> order = new ArrayList<>();

    private final InMemoryTrickRepository trickRepository = new InMemoryTrickRepository(order);

    private final RecordingSessionEventPublisher publisher = new RecordingSessionEventPublisher(order);

    private final GameSession session =
            aSession().withPlayerCount(3).withStatus(SessionStatus.IN_PROGRESS).build();

    @Test
    @DisplayName("opens a trick without announcing anything")
    void shouldNotAnnounceOpeningATrick() {
        final var journal = journal();
        final var trick = Trick.open(UUID.randomUUID(), 1, 0);

        journal.openTrick(session.sessionId(), trick, 0, NOW);

        assertThat(trickRepository.opened())
                .as("the trick must reach the port")
                .hasSize(1);
        assertThat(publisher.published())
                .as("opening a trick is not a fact a client is told: the play that opens it "
                        + "announces card-played immediately afterwards, so a frame here would "
                        + "describe a table state no player can act on")
                .isEmpty();
        assertThat(order).containsExactly("openTrick");
    }

    @Test
    @DisplayName("reads the current trick without writing or announcing anything")
    void shouldNotWriteOrAnnounceWhenReadingTheCurrentTrick() {
        final var seeded = Trick.open(UUID.randomUUID(), 1, 0);
        trickRepository.seededWith(seeded);
        final var journal = journal();

        final var found = journal.currentTrick(session.sessionId());

        assertThat(found).contains(seeded);
        assertThat(order)
                .as("currentTrick is the read half of a compare-and-set and must leave no trace: "
                        + "it exists on the journal only so the leader seat it yields goes straight "
                        + "back down as the expected witness")
                .isEmpty();
        assertThat(publisher.published()).isEmpty();
    }

    private TrickJournal journal() {
        return new TrickJournal(
                trickRepository, new InMemorySessionRepository(order, session), publisher, Optional.empty());
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("rejects null collaborators")
        void shouldRejectNullCollaborators() {
            final var sessionRepository = new InMemorySessionRepository(order, session);

            assertThatNullPointerException()
                    .isThrownBy(() -> new TrickJournal(null, sessionRepository, publisher, Optional.empty()))
                    .withMessageContaining("trickRepository");
            assertThatNullPointerException()
                    .isThrownBy(() -> new TrickJournal(trickRepository, null, publisher, Optional.empty()))
                    .withMessageContaining("sessionRepository");
            assertThatNullPointerException()
                    .isThrownBy(() -> new TrickJournal(trickRepository, sessionRepository, null, Optional.empty()))
                    .withMessageContaining("sessionEventPublisher");
            assertThatNullPointerException()
                    .isThrownBy(() -> new TrickJournal(trickRepository, sessionRepository, publisher, null))
                    .withMessageContaining("persistGameResultPort");
        }
    }
}
