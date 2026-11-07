package org.maglez.eop.inbound.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.domain.GameSession;
import org.maglez.eop.domain.Player;
import org.maglez.eop.domain.SessionStatus;
import org.maglez.eop.application.port.in.JoinSessionPort;
import org.maglez.eop.application.port.in.SessionAdmission;
import org.maglez.eop.application.port.out.SessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.maglez.eop.outbound.web.SseSessionEventPublisher;

/**
 * Asserts the two claims that make the transport disposable: a seat is owned by
 * the database and not by a connection, and two players reaching for the same
 * seat at the same instant both end up seated somewhere.
 *
 * <p>The first claim is the one ADR-014 rests on. The EOP-8 spike kept the list
 * of who was playing in the memory of the process holding the streams, and the
 * server went on reporting two live subscribers after both browsers had closed.
 * The design that replaced it treats the subscriber list as a delivery detail
 * and the {@code player} table as the truth, which means losing every subscriber
 * must be survivable. Here every subscriber is dropped deliberately, and the
 * table is re-read to show that nobody moved.
 *
 * <p>The second claim is what {@code uq_player_session_seat} is for (ADR-019).
 * {@code GameSession.nextSeatOrder()} reads the seat count it can see, so two
 * simultaneous joins will both name the same seat; the constraint refuses the
 * loser and the use case retries with the count as it then stands. Five callers
 * are released together against the real index. The assertion is on the outcome
 * rather than on the number of contests, because how many actually collide
 * depends on the machine — but whether a contest happens or not, the seats must
 * come out contiguous and unshared.
 *
 * <p>The parallel test drives {@link JoinSessionPort} rather than the
 * endpoint. The race being tested is between two database writes, and the
 * controller contributes nothing to it; putting a single {@code MockMvc}
 * instance in the middle of five threads would only add a second thing that
 * could be blamed for a failure.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Session resilience")
class SessionResilienceIntegrationTest {

    private static final String SESSIONS = "/api/v1/sessions";

    /** Seats 1 through 5: every seat a facilitator's table has left to give. */
    private static final int CONTENDERS = GameSession.MAXIMUM_PLAYERS - 1;

    private static final String ADDRESS = "198.51.100.7";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SseSessionEventPublisher publisher;

    @Autowired
    private JoinSessionPort joinSessionPort;

    @Autowired
    private SessionRepository sessionRepository;

    @Test
    @DisplayName("keeps every seat when every subscriber is lost")
    void shouldKeepSeatsWhenEverySubscriberIsLost() throws Exception {
        final var facilitator = createSession("Ada");
        joinSession(facilitator.joinCode(), "Grace");
        joinSession(facilitator.joinCode(), "Alan");
        final var seatedBefore = seatsOf(facilitator.sessionId());
        assertThat(seatedBefore).containsExactly(0, 1, 2);

        subscribe(facilitator.sessionId(), facilitator.playerToken());
        assertThat(publisher.subscriberCount(UUID.fromString(facilitator.sessionId())))
                .as("the endpoint registers the caller before returning the stream")
                .isEqualTo(1);

        publisher.forgetEveryone();

        assertThat(publisher.subscriberCount(UUID.fromString(facilitator.sessionId())))
                .as("no subscriber survives")
                .isZero();
        final var reread = sessionRepository.findById(UUID.fromString(facilitator.sessionId())).orElseThrow();
        assertThat(reread.players()).extracting(Player::seatOrder).containsExactly(0, 1, 2);
        assertThat(reread.players()).extracting(player -> player.displayName().value())
                .containsExactly("Ada", "Grace", "Alan");
        assertThat(reread.status()).isEqualTo(SessionStatus.LOBBY);
    }

    @Test
    @DisplayName("still answers a resync after every subscriber is lost")
    void shouldStillAnswerAResyncAfterEverySubscriberIsLost() throws Exception {
        final var facilitator = createSession("Ada");
        joinSession(facilitator.joinCode(), "Grace");
        joinSession(facilitator.joinCode(), "Alan");
        subscribe(facilitator.sessionId(), facilitator.playerToken());

        publisher.forgetEveryone();

        mockMvc.perform(get(SESSIONS + "/" + facilitator.sessionId())
                        .header(SessionController.PLAYER_TOKEN_HEADER, facilitator.playerToken()))
                .andExpect(status().isOk());
        assertThat(seatsOf(facilitator.sessionId())).containsExactly(0, 1, 2);
    }

    @Test
    @DisplayName("seats five simultaneous callers in five different seats")
    void shouldSeatFiveSimultaneousCallersInFiveDifferentSeats() throws Exception {
        final var facilitator = createSession("Ada");
        final var gate = new CountDownLatch(1);
        final var ready = new CountDownLatch(CONTENDERS);
        final List<Future<SessionAdmission>> arrivals;

        try (ExecutorService callers = Executors.newFixedThreadPool(CONTENDERS)) {
            arrivals = IntStream.rangeClosed(1, CONTENDERS)
                    .mapToObj(contender -> callers.submit(() -> {
                        ready.countDown();
                        gate.await(10, TimeUnit.SECONDS);
                        return joinSessionPort.execute(
                                facilitator.joinCode(),
                                org.maglez.eop.domain.DisplayName.of("Contender " + contender),
                                ADDRESS);
                    }))
                    .toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).as("every caller reached the gate").isTrue();
            gate.countDown();
        }

        final var seatsTaken = arrivals.stream()
                .map(SessionResilienceIntegrationTest::completed)
                .map(admission -> admission.session().players().stream()
                        .filter(player -> player.playerId().equals(admission.playerId()))
                        .findFirst()
                        .orElseThrow()
                        .seatOrder())
                .sorted()
                .toList();

        assertThat(seatsTaken)
                .as("each caller was told which seat it holds, and no two were told the same")
                .containsExactly(1, 2, 3, 4, 5);
        assertThat(seatsOf(facilitator.sessionId()))
                .as("the table holds one unshared run of seats")
                .containsExactly(0, 1, 2, 3, 4, 5);
    }

    @Test
    @DisplayName("mints one credential per simultaneous caller")
    void shouldMintOneCredentialPerSimultaneousCaller() throws Exception {
        final var facilitator = createSession("Ada");
        final var gate = new CountDownLatch(1);
        final List<Future<SessionAdmission>> arrivals;

        try (ExecutorService callers = Executors.newFixedThreadPool(CONTENDERS)) {
            arrivals = IntStream.rangeClosed(1, CONTENDERS)
                    .mapToObj(contender -> callers.submit(() -> {
                        gate.await(10, TimeUnit.SECONDS);
                        return joinSessionPort.execute(
                                facilitator.joinCode(),
                                org.maglez.eop.domain.DisplayName.of("Rival " + contender),
                                ADDRESS);
                    }))
                    .toList();
            gate.countDown();
        }

        final var credentials = arrivals.stream()
                .map(SessionResilienceIntegrationTest::completed)
                .map(SessionAdmission::playerToken)
                .toList();
        assertThat(credentials).doesNotHaveDuplicates().hasSize(CONTENDERS);
        assertThat(arrivals.stream().map(SessionResilienceIntegrationTest::completed)
                .map(SessionAdmission::playerId).distinct().count())
                .as("a retried seat must not mint a second identity")
                .isEqualTo(CONTENDERS);
    }

    // ---------------------------------------------------------------- helpers

    private static SessionAdmission completed(final Future<SessionAdmission> arrival) {
        try {
            return arrival.get(20, TimeUnit.SECONDS);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for a caller", interrupted);
        } catch (final Exception failed) {
            throw new IllegalStateException("a simultaneous caller was refused a seat", failed);
        }
    }

    private List<Integer> seatsOf(final String sessionId) {
        return sessionRepository.findById(UUID.fromString(sessionId))
                .orElseThrow()
                .players()
                .stream()
                .map(Player::seatOrder)
                .toList();
    }

    private void subscribe(final String sessionId, final String playerToken) throws Exception {
        mockMvc.perform(get(SESSIONS + "/" + sessionId + "/events")
                        .header(SessionController.PLAYER_TOKEN_HEADER, playerToken))
                .andExpect(request().asyncStarted());
    }

    private Admission createSession(final String displayName) throws Exception {
        final var body = mockMvc.perform(post(SESSIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nameRequest(displayName)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return admissionFrom(body);
    }

    private Admission joinSession(final String joinCode, final String displayName) throws Exception {
        final var body = mockMvc.perform(post(SESSIONS + "/" + joinCode + "/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nameRequest(displayName)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return admissionFrom(body);
    }

    private static String nameRequest(final String displayName) {
        return "{\"displayName\":\"%s\"}".formatted(displayName);
    }

    private static Admission admissionFrom(final String body) {
        final var json = JsonPath.parse(body);
        return new Admission(
                json.read("$.session.sessionId"),
                json.read("$.session.joinCode"),
                json.read("$.playerToken"));
    }

    /**
     * @param sessionId   the lobby that was opened or joined
     * @param joinCode    the code the next caller needs
     * @param playerToken the credential, in plaintext, as only the holder sees it
     */
    private record Admission(String sessionId, String joinCode, String playerToken) {
    }
}
