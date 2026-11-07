package org.maglez.eop.application.port.out;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import org.maglez.eop.domain.Trick;
import org.maglez.eop.domain.TrickPlay;

/**
 * A trick repository that keeps one session's current trick in memory.
 *
 * <p>Hand written for the same reason as {@link InMemoryHandRepository}, and
 * with the same deliberate omission: the compare-and-set on the recorded leader
 * seat that every write on this port carries is not reproduced here. Whether a
 * conditional update matches a row is a question only a database can answer, so
 * it is settled in the adapter's integration tests. What a use case test can
 * settle is which witness the use case passed down — and passing the wrong one,
 * or passing one taken from a request body, is the defect this double is shaped
 * to expose.
 *
 * <p>Each write records its arguments and appends its name to the shared call
 * log, so a test can assert both that a trick was opened before a play was
 * appended and that no trick was opened at all when the play was refused.
 */
public final class InMemoryTrickRepository implements TrickRepository {

    /**
     * One recorded resolution.
     *
     * @param trick the resolved trick, as the domain returned it
     * @param expectedLeaderSeat the compare-and-set witness the use case passed
     * @param nextLeaderSeat the seat the use case said should lead next, or empty when it said none does
     * @param occurredAt the instant the use case supplied
     */
    public record Resolution(Trick trick, int expectedLeaderSeat, OptionalInt nextLeaderSeat, Instant occurredAt) {
    }

    /**
     * One recorded appended play.
     *
     * @param trickId the trick the play was appended to
     * @param expectedLeaderSeat the compare-and-set witness the use case passed
     * @param play the play, as {@code acceptPlay} returned it
     */
    public record Appended(UUID trickId, int expectedLeaderSeat, TrickPlay play) {
    }

    /**
     * One recorded opened trick.
     *
     * @param trick the trick as the use case built it
     * @param expectedLeaderSeat the compare-and-set witness the use case passed
     * @param occurredAt the instant the use case supplied
     */
    public record Opened(Trick trick, int expectedLeaderSeat, Instant occurredAt) {
    }

    private final List<String> order;

    private final List<Opened> opened = new ArrayList<>();

    private final List<Appended> appended = new ArrayList<>();

    private final List<Resolution> resolutions = new ArrayList<>();

    private final List<Trick> history = new ArrayList<>();

    private Trick current;

    private UUID tricksAskedFor;

    /**
     * Creates an empty repository, as a session looks before its first lead.
     *
     * @param order the shared call log this repository appends its writes to
     */
    public InMemoryTrickRepository(final List<String> order) {
        this.order = Objects.requireNonNull(order, "order is required");
    }

    /**
     * Seeds the trick that reads should answer with.
     *
     * @param trick the current trick, resolved or not
     * @return this repository
     */
    public InMemoryTrickRepository seededWith(final Trick trick) {
        this.current = Objects.requireNonNull(trick, "trick is required");
        return this;
    }

    /**
     * Seeds the tricks that a whole-history read should answer with.
     *
     * <p>Kept apart from the current trick on purpose. A score is derived from every trick played,
     * and a double that answered the history with whatever the last write happened to leave behind
     * would let a use case that read the wrong thing still pass.
     *
     * @param tricks the session's tricks, in the order they were played
     * @return this repository
     */
    public InMemoryTrickRepository seededWithHistory(final Trick... tricks) {
        this.history.clear();
        this.history.addAll(List.of(tricks));
        return this;
    }

    @Override
    public Optional<Trick> findCurrentTrick(final UUID sessionId) {
        return Optional.ofNullable(current);
    }

    @Override
    public List<Trick> findTricks(final UUID sessionId) {
        this.tricksAskedFor = sessionId;
        return List.copyOf(history);
    }

    @Override
    public void openTrick(final UUID sessionId, final Trick trick, final int expectedLeaderSeat,
            final Instant occurredAt) {
        order.add("openTrick");
        opened.add(new Opened(trick, expectedLeaderSeat, occurredAt));
        current = trick;
    }

    @Override
    public void appendPlay(final UUID sessionId, final UUID trickId, final int expectedLeaderSeat,
            final TrickPlay play) {
        order.add("appendPlay");
        appended.add(new Appended(trickId, expectedLeaderSeat, play));
    }

    @Override
    public void recordResolution(final UUID sessionId, final Trick resolved, final int expectedLeaderSeat,
            final OptionalInt nextLeaderSeat, final Instant occurredAt) {
        order.add("recordResolution");
        resolutions.add(new Resolution(resolved, expectedLeaderSeat, nextLeaderSeat, occurredAt));
        current = resolved;
    }

    @Override
    public void clearTricksForNewGame(final UUID sessionId) {
        order.add("clearTricksForNewGame");
        current = null;
        history.clear();
    }

    /**
     * Answers every trick this repository was asked to open.
     *
     * <p>Empty is the assertion that matters most: a refusal that has already
     * opened a trick has left an empty trick behind that nobody asked for, and
     * only this list can tell that apart from a clean refusal.
     *
     * @return the opened tricks, in the order they were opened
     */
    public List<Opened> opened() {
        return List.copyOf(opened);
    }

    /**
     * Answers every play this repository was asked to append.
     *
     * @return the appended plays, in the order they were appended
     */
    public List<Appended> appended() {
        return List.copyOf(appended);
    }

    /**
     * Answers every resolution this repository was asked to record.
     *
     * @return the resolutions, in the order they were recorded
     */
    public List<Resolution> resolutions() {
        return List.copyOf(resolutions);
    }

    /**
     * Answers which session the history was last read for.
     *
     * <p>The assertion that matters is that it is the session the caller authorised against, and not
     * some other one: a read scoped to the wrong session would hand a member of one game the history
     * of another.
     *
     * @return the session identifier the history was read for, empty if it was never read
     */
    public Optional<UUID> tricksAskedFor() {
        return Optional.ofNullable(tricksAskedFor);
    }
}
