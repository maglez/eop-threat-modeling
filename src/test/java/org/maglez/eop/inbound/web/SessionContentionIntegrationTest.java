package org.maglez.eop.inbound.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.outbound.persistence.SessionRepositoryAdapter;
import org.maglez.eop.domain.GameSession;
import org.maglez.eop.domain.JoinCode;
import org.maglez.eop.domain.JoinCodeUnavailableException;
import org.maglez.eop.domain.Player;
import org.maglez.eop.domain.SeatAlreadyTakenException;
import org.maglez.eop.application.port.out.SessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The two exhaustion paths, driven over HTTP rather than by calling a handler.
 *
 * <p>{@link GlobalExceptionHandlerTest} proves the two handlers return the right status when called directly. That is
 * necessary but not sufficient for this defect: what escaped to {@code main} was not a wrong handler, it was an absent
 * one, and an absent handler is invisible to a test that names the handler it expects. Only a request travelling the
 * whole adapter can show that Spring dispatches these exceptions to the new methods instead of to
 * {@code handleUnexpected}, which is what turned a lost race into a 500.</p>
 *
 * <p>Neither path can be provoked honestly. Seat contention needs every one of the eight attempts to lose its race,
 * and filling the lobby to capacity raises the already-mapped {@code SessionFullException} instead; an exhausted
 * join-code budget needs five independent collisions in a code space sized so that never happens. So the repository is
 * wrapped in a decorator with two switches, each of which makes the real port throw exactly what the persistence
 * adapter throws when its unique constraint fires. The decorator delegates every other call, so the lobby these tests
 * join is a genuine one created through the real adapter.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Session contention over HTTP")
class SessionContentionIntegrationTest {

    private static final String SESSIONS = "/api/v1/sessions";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ContestableSessionRepository repository;

    @AfterEach
    void releaseTheSwitches() {
        repository.contestEverySeat.set(false);
        repository.rejectEveryJoinCode.set(false);
    }

    @Test
    @DisplayName("a join whose every seat claim loses its race is a 409, not a 500")
    void shouldAnswerSeatContentionWith409() throws Exception {
        final String joinCode = joinCodeOfANewLobby("Grace");

        repository.contestEverySeat.set(true);

        mockMvc.perform(post(SESSIONS + "/" + joinCode + "/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Trent\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("The lobby filled while you were joining"))
                .andExpect(jsonPath("$.detail").value("Another player took the seat on every attempt. Read the session and try again."));
    }

    @Test
    @DisplayName("a create whose every join code collides is a 503 carrying Retry-After, not a 500")
    void shouldAnswerJoinCodeExhaustionWith503() throws Exception {
        repository.rejectEveryJoinCode.set(true);

        mockMvc.perform(post(SESSIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Grace\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.title").value("No lobby could be opened"))
                .andExpect(jsonPath("$.detail").value("The service could not open a new lobby. Try again in a few seconds."));
    }

    @Test
    @DisplayName("with neither switch thrown a lobby is created and joined exactly as before")
    void shouldLeaveTheUncontestedPathUntouched() throws Exception {
        final String joinCode = joinCodeOfANewLobby("Grace");

        mockMvc.perform(post(SESSIONS + "/" + joinCode + "/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Trent\"}"))
                .andExpect(status().isOk());
    }

    /**
     * Opens a real lobby through the undecorated path and returns its join code.
     *
     * @param displayName the facilitator's display name
     * @return the join code the lobby was published under
     * @throws Exception if the request could not be performed
     */
    private String joinCodeOfANewLobby(final String displayName) throws Exception {
        final String body = mockMvc.perform(post(SESSIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"" + displayName + "\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return JsonPath.read(body, "$.session.joinCode");
    }

    /**
     * Publishes the decorator as the primary {@link SessionRepository}, so the use cases resolve to it.
     */
    @TestConfiguration
    static class ContentionConfiguration {

        /**
         * Wraps the real adapter.
         *
         * <p>The delegate is injected by its concrete type on purpose. Asking for the {@link SessionRepository}
         * interface here would ask the container for the bean this method is itself defining.</p>
         *
         * @param delegate the real persistence adapter
         * @return the decorator, primary so every use case is wired through it
         */
        @Bean
        @Primary
        ContestableSessionRepository contestableSessionRepository(final SessionRepositoryAdapter delegate) {
            return new ContestableSessionRepository(delegate);
        }
    }

    /**
     * A {@link SessionRepository} that can be told to lose every seat race, or to collide on every join code.
     *
     * <p>Both switches raise the exception the real adapter raises when the matching unique constraint fires, so the
     * use cases exhaust their retry budgets against the same types they would meet in production.</p>
     */
    static final class ContestableSessionRepository implements SessionRepository {

        private final AtomicBoolean contestEverySeat = new AtomicBoolean();

        private final AtomicBoolean rejectEveryJoinCode = new AtomicBoolean();

        private final SessionRepository delegate;

        ContestableSessionRepository(final SessionRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<GameSession> findById(final UUID sessionId) {
            return delegate.findById(sessionId);
        }

        @Override
        public Optional<GameSession> findByJoinCode(final JoinCode joinCode) {
            return delegate.findByJoinCode(joinCode);
        }

        @Override
        public void createLobby(final GameSession session) {
            if (rejectEveryJoinCode.get()) {
                throw new JoinCodeUnavailableException();
            }
            delegate.createLobby(session);
        }

        @Override
        public void seatPlayer(final UUID sessionId, final Player player, final Instant occurredAt) {
            if (contestEverySeat.get()) {
                throw new SeatAlreadyTakenException(sessionId, player.seatOrder());
            }
            delegate.seatPlayer(sessionId, player, occurredAt);
        }

        @Override
        public void recordStarted(final UUID sessionId, final Instant occurredAt) {
            delegate.recordStarted(sessionId, occurredAt);
        }

        @Override
        public void recordCompleted(final UUID sessionId, final Instant occurredAt) {
            delegate.recordCompleted(sessionId, occurredAt);
        }

        @Override
        public List<UUID> findExpiredSessionIds(final Instant before) {
            return delegate.findExpiredSessionIds(before);
        }

        @Override
        public void abandonAndDelete(final UUID sessionId) {
            delegate.abandonAndDelete(sessionId);
        }

        @Override
        public void resetToInProgress(final UUID sessionId, final Instant occurredAt) {
            delegate.resetToInProgress(sessionId, occurredAt);
        }
    }
}
