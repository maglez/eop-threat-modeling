package org.maglez.eop.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * One time around the table: each participating player lays a single card face up, and the highest
 * card of the suit that was led takes the trick unless somebody played trump.
 *
 * <p>A trick is immutable. Every rule here answers a question and returns a new instance rather than
 * mutating this one, so a rejected play leaves no trace and a resolved trick cannot be quietly
 * re-resolved.
 *
 * <p>Deliberately <em>not</em> a fixed number of plays. Because EOP-14 deals the whole deck and hands
 * off the remainder to the lowest seats (ADR-023), at 68 cards three of the four supported table
 * sizes divide unevenly — four players is the exception, at seventeen cards each — so the final trick
 * is short at three, five and six players: some seats have already run out of cards.
 * A trick is therefore one card from each seat that still held a card when it opened, which is
 * why {@link #seatToPlay(Collection)} and {@link #isComplete(Collection)} both need to be told
 * which seats still hold cards rather than counting to the table size.
 *
 * <p>Pure domain type: no Spring, no Jakarta, no persistence annotations. The persistence adapter
 * holds its own separate mapped type.
 */
public final class Trick {

    private final UUID trickId;
    private final int sequence;
    private final int leaderSeat;
    private final List<TrickPlay> plays;
    private final TrickPlay winner;

    private Trick(
            final UUID trickId,
            final int sequence,
            final int leaderSeat,
            final List<TrickPlay> plays,
            final TrickPlay winner) {
        this.trickId = Objects.requireNonNull(trickId, "trickId is required");
        this.plays = List.copyOf(Objects.requireNonNull(plays, "plays is required"));
        this.winner = winner;

        if (sequence < 1) {
            throw new IllegalArgumentException("A trick sequence is one-based, so it cannot be " + sequence);
        }
        if (leaderSeat < 0 || leaderSeat >= GameSession.MAXIMUM_PLAYERS) {
            throw new IllegalArgumentException(
                    "A leader seat must be between 0 and "
                            + (GameSession.MAXIMUM_PLAYERS - 1)
                            + " inclusive, so it cannot be "
                            + leaderSeat);
        }
        this.sequence = sequence;
        this.leaderSeat = leaderSeat;

        final long distinctSeats = this.plays.stream().map(TrickPlay::seatOrder).distinct().count();
        if (distinctSeats != this.plays.size()) {
            throw new IllegalArgumentException("A seat cannot play twice in the same trick");
        }
        final long distinctCards =
                this.plays.stream().map(play -> play.card().cardId()).distinct().count();
        if (distinctCards != this.plays.size()) {
            throw new IllegalArgumentException("The same card cannot be played twice in the same trick");
        }
        final long distinctPlayers = this.plays.stream().map(TrickPlay::playerId).distinct().count();
        if (distinctPlayers != this.plays.size()) {
            throw new IllegalArgumentException("A player cannot hold two seats in the same trick");
        }

        final long distinctPlayIds = this.plays.stream().map(TrickPlay::trickPlayId).distinct().count();
        if (distinctPlayIds != this.plays.size()) {
            throw new IllegalArgumentException("Two plays in the same trick cannot share an identifier");
        }

        if (winner != null && !this.plays.contains(winner)) {
            throw new IllegalArgumentException("The winning play must be one of the plays in the trick");
        }
        if (!this.plays.isEmpty() && this.plays.get(0).seatOrder() != leaderSeat) {
            throw new IllegalArgumentException(
                    "The first play in a trick belongs to the leading seat "
                            + leaderSeat
                            + ", not to seat "
                            + this.plays.get(0).seatOrder());
        }
        int previousOffset = -1;
        for (final TrickPlay played : this.plays) {
            final int offset = (played.seatOrder() - leaderSeat + GameSession.MAXIMUM_PLAYERS)
                    % GameSession.MAXIMUM_PLAYERS;
            if (offset <= previousOffset) {
                throw new IllegalArgumentException(
                        "Plays run clockwise from the leading seat, so seat "
                                + played.seatOrder()
                                + " cannot play after a seat that is further round the table from seat "
                                + leaderSeat);
            }
            previousOffset = offset;
        }
    }

    /**
     * Opens an empty trick for the given leading seat.
     *
     * <p>The leading seat of the first trick is derived from the deal rather than chosen: it is the
     * seat holding the lowest-ranked Tampering card actually dealt (see {@link Hands#openingLeaderSeat()}).
     * The leading seat of every later trick is the seat that took the previous one.
     *
     * @param trickId the identifier for the new trick, minted by the caller (ADR-018)
     * @param sequence the one-based position of this trick within the session
     * @param leaderSeat the seat that plays first and whose card sets the led suit
     * @return an open trick with no plays and no winner
     */
    public static Trick open(final UUID trickId, final int sequence, final int leaderSeat) {
        return new Trick(trickId, sequence, leaderSeat, List.of(), null);
    }

    /**
     * Rebuilds a trick from stored state.
     *
     * <p><strong>This is not a validating gate for the rules of play, and must not be mistaken for
     * one.</strong> The constructor invariants it runs are the ones a trick can check on its own: one
     * play per seat, one play per card, plays running clockwise from the leading seat, a first play
     * belonging to that seat, one player across the plays, one identifier per play, and a winner drawn
     * from the plays. It cannot check follow-suit, because
     * follow-suit is a question about a hand and a trick holds no hands — so a stored row set in which
     * a player failed to follow suit will rehydrate without complaint and will then resolve a winner.
     * The place that refuses an illegal play is {@link #acceptPlay(int, TrickPlay, Hands)}, once, on
     * the way in. Slice B's persistence adapter therefore has to be trusted to store only what
     * {@code acceptPlay} produced, rather than relying on this method to re-check it.
     *
     * <p>This is the only path used when a client reconnects or refreshes, because the database is the
     * only authority on where a game has got to (ADR-014). The invariants in the constructor run
     * against stored rows as well as against fresh ones, so a row set that could not have arisen from
     * legal play fails loudly here rather than producing a game in an impossible state.
     *
     * @param trickId the stored identifier
     * @param sequence the stored one-based position within the session
     * @param leaderSeat the stored leading seat
     * @param plays the stored plays, in play order
     * @param winner the stored winning play, or {@code null} if the trick is not yet resolved
     * @return the reconstituted trick
     */
    public static Trick reconstitute(
            final UUID trickId,
            final int sequence,
            final int leaderSeat,
            final List<TrickPlay> plays,
            final TrickPlay winner) {
        return new Trick(trickId, sequence, leaderSeat, plays, winner);
    }

    /**
     * The suit that was led, which is the suit of the first card played.
     *
     * @return the led suit, or empty while the trick has no plays
     */
    public Optional<StrideCategory> ledSuit() {
        return plays.isEmpty() ? Optional.empty() : Optional.of(plays.get(0).card().suit());
    }

    /**
     * Checks that a card may legally be played into this trick from the given hand, and returns the
     * card as it was actually dealt if it may.
     *
     * <p>The whole follow-suit rule lives here, in four branches:
     *
     * <ol>
     *   <li>The hand must actually hold the card. This is the check that stops a card being played
     *       twice, and stops a player playing a card dealt to somebody else.
     *   <li>If no suit has been led yet, the leader may play anything at all.
     *   <li>A card of the led suit is always legal.
     *   <li>Otherwise the play is legal only if the hand holds no card of the led suit. A player who
     *       can follow suit must.
     * </ol>
     *
     * <p>Note what the last branch permits: a player holding none of the led suit may play
     * <em>any</em> card, including a trump. That is how trump gets into a trick, and it is deliberate
     * rather than an oversight.
     *
     * <p>This check is the server's, not the client's. A user interface that only offers legal cards
     * is a courtesy; the cards a client is willing to offer say nothing about the request a client is
     * able to send.
     *
     * <p>The card is resolved out of the hand before any rule is applied to it, and the resolved card
     * is what this method returns and what the caller must go on to play. This matters more than it
     * looks. A card is named in a request by identifier, and the suit and rank that arrive alongside
     * that identifier are the caller's claim, not the deck's fact. Checking possession by identifier
     * and then reading the suit off the submitted object would let a player who holds the two of
     * Tampering submit that identifier labelled as the ace of Elevation of Privilege: possession
     * passes, follow-suit is escaped because the claimed suit is trump, and the trick is taken by a
     * card that was never dealt. Resolving first makes the submitted suit and rank inert.
     *
     * @param card the card the player is attempting to play, named by identifier
     * @param hand the hand the player actually holds
     * @return the card as it was actually dealt, which is the card that may be played
     * @throws CardNotInHandException if the hand does not hold the card
     * @throws MustFollowSuitException if the hand holds the led suit and the card is of another suit
     */
    public Card assertLegalPlay(final Card card, final Hand hand) {
        Objects.requireNonNull(hand, "hand is required");

        final Card resolved = hand.resolve(card);

        final Optional<StrideCategory> led = ledSuit();
        if (led.isEmpty()) {
            return resolved;
        }

        final StrideCategory ledSuit = led.get();
        if (resolved.suit() != ledSuit && hand.holdsSuit(ledSuit)) {
            throw new MustFollowSuitException(ledSuit, resolved.suit());
        }
        return resolved;
    }

    /**
     * Checks that the given seat is the seat whose turn it is, and does nothing if it is.
     *
     * <p>Like the follow-suit check, this belongs on the server, and the seat passed in must have been
     * derived from the credential the request presented rather than from a player identifier the
     * request supplied. Otherwise a caller could play on another player's behalf simply by naming them.
     *
     * @param seatOrder the seat attempting to play
     * @param seatsHoldingCards the seats that still hold at least one card
     * @throws OutOfTurnException if it is another seat's turn
     * @throws IllegalStateException if the trick is complete, or was opened on a seat that holds no cards
     */
    public void assertSeatMayPlay(final int seatOrder, final Collection<Integer> seatsHoldingCards) {
        final OptionalInt expected = seatToPlay(seatsHoldingCards);
        if (expected.isEmpty()) {
            // Two different states both leave no seat to play, and saying "complete" for the second
            // sends a reader looking in the wrong place: a trick with no plays at all is not complete,
            // it was opened on a seat that holds no cards and can never be played into.
            final String reason = plays.isEmpty()
                    ? " was opened on seat " + leaderSeat + ", which holds no cards, so no seat can play into it"
                    : " is complete, so seat " + seatOrder + " cannot play into it";
            throw new IllegalStateException(describe() + reason);
        }
        if (expected.getAsInt() != seatOrder) {
            throw new OutOfTurnException(expected.getAsInt(), seatOrder);
        }
    }

    /**
     * Works out which seat plays next.
     *
     * <p>Play is clockwise, which is ascending seat order wrapping back to zero (ADR-019). The naive
     * form of this rule — leader's seat plus the number of cards already played — is correct only
     * while every seat still holds a card, and ADR-023 makes that false for the last trick at every
     * player count. The general form implemented here is <em>the next seat clockwise from the last
     * play that has not yet played in this trick and still holds a card</em>.
     *
     * <p>Seats that do not exist at this table are simply absent from {@code seatsHoldingCards} and so
     * are skipped by the same test that skips seats which have run out of cards.
     *
     * @param seatsHoldingCards the seats that still hold at least one card
     * @return the seat that plays next, or empty if every eligible seat has played
     */
    public OptionalInt seatToPlay(final Collection<Integer> seatsHoldingCards) {
        Objects.requireNonNull(seatsHoldingCards, "seatsHoldingCards is required");

        if (plays.isEmpty()) {
            return seatsHoldingCards.contains(leaderSeat)
                    ? OptionalInt.of(leaderSeat)
                    : OptionalInt.empty();
        }

        final List<Integer> waiting = seatsHoldingCards.stream()
                .filter(seat -> !hasPlayed(seat))
                .toList();
        return SeatOrder.nextClockwise(plays.get(plays.size() - 1).seatOrder(), waiting);
    }

    /**
     * Whether every seat eligible to play in this trick has played.
     *
     * <p>The seat set is the <em>current</em> one, taken after each played card has been removed from
     * its hand — not a snapshot of who held cards when the trick opened. ADR-023 describes completion
     * in terms of the start of the trick, which this signature cannot express; the two readings happen
     * to agree, because a seat that has just emptied its hand is excluded by having already played
     * rather than by still holding a card. Pinning it here so that whoever wires this up does not have
     * to work that out, and does not have to guess which of the two the method meant.
     *
     * <p>A trick with no plays at all is never complete, even if no seat can play into it. That state
     * means the trick was opened on a seat holding no cards, which is a different problem with a
     * different answer, and {@link #assertSeatMayPlay(int, Collection)} names it as such.
     *
     * @param seatsHoldingCards the seats that still hold at least one card
     * @return true when no seat is left to play
     */
    public boolean isComplete(final Collection<Integer> seatsHoldingCards) {
        return !plays.isEmpty() && seatToPlay(seatsHoldingCards).isEmpty();
    }

    /**
     * Whether the given seat has already played into this trick.
     *
     * @param seatOrder the seat to test
     * @return true if that seat has a play in this trick
     */
    public boolean hasPlayed(final int seatOrder) {
        return plays.stream().anyMatch(play -> play.seatOrder() == seatOrder);
    }

    /**
     * Plays a card into the trick, having first checked every rule that governs doing so.
     *
     * <p>This is the only way in from outside the domain, and it exists because the alternative did
     * not work. The three steps below used to be three separate public calls that a caller was trusted
     * to make in the right order, documented and nothing more; a caller who forgot one got an illegal
     * play accepted in silence. Two optional checks in front of an unguarded mutator are not defence
     * in depth, so the guard now owns the sequence and {@link #play(TrickPlay)} is package-private.
     *
     * <p>The order is deliberate. The seat is settled first — is it a seat at all, does the play claim
     * it, and does the player named occupy it — because those three questions are answered entirely
     * from facts the requester already has, so a refusal tells them nothing they did not send. Turn
     * order comes next, before the card is looked at, because a player who is not to play has no
     * business learning anything about the state of their own hand from the response. Only then is the
     * card resolved out of the hand and follow-suit judged on the card as dealt rather than as claimed.
     *
     * <p>The acting seat is a parameter in its own right, and the caller must derive it from the
     * credential the request presented. It is deliberately <em>not</em> read out of the candidate play,
     * and an earlier version of this method that did read it from there was broken. Every player is
     * told every other player's seat and identifier, because the session state has to publish them for
     * a client to draw the table, so a play describing someone else needs no guesswork. Trusting the
     * seat in the payload let a player claim the seat whose turn it was, play out of turn, and then
     * play a second card from their own hand at their real seat later in the same trick — taking the
     * trick and locking that seat's real occupant out of it, since a seat already played for cannot
     * play again. Neither trick invariant catches that: two distinct seats and two distinct cards is
     * precisely what they allow.
     *
     * <p>The hand and the seats still holding cards are likewise derived here rather than accepted,
     * both read out of {@code hands} against the acting seat. A caller that could hand in the hand
     * would be a caller that could hand in someone else's, and a caller that could hand in the seat
     * set could hand in a permissive one that made an out-of-turn play look like its turn. Passing
     * {@link Hands} as an argument rather than holding it is the boundary: a trick is one round, a
     * hand spans the session, so a trick must be able to ask about hands without owning them.
     *
     * <p>What remains caller-supplied on the candidate is the player identifier, and it is checked
     * against the player the acting seat's hand belongs to rather than believed. That is a second,
     * independent check on the same question, and a mismatch is a disagreement to refuse rather than
     * to guess at.
     *
     * @param actingSeat the seat of the player making the request, derived from their credential
     * @param candidate the play as the request described it
     * @param hands every hand in the session, from which the acting seat's hand is read
     * @return a new trick with the play appended, carrying the card as it was dealt
     * @throws NotYourSeatException if the play claims a seat other than the acting seat
     * @throws OutOfTurnException if it is another seat's turn
     * @throws CardNotInHandException if the hand does not hold the card
     * @throws MustFollowSuitException if the hand holds the led suit and the card is of another suit
     * @throws PlayerMismatchException if the play names a player other than the one holding
     *         the acting seat's hand
     * @throws IllegalArgumentException if the acting seat is not a seat at this table, or if
     *         no hand was dealt to it
     */
    public Trick acceptPlay(final int actingSeat,
                            final TrickPlay candidate,
                            final Hands hands) {
        Objects.requireNonNull(candidate, "candidate is required");
        Objects.requireNonNull(hands, "hands is required");

        if (actingSeat < 0 || actingSeat >= GameSession.MAXIMUM_PLAYERS) {
            throw new IllegalArgumentException("Seat " + actingSeat
                    + " is not a seat at this table, which seats at most " + GameSession.MAXIMUM_PLAYERS);
        }

        if (candidate.seatOrder() != actingSeat) {
            throw new NotYourSeatException(actingSeat, candidate.seatOrder());
        }

        final Hand hand = hands.handOf(actingSeat);

        if (!hand.playerId().equals(candidate.playerId())) {
            throw new PlayerMismatchException(actingSeat, hand.playerId(), candidate.playerId());
        }

        assertSeatMayPlay(actingSeat, hands.seatsHoldingCards());
        final Card dealt = assertLegalPlay(candidate.card(), hand);
        return play(candidate.withCard(dealt));
    }

    /**
     * Adds a play to the trick, returning the new trick.
     *
     * <p>Package-private on purpose. Legality cannot be re-checked here, because the questions
     * {@link #assertLegalPlay(Card, Hand)} and {@link #assertSeatMayPlay(int, Collection)} answer need
     * the player's hand and the seats still holding cards, neither of which a trick knows or should
     * hold — a trick is one round, a hand spans the session. Since this method therefore cannot
     * defend itself, it is not reachable from the use-case or adapter packages at all;
     * {@link #acceptPlay(int, TrickPlay, Hands)} is the way in. What this method does enforce,
     * through the constructor, is the pair of invariants that hold regardless of any hand: one play
     * per seat, and one play per card.
     *
     * @param play the play to add
     * @return a new trick with the play appended
     * @throws IllegalStateException if the trick has already been resolved
     */
    Trick play(final TrickPlay play) {
        Objects.requireNonNull(play, "play is required");
        if (winner != null) {
            throw new IllegalStateException(
                    describe() + " has already been resolved, so no further card can be played into it");
        }
        final List<TrickPlay> extended = new ArrayList<>(plays);
        extended.add(play);
        return new Trick(trickId, sequence, leaderSeat, extended, null);
    }

    /**
     * Resolves the trick, returning a new trick that records which play took it.
     *
     * <p>The rule: the highest card of the led suit takes the trick, unless one or more Elevation of
     * Privilege cards were played, in which case the highest of those takes it. Only a card of the led
     * suit or a trump can take a trick, so every other card in the trick is irrelevant to the outcome
     * — it was played to get rid of it, or to say something about the system, and both are legitimate.
     *
     * <p>This is correct when Elevation of Privilege is itself the led suit: the decisive suit is then
     * trump either way and the highest card of it wins, which is the same answer.
     *
     * <p>There can be no tie. The deck holds no two cards of the same suit and rank, so within the
     * decisive suit exactly one play holds the highest rank.
     *
     * @return a new trick with the winning play recorded
     * @throws IllegalStateException if the trick has no plays, or has already been resolved
     */
    public Trick resolved() {
        if (plays.isEmpty()) {
            throw new IllegalStateException(describe() + " has no plays, so it cannot be resolved");
        }
        if (winner != null) {
            throw new IllegalStateException(describe() + " has already been resolved");
        }
        return new Trick(trickId, sequence, leaderSeat, plays, winningPlay());
    }

    private TrickPlay winningPlay() {
        final StrideCategory led = plays.get(0).card().suit();
        final boolean anyTrump = plays.stream().anyMatch(play -> play.card().isTrump());
        final StrideCategory decisive = anyTrump ? StrideCategory.ELEVATION_OF_PRIVILEGE : led;

        return plays.stream()
                .filter(play -> play.card().suit() == decisive)
                .reduce((held, candidate) -> candidate.card().rank().beats(held.card().rank()) ? candidate : held)
                // Unreachable: the leader's own card is of the led suit, so if trump is not the
                // decisive suit there is at least one candidate, and if it is then some play was trump.
                .orElseThrow(() -> new IllegalStateException(
                        describe() + " contains no card of the decisive suit " + decisive));
    }

    private String describe() {
        return "Trick " + sequence;
    }

    /**
     * The play that took this trick.
     *
     * @return the winning play, or empty while the trick is unresolved
     */
    public Optional<TrickPlay> winner() {
        return Optional.ofNullable(winner);
    }

    /**
     * The seat that took this trick.
     *
     * <p>A fact about this trick and nothing more. It is deliberately <em>not</em> called
     * {@code nextLeaderSeat}, because the winner is not always able to lead: see
     * {@link #nextLeaderSeat(Collection)}.
     *
     * @return the seat whose play took the trick
     * @throws IllegalStateException if this trick has not been resolved
     */
    public int winningSeat() {
        if (winner == null) {
            throw new IllegalStateException(
                    describe() + " is unresolved, so the seat that took it is not yet known");
        }
        return winner.seatOrder();
    }

    /**
     * The seat that leads the next trick, or empty if there is no next trick.
     *
     * <p>The winner leads next — but only if the winner still holds a card, and under ADR-023 that is
     * not always true. At 68 cards three of the four supported table sizes divide unevenly, so a seat
     * can play its last card and win the trick it played it into. At six players seats 0 and 1 hold
     * twelve cards and seats 2 to 5 hold eleven: if any of seats 2 to 5 takes trick eleven, the winner
     * is out of cards while seats 0 and 1 each still hold one.
     *
     * <p>Handing back the winner's seat regardless would open the next trick on a seat that can never
     * play into it: {@link #seatToPlay(Collection)} would report no seat to play while
     * {@link #isComplete(Collection)} reported the trick incomplete, and the game would simply stop
     * with no exception thrown. That is precisely the shape of defect ADR-023 was written to prevent —
     * visible only on the last trick, and only at the player counts whose deal is uneven — so the rule
     * is stated here
     * rather than left for whoever wires trick resolution to infer: <em>the lead passes to the winner
     * if the winner still holds a card, and otherwise to the next seat clockwise from the winner that
     * does</em>. Empty means nobody holds a card, which is one of PRD §3.3's end conditions.
     *
     * @param seatsHoldingCards the seats that still hold at least one card, after this trick's cards
     *                          have been removed from their hands
     * @return the seat that leads the next trick, or empty if the game is out of cards
     * @throws IllegalStateException if this trick has not been resolved
     */
    public OptionalInt nextLeaderSeat(final Collection<Integer> seatsHoldingCards) {
        Objects.requireNonNull(seatsHoldingCards, "seatsHoldingCards is required");
        final int won = winningSeat();

        if (seatsHoldingCards.contains(won)) {
            return OptionalInt.of(won);
        }
        return SeatOrder.nextClockwise(won, seatsHoldingCards);
    }

    /**
     * The identifier of this trick.
     *
     * @return the trick identifier
     */
    public UUID trickId() {
        return trickId;
    }

    /**
     * The one-based position of this trick within the session.
     *
     * @return the trick sequence
     */
    public int sequence() {
        return sequence;
    }

    /**
     * The seat that played first and set the led suit.
     *
     * @return the leading seat
     */
    public int leaderSeat() {
        return leaderSeat;
    }

    /**
     * The plays in this trick, in play order.
     *
     * @return an unmodifiable list of plays
     */
    public List<TrickPlay> plays() {
        return plays;
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Trick that)) {
            return false;
        }
        return sequence == that.sequence
                && leaderSeat == that.leaderSeat
                && trickId.equals(that.trickId)
                && plays.equals(that.plays)
                && Objects.equals(winner, that.winner);
    }

    @Override
    public int hashCode() {
        return Objects.hash(trickId, sequence, leaderSeat, plays, winner);
    }

    /**
     * A summary that names no card.
     *
     * <p>Cards already played are public information, but a trick in progress is interpolated into log
     * lines alongside hands that are not, and a rendering that lists cards here invites the same
     * rendering on {@link Hand}. The count and the led suit are what a diagnostic actually needs.
     *
     * @return a short description of the trick
     */
    @Override
    public String toString() {
        return "Trick[sequence="
                + sequence
                + ", leaderSeat="
                + leaderSeat
                + ", ledSuit="
                + ledSuit().map(Enum::name).orElse("none")
                + ", plays="
                + plays.size()
                + ", resolved="
                + (winner != null)
                + "]";
    }
}
