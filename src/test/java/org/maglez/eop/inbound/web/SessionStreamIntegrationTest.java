package org.maglez.eop.inbound.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.maglez.eop.domain.Player;

/**
 * Proves that a browser holding an open connection is actually told what happened.
 *
 * <p>Every other test of the realtime path stops at the publisher: it can show that an emitter was
 * registered, that a departed emitter was forgotten and that the heartbeat thread sweeps. None of
 * them can show that a byte ever left the process, because a {@code SseEmitter} with no servlet
 * behind it buffers what it is handed instead of writing it. This class starts a real server on a
 * real port, opens a real socket and reads the wire.
 *
 * <p>The client is the JDK's own {@code HttpClient} with a line body handler rather than an SSE
 * client library. That is deliberate twice over: the project does not depend on WebFlux, so
 * {@code WebClient} is not available; and a parsing SSE client would helpfully discard exactly the
 * frames this test exists to check. The reconnect hint and both comment frames carry no event and
 * no data, so a well-behaved parser drops them — yet they are the whole of the keep-alive contract
 * with an intermediary, and a proxy that idles a commentless connection out is the failure this
 * suite is meant to catch (ADR-014).
 *
 * <p>The heartbeat is turned down from fifteen seconds to two hundred milliseconds for this context
 * alone. A test that waited out the production interval would be the slowest in the suite by two
 * orders of magnitude and would tell us nothing extra.
 *
 * <p>One test here is not about the wire at all. {@code shouldNotHoldAJdbcConnectionForTheLifetimeOfAStream}
 * asserts that an open stream costs no JDBC connection, and it lives in this class because it needs
 * exactly what this class already provides: a real server, so that returning an {@code SseEmitter}
 * really does start async processing. With {@code spring.jpa.open-in-view} at Spring's default of
 * {@code true} the {@code EntityManager} opened to validate the player token stayed bound until the
 * request completed — which for a stream means until the stream ended — so each watcher held one of
 * the pool's ten connections and a handful of watchers stalled every database-backed request in the
 * application for as long as it took a heartbeat to reap a dead stream (EOP-227, ADR-070).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "eop.realtime.heartbeat-interval=200ms")
@DisplayName("Session event stream")
class SessionStreamIntegrationTest {

    /** How long a wire assertion waits before it is declared a genuine failure. */
    private static final Duration PATIENCE = Duration.ofSeconds(10);

    /** How often the collected lines are re-examined while waiting. */
    private static final Duration GLANCE = Duration.ofMillis(50);

    /** How many beats must arrive before the keep-alive is believed to be periodic rather than lucky. */
    private static final int BEATS_EXPECTED = 3;

    /** How many streams the connection-hold assertion opens at once. */
    private static final int STREAMS_WATCHED = 3;

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    private final HttpClient client = HttpClient.newHttpClient();

    /**
     * Cancels any stream still being read.
     *
     * <p>The subscriber never sees the end of the body — the server has no reason to close a stream
     * it believes a player is watching — so the client has to be torn down rather than drained. An
     * immediate shutdown cancels the in-flight response, the emitter's error callback fires and the
     * server forgets the subscriber, which is the same sequence a closed browser tab produces.
     */
    @AfterEach
    void hangUp() {
        client.shutdownNow();
    }

    @Test
    @DisplayName("opens with a reconnect hint and a comment, so an intermediary sees traffic at once")
    void shouldOpenTheStreamWithAReconnectHintAndAComment() throws Exception {
        final Admission facilitator = createSession("Ada");
        final List<String> wire = watch(facilitator);

        awaitFrame(wire, "retry:3000");
        awaitFrame(wire, ":subscribed");
    }

    @Test
    @DisplayName("delivers player-joined to a subscriber watching the session that was joined")
    void shouldDeliverPlayerJoinedToALiveSubscriber() throws Exception {
        final Admission facilitator = createSession("Ada");
        final List<String> wire = watch(facilitator);
        awaitFrame(wire, ":subscribed");

        join(facilitator.joinCode(), "Grace");

        awaitFrame(wire, "event:player-joined");
        awaitData(wire, facilitator.sessionId());
    }

    @Test
    @DisplayName("delivers game-started, so a lobby learns play began without polling for it")
    void shouldDeliverGameStartedToALiveSubscriber() throws Exception {
        final Admission facilitator = createSession("Ada");
        join(facilitator.joinCode(), "Grace");
        join(facilitator.joinCode(), "Alan");
        final List<String> wire = watch(facilitator);
        awaitFrame(wire, ":subscribed");

        startPlay(facilitator);

        awaitFrame(wire, "event:game-started");
    }

    @Test
    @DisplayName("keeps sending heartbeat comments while nothing at all is happening")
    void shouldKeepAnIdleConnectionAliveWithHeartbeats() throws Exception {
        final Admission facilitator = createSession("Ada");
        final List<String> wire = watch(facilitator);

        awaitFrame(wire, ":heartbeat");

        // A single beat could be a coincidence of timing; the contract is that they keep coming.
        await().atMost(PATIENCE)
                .pollInterval(GLANCE)
                .untilAsserted(() -> assertThat(countOf(wire, ":heartbeat")).isGreaterThanOrEqualTo(BEATS_EXPECTED));
    }

    @Test
    @DisplayName("refuses to open a stream for a caller with no credential")
    void shouldRefuseToOpenAStreamWithoutACredential() throws Exception {
        final Admission facilitator = createSession("Ada");

        final HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(eventsUri(facilitator.sessionId())).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("Player not recognised").doesNotContain("subscribed");
    }

    @Test
    @DisplayName("holds no JDBC connection while a stream is open, so watchers cannot exhaust the pool")
    void shouldNotHoldAJdbcConnectionForTheLifetimeOfAStream() throws Exception {
        final HikariPoolMXBean pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        final Admission facilitator = createSession("Ada");
        final Admission second = join(facilitator.joinCode(), "Grace");
        final Admission third = join(facilitator.joinCode(), "Alan");

        final List<String> firstWire = watch(facilitator);
        final List<String> secondWire = watch(second);
        final List<String> thirdWire = watch(third);
        awaitFrame(firstWire, ":subscribed");
        awaitFrame(secondWire, ":subscribed");
        awaitFrame(thirdWire, ":subscribed");

        // All three streams are live and being read, so nothing here is waiting on a reaper: if a
        // stream costs a connection, these three are checked out right now and stay that way.
        await().atMost(PATIENCE)
                .pollInterval(GLANCE)
                .untilAsserted(() -> assertThat(pool.getActiveConnections())
                        .describedAs("JDBC connections still checked out while %d streams are open", STREAMS_WATCHED)
                        .isZero());
    }

    /**
     * Waits until the stream shows a whitespace-insensitive match for the given frame.
     *
     * <p>Spring writes {@code event:player-joined} without a space after the colon while the event
     * stream specification permits one, so the comparison ignores spaces rather than pinning a
     * detail of the framework's string building that no client cares about.
     *
     * @param wire the lines collected so far
     * @param frame the frame to wait for
     */
    private static void awaitFrame(final List<String> wire, final String frame) {
        await().atMost(PATIENCE)
                .pollInterval(GLANCE)
                .untilAsserted(() -> assertThat(wire)
                        .as("waiting for %s in %s", frame, wire)
                        .anyMatch(line -> squeeze(line).equals(frame)));
    }

    /**
     * Waits until a data frame naming the given session arrives.
     *
     * @param wire the lines collected so far
     * @param sessionId the identifier the payload must carry
     */
    private static void awaitData(final List<String> wire, final String sessionId) {
        await().atMost(PATIENCE)
                .pollInterval(GLANCE)
                .untilAsserted(() -> assertThat(wire)
                        .as("waiting for a data frame naming %s in %s", sessionId, wire)
                        .anyMatch(line -> squeeze(line).startsWith("data:") && line.contains(sessionId)));
    }

    /**
     * @param wire the lines collected so far
     * @param frame the frame to count
     * @return how many times that frame has arrived
     */
    private static long countOf(final List<String> wire, final String frame) {
        return wire.stream().filter(line -> squeeze(line).equals(frame)).count();
    }

    /**
     * @param line a line read from the wire
     * @return that line with every space removed
     */
    private static String squeeze(final String line) {
        return line.replace(" ", "");
    }

    /**
     * Opens a stream for the given player and starts collecting its lines in the background.
     *
     * <p>The response future settles as soon as the headers arrive, because a line body handler is a
     * streaming one — so the status can be asserted here, on the test's own thread, where a failure
     * is reported rather than swallowed. Only the endless part, draining the body, is handed to
     * another thread.
     *
     * <p>The returned list is written by that thread and read by this one, so it has to tolerate
     * concurrent traversal — hence a copy-on-write list rather than a plain one.
     *
     * @param player the credential to subscribe with
     * @return the growing list of lines seen on the wire
     * @throws Exception if the stream cannot be opened within the allotted patience
     */
    private List<String> watch(final Admission player) throws Exception {
        final HttpRequest request = HttpRequest.newBuilder(eventsUri(player.sessionId()))
                .header(SessionController.PLAYER_TOKEN_HEADER, player.playerToken())
                .GET()
                .build();
        final HttpResponse<Stream<String>> response = client.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                .get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).get().asString().contains("text/event-stream");

        final List<String> wire = new CopyOnWriteArrayList<>();
        // Nothing joins this: the body has no end until the client is shut down, and the shutdown
        // makes the drain throw, which is the intended way for it to stop.
        CompletableFuture.runAsync(() -> response.body().forEach(wire::add));
        return wire;
    }

    /**
     * @param name the display name to open the lobby under
     * @return the facilitator's admission
     * @throws IOException if the request cannot be sent
     * @throws InterruptedException if the calling thread is interrupted
     */
    private Admission createSession(final String name) throws IOException, InterruptedException {
        final HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(uri("/api/v1/sessions"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(nameRequest(name)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        return admissionFrom(response.body());
    }

    /**
     * @param joinCode the code to join with
     * @param name the display name to join under
     * @return the joining player's admission
     * @throws IOException if the request cannot be sent
     * @throws InterruptedException if the calling thread is interrupted
     */
    private Admission join(final String joinCode, final String name) throws IOException, InterruptedException {
        final HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(uri("/api/v1/sessions/" + joinCode + "/players"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(nameRequest(name)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return admissionFrom(response.body());
    }

    /**
     * @param facilitator the player entitled to start play
     * @throws IOException if the request cannot be sent
     * @throws InterruptedException if the calling thread is interrupted
     */
    private void startPlay(final Admission facilitator) throws IOException, InterruptedException {
        final HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(uri("/api/v1/sessions/" + facilitator.sessionId() + "/start"))
                        .header(SessionController.PLAYER_TOKEN_HEADER, facilitator.playerToken())
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    /**
     * @param sessionId the session to stream
     * @return the events endpoint for that session
     */
    private URI eventsUri(final String sessionId) {
        return uri("/api/v1/sessions/" + sessionId + "/events");
    }

    /**
     * @param path an application path
     * @return that path against the randomly assigned port
     */
    private URI uri(final String path) {
        return URI.create("http://localhost:" + port + path);
    }

    /**
     * @param name the display name to send
     * @return a request body carrying it
     */
    private static String nameRequest(final String name) {
        return "{\"displayName\":\"" + name + "\"}";
    }

    /**
     * Pulls the three fields the tests need out of an admission body.
     *
     * <p>Reading them with a regular expression rather than a JSON library keeps this class free of
     * a parser dependency it would otherwise use once; the shapes asserted properly live in
     * {@link SessionControllerIntegrationTest}.
     *
     * @param body an admission response body
     * @return the identifier, join code and credential it carries
     */
    private static Admission admissionFrom(final String body) {
        return new Admission(field(body, "sessionId"), field(body, "joinCode"), field(body, "playerToken"));
    }

    /**
     * @param body a JSON body
     * @param name the field to read
     * @return the first string value that field holds
     */
    private static String field(final String body, final String name) {
        final Matcher found = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        assertThat(found.find()).as("%s in %s", name, body).isTrue();
        return found.group(1);
    }

    /**
     * What a test needs to remember about a player it created.
     *
     * @param sessionId the session the player belongs to
     * @param joinCode the code others join with
     * @param playerToken the player's credential
     */
    private record Admission(String sessionId, String joinCode, String playerToken) {
    }
}
