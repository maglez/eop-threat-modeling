package org.maglez.eop.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The score of one game, computed from the cards that have been played.
 *
 * <p>This is the whole of the shipped scoring rule, and it is deliberately small: <strong>one point for a threat on your card, plus one
 * point for taking the trick</strong> (PRD §3.4). It must not be embellished. A richer system was tried and abandoned — four points for a
 * threat on your own card, three, two and one for the first, second and third threat on other people's, one for the trick, two for a face
 * card, three for an ace — not because it was a
 * worse game but because playtesters could not compute it at the table and looked to the facilitator at the end of every round to find out
 * who had scored. A server does not have that limitation, so a richer rule is cheap to add later and is worth recording as an option; it is
 * explicitly not part of this story.</p>
 *
 * <p>The score is <em>derived</em>, not accumulated. Every total here is a pure function of the plays and of which play took each trick, so
 * it cannot drift away from the play that produced it, and there is no running tally anywhere that could be wrong. That also means the
 * server is the only thing that ever computes a score: a client is shown totals and never asserts its own.</p>
 *
 * <p>The list of tricks may include one that is still in progress. Threat points are earned when a card is played with a threat connected
 * to it, so they count immediately; the trick point is earned by taking the trick, so it only counts once the trick has a winner. The
 * consequence is that a running score only ever grows, and that a player watching the sheet sees their threat point the moment they play
 * rather than when the trick is swept.</p>
 *
 * <p>What this class deliberately does <em>not</em> do is decide that the game has ended, or move the session to a terminal state. It
 * answers what the score is; whether the score is final is a fact about the session, which the session owns.</p>
 */
public final class ScoreSheet {

    private final List<ScoredPlay> rows;

    private final List<Standing> standings;

    /**
     * Captures per player: player ID → (STRIDE category → count of cards captured).
     *
     * <p>Computed once in {@link #of(List, List)} while the {@link Trick} objects are still in hand,
     * so the trick boundary is exact rather than reconstructed from the flattened row list.</p>
     */
    private final Map<UUID, Map<StrideCategory, Integer>> capturedBySuitByPlayer;

    private ScoreSheet(final List<ScoredPlay> rows, final List<Standing> standings,
            final Map<UUID, Map<StrideCategory, Integer>> capturedBySuitByPlayer) {
        this.rows = List.copyOf(rows);
        this.standings = List.copyOf(standings);
        // Defensive copy using Map.copyOf so SpotBugs EI_EXPOSE_REP is satisfied.
        // Each inner map is also copied to prevent mutation of the original EnumMap.
        final Map<UUID, Map<StrideCategory, Integer>> copy = new HashMap<>();
        for (final Map.Entry<UUID, Map<StrideCategory, Integer>> entry : capturedBySuitByPlayer.entrySet()) {
            copy.put(entry.getKey(), Map.copyOf(entry.getValue()));
        }
        this.capturedBySuitByPlayer = Map.copyOf(copy);
    }

    /**
     * Scores a game from its seated players and its tricks.
     *
     * <p>Tricks are read in sequence order regardless of the order they arrive in, so the rows come out in the order the cards were played.
     * An unresolved trick — the one currently on the table — contributes its plays' threat points but no trick point, because nobody has
     * taken it yet. In a real game at most one trick is unresolved at a time, but that is a property of how the game is played rather than
     * something checked here: a sheet scored from several unresolved tricks is arithmetically correct, merely describing a position no
     * session can reach.</p>
     *
     * <p>A play by somebody who is not seated, or a play whose seat disagrees with its player's seat, is a contradiction rather than a
     * client error, and is refused as one. Neither is reachable through the game's own writes; both are cheap to rule out here and would
     * otherwise produce a plausible but wrong sheet.</p>
     *
     * @param players the seated players, at least one
     * @param tricks  the tricks played so far, possibly empty, possibly including one still in progress
     * @return the score of the game as it stands
     * @throws ScoreNotDerivableException if the game contradicts itself: nobody seated, one player seated twice, two tricks colliding
     *                                   on identity or sequence, or a play that cannot be attributed to a seated player at the seat
     *                                   it names
     */
    public static ScoreSheet of(final List<Player> players, final List<Trick> tricks) {
        Objects.requireNonNull(players, "players is required");
        Objects.requireNonNull(tricks, "tricks is required");
        if (players.isEmpty()) {
            throw ScoreNotDerivableException.noPlayers();
        }
        final Map<UUID, Player> seated = index(players);
        final List<ScoredPlay> scored = new ArrayList<>();
        final Map<UUID, Map<StrideCategory, Integer>> captures = new HashMap<>();
        for (final Trick trick : inSequenceOrder(tricks)) {
            final Optional<TrickPlay> winner = trick.winner();
            for (final TrickPlay play : trick.plays()) {
                final Player player = seated.get(play.playerId());
                if (player == null) {
                    throw ScoreNotDerivableException.playByUnseatedPlayer(play.trickPlayId());
                }
                if (player.seatOrder() != play.seatOrder()) {
                    throw ScoreNotDerivableException.playSeatMismatch(play.trickPlayId(), play.seatOrder(), player.seatOrder());
                }
                final boolean tookTrick = winner.isPresent() && winner.get().trickPlayId().equals(play.trickPlayId());
                scored.add(ScoredPlay.of(player, play, tookTrick));
            }
            // Attribute all cards in this trick to the winner, using the Trick's own winner() —
            // exact, not reconstructed from the flattened row list.
            winner.ifPresent(winningPlay -> {
                final Map<StrideCategory, Integer> tally =
                        captures.computeIfAbsent(winningPlay.playerId(), id -> new EnumMap<>(StrideCategory.class));
                for (final TrickPlay play : trick.plays()) {
                    tally.merge(play.card().suit(), 1, Integer::sum);
                }
            });
        }
        // Wrap each inner map as unmodifiable
        final Map<UUID, Map<StrideCategory, Integer>> unmodifiable = new HashMap<>();
        for (final Map.Entry<UUID, Map<StrideCategory, Integer>> entry : captures.entrySet()) {
            unmodifiable.put(entry.getKey(), Collections.unmodifiableMap(entry.getValue()));
        }
        return new ScoreSheet(scored, rank(players, scored), Collections.unmodifiableMap(unmodifiable));
    }

    private static Map<UUID, Player> index(final List<Player> players) {
        final Map<UUID, Player> seated = new LinkedHashMap<>();
        for (final Player player : players) {
            Objects.requireNonNull(player, "A player is required");
            if (seated.put(player.playerId(), player) != null) {
                throw ScoreNotDerivableException.playerSeatedTwice(player.playerId());
            }
        }
        return seated;
    }

    private static List<Trick> inSequenceOrder(final List<Trick> tricks) {
        final Set<UUID> identifiers = new HashSet<>();
        final Set<Integer> sequences = new HashSet<>();
        for (final Trick trick : tricks) {
            Objects.requireNonNull(trick, "A trick is required");
            if (!identifiers.add(trick.trickId())) {
                throw ScoreNotDerivableException.trickRepeated(trick.trickId());
            }
            if (!sequences.add(trick.sequence())) {
                throw ScoreNotDerivableException.sequenceRepeated(trick.sequence());
            }
        }
        final List<Trick> ordered = new ArrayList<>(tricks);
        ordered.sort(Comparator.comparingInt(Trick::sequence));
        return ordered;
    }

    private static List<Standing> rank(final List<Player> players, final List<ScoredPlay> scored) {
        final Map<UUID, Integer> totals = new HashMap<>();
        for (final Player player : players) {
            totals.put(player.playerId(), 0);
        }
        for (final ScoredPlay row : scored) {
            totals.merge(row.playerId(), row.points(), Integer::sum);
        }
        final List<Player> ranked = new ArrayList<>(players);
        ranked.sort(Comparator.comparingInt((final Player player) -> totals.get(player.playerId())).reversed()
                .thenComparingInt(Player::seatOrder));
        final List<Standing> standings = new ArrayList<>();
        int position = 0;
        int place = 0;
        Integer previous = null;
        for (final Player player : ranked) {
            place++;
            final int points = totals.get(player.playerId());
            if (previous == null || points != previous) {
                position = place;
                previous = points;
            }
            final boolean tied = Collections.frequency(totals.values(), points) > 1;
            standings.add(new Standing(player.playerId(), player.seatOrder(), player.displayName(), points, position, tied));
        }
        return standings;
    }

    /**
     * The Score Card rows, in the order the cards were played.
     *
     * @return one row per play, oldest first
     */
    public List<ScoredPlay> rows() {
        return rows;
    }

    /**
     * Every seated player's total, best first, with a seat order tiebreak so that the list itself is stable.
     *
     * <p>The order is presentation only. Two players on the same total hold the same {@link Standing#position()}, and the seat used to
     * order them says nothing about which of them did better.</p>
     *
     * @return one standing per seated player, including players who have scored nothing
     */
    public List<Standing> standings() {
        return standings;
    }

    /**
     * Whoever is in front, which may be several players at once.
     *
     * @return every standing in first position — more than one when the lead is shared
     */
    public List<Standing> leaders() {
        return standings.stream().filter(standing -> standing.position() == 1).toList();
    }

    /**
     * Whether the lead is shared.
     *
     * @return true when more than one player holds first position
     */
    public boolean leadIsShared() {
        return leaders().size() > 1;
    }

    /**
     * One player's total.
     *
     * @param playerId identifier of a seated player
     * @return that player's points, zero if they have not scored
     * @throws ScoreNotDerivableException if the player is not seated in this game
     */
    public int pointsOf(final UUID playerId) {
        Objects.requireNonNull(playerId, "playerId is required");
        return standings.stream()
                .filter(standing -> standing.playerId().equals(playerId))
                .mapToInt(Standing::points)
                .findFirst()
                .orElseThrow(() -> ScoreNotDerivableException.playerNotSeated(playerId));
    }

    /**
     * How many cards of each STRIDE category each player captured, keyed by player ID.
     *
     * <p>A player captures every card played in a trick they win. This map is computed once
     * during {@link #of(List, List)} while the {@link Trick} objects are still in hand, so the
     * trick boundary is exact — the winner is identified by {@link Trick#winner()}, not
     * reconstructed by scanning the flattened row list.
     *
     * <p>Players who captured no cards are absent from the outer map. An unresolved trick
     * (no winner yet) contributes no captures.
     *
     * @return an unmodifiable map from player ID to an unmodifiable map from STRIDE category to
     *         capture count; players who captured nothing are absent from the outer map
     */
    public Map<UUID, Map<StrideCategory, Integer>> capturedBySuitByPlayer() {
        return capturedBySuitByPlayer;
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ScoreSheet that)) {
            return false;
        }
        return rows.equals(that.rows) && standings.equals(that.standings);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rows, standings);
    }

    /**
     * Describes the sheet without reproducing it.
     *
     * <p>Every row names a card that is already on the table, so nothing here is confidential, but a sheet late in a game is long and a log
     * line is not where anybody reads a score. The shape is what is useful in a trace; the sheet itself is what the endpoint returns.</p>
     *
     * @return the number of rows, the number of players and the leading total
     */
    @Override
    public String toString() {
        final int leading = leaders().stream().mapToInt(Standing::points).findFirst().orElse(0);
        return "ScoreSheet[rows=" + rows.size() + ", players=" + standings.size() + ", leading=" + leading + "]";
    }
}
