package org.maglez.eop.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Test data builder for {@link Trick}.
 *
 * <p>Builds through {@link Trick#reconstitute} rather than through
 * {@link Trick#open} followed by a run of {@link Trick#play} calls, so a test can
 * state a mid-trick or a resolved trick directly instead of replaying the moves
 * that would have produced it. The constructor invariants still run, so a builder
 * cannot assemble a trick the domain would refuse.
 *
 * <p>Because {@code Trick} requires the first play to belong to the leading seat,
 * {@link #withPlays} does not silently reorder anything: a test that states an
 * impossible ordering gets the exception, which is usually the point.
 */
public final class TrickBuilder {

    private UUID trickId = new UUID(1000, 1);
    private int sequence = 1;
    private int leaderSeat;
    private List<TrickPlay> plays = List.of();
    private TrickPlay winner;

    private TrickBuilder() {
    }

    /**
     * Starts a builder describing an open, empty, unresolved first trick led from
     * seat zero.
     *
     * @return a new builder
     */
    public static TrickBuilder aTrick() {
        return new TrickBuilder();
    }

    /**
     * @param value the trick identifier to use
     * @return this builder
     */
    public TrickBuilder withTrickId(final UUID value) {
        this.trickId = value;
        return this;
    }

    /**
     * @param value the one-based position of this trick within the session
     * @return this builder
     */
    public TrickBuilder withSequence(final int value) {
        this.sequence = value;
        return this;
    }

    /**
     * @param value the seat leading this trick
     * @return this builder
     */
    public TrickBuilder withLeaderSeat(final int value) {
        this.leaderSeat = value;
        return this;
    }

    /**
     * @param value the plays made so far, in play order
     * @return this builder
     */
    public TrickBuilder withPlays(final List<TrickPlay> value) {
        this.plays = value;
        return this;
    }

    /**
     * @param value the plays made so far, in play order
     * @return this builder
     */
    public TrickBuilder withPlays(final TrickPlay... value) {
        this.plays = List.of(value);
        return this;
    }

    /**
     * Appends one play, leaving the others alone.
     *
     * @param value the play to append
     * @return this builder
     */
    public TrickBuilder andPlay(final TrickPlay value) {
        final List<TrickPlay> extended = new ArrayList<>(this.plays);
        extended.add(value);
        this.plays = List.copyOf(extended);
        return this;
    }

    /**
     * @param value the winning play, or null while the trick is unresolved
     * @return this builder
     */
    public TrickBuilder withWinner(final TrickPlay value) {
        this.winner = value;
        return this;
    }

    /**
     * @return the trick described by this builder
     */
    public Trick build() {
        return Trick.reconstitute(trickId, sequence, leaderSeat, plays, winner);
    }
}
