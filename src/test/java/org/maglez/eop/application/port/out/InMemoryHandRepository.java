package org.maglez.eop.application.port.out;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import org.maglez.eop.domain.Hands;

/**
 * A hand repository that keeps one session's deal in memory.
 *
 * <p>Hand written rather than mocked, for the reason
 * {@link InMemoryCardRepository} gives: the use cases above this port are thin,
 * so what is worth asserting is which arguments they pass down and which answer
 * they make of what comes back, and a real object reads better than a script of
 * expectations that mostly restates the implementation.
 *
 * <p>It deliberately does <em>not</em> reproduce the adapter's conditional
 * writes. {@link HandRepository#recordDeal} refuses a second deal by writing the
 * opening leader seat only where none is recorded, and that behaviour belongs to
 * the adapter and its integration tests, where a real database can arbitrate
 * between two callers. Reimplementing it here would only assert that this class
 * agrees with itself, and would quietly become the thing under test.
 *
 * <p>Every write appends its name to the shared call log so a test can pin the
 * order of calls across collaborators — the one property a use case can get
 * wrong without any single collaborator noticing.
 */
public final class InMemoryHandRepository implements HandRepository {

    /** Seat value standing for "no leader recorded", which is every undealt session. */
    private static final int NO_LEADER = -1;

    private final List<String> order;

    private final List<UUID> sessionsAsked = new ArrayList<>();

    private Hands dealt;

    private int leaderSeat = NO_LEADER;

    private Hands recordedHands;

    private int recordedLeaderSeat = NO_LEADER;

    private Instant recordedAt;

    private int recordDealCalls;

    /**
     * Creates an empty repository, as a session looks before its deal.
     *
     * @param order the shared call log this repository appends its writes to
     */
    public InMemoryHandRepository(final List<String> order) {
        this.order = Objects.requireNonNull(order, "order is required");
    }

    /**
     * Seeds a deal that has already happened.
     *
     * <p>Returned fluently so a test can seed and assign in one expression.
     *
     * @param hands the hands to answer reads with
     * @param currentLeaderSeat the seat recorded as leading
     * @return this repository
     */
    public InMemoryHandRepository seededWith(final Hands hands, final int currentLeaderSeat) {
        this.dealt = Objects.requireNonNull(hands, "hands is required");
        this.leaderSeat = currentLeaderSeat;
        return this;
    }

    /**
     * Seeds hands with no leader seat recorded, which is a state the real deal cannot produce.
     *
     * <p>{@code recordDeal} writes the hands and the leader seat in one transaction, so a session
     * with hands and no leader does not arise from any sequence of legal calls. It is seeded here
     * only to reach the guard that answers it, because the alternative to a guard is that the use
     * case reads an empty optional and fails on it somewhere less obvious. Treat this as the
     * partial-write case it is, not as a state worth supporting.
     *
     * @param hands the hands to answer reads with
     * @return this repository
     */
    public InMemoryHandRepository seededWithNoLeader(final Hands hands) {
        this.dealt = Objects.requireNonNull(hands, "hands is required");
        this.leaderSeat = NO_LEADER;
        return this;
    }

    @Override
    public Optional<Hands> findBySessionId(final UUID sessionId) {
        sessionsAsked.add(sessionId);
        return Optional.ofNullable(dealt);
    }

    @Override
    public OptionalInt findCurrentLeaderSeat(final UUID sessionId) {
        sessionsAsked.add(sessionId);
        return leaderSeat == NO_LEADER ? OptionalInt.empty() : OptionalInt.of(leaderSeat);
    }

    @Override
    public void recordDeal(final UUID sessionId, final Hands hands, final int openingLeaderSeat,
            final Instant occurredAt) {
        order.add("recordDeal");
        recordDealCalls++;
        recordedHands = hands;
        recordedLeaderSeat = openingLeaderSeat;
        recordedAt = occurredAt;
        dealt = hands;
        leaderSeat = openingLeaderSeat;
    }

    @Override
    public void clearHandsForNewGame(final UUID sessionId) {
        order.add("clearHandsForNewGame");
        dealt = null;
        leaderSeat = NO_LEADER;
    }

    /**
     * Answers how many deals were recorded.
     *
     * <p>A refusal test asserts this is zero: a use case that throws after
     * writing has still written, and only a count can tell the two apart.
     *
     * @return the number of {@code recordDeal} calls
     */
    public int recordDealCalls() {
        return recordDealCalls;
    }

    /**
     * Answers the hands handed to the last recorded deal.
     *
     * @return the hands as the use case built them, or {@code null} if no deal was recorded
     */
    public Hands recordedHands() {
        return recordedHands;
    }

    /**
     * Answers the opening leader seat handed to the last recorded deal.
     *
     * @return the seat, or {@code -1} if no deal was recorded
     */
    public int recordedLeaderSeat() {
        return recordedLeaderSeat;
    }

    /**
     * Answers the instant handed to the last recorded deal.
     *
     * @return the instant, or {@code null} if no deal was recorded
     */
    public Instant recordedAt() {
        return recordedAt;
    }

    /**
     * Answers every session identifier this repository was asked about.
     *
     * <p>Every read on this port leads with a session identifier, because
     * {@link Hands} carries none. A test asserting cross-session scoping needs
     * to see that the identifier reaching the port is the one the caller named
     * and not one recovered from somewhere else.
     *
     * @return the session identifiers, in the order they were asked
     */
    public List<UUID> sessionsAsked() {
        return List.copyOf(sessionsAsked);
    }
}
