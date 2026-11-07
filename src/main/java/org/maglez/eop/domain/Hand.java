package org.maglez.eop.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The cards one player holds in one session.
 *
 * <p>Immutable. Playing a card returns a new hand rather than mutating this one,
 * so a play that is rejected downstream cannot leave a card missing from a hand
 * that was never legally played from.
 *
 * <p>There is no draw pile. Every card in the deck is dealt at the start of the
 * session, so a hand only ever shrinks, and a session ends when hands empty
 * (PRD §3.3, ADR-023).
 *
 * <p>A hand deliberately does not carry a seat number. A seat belongs to the
 * {@link Player} who holds it and is the key this hand is filed under in
 * {@link Hands}; storing it a third time here would create a copy of a fact that
 * could disagree with the other two.
 *
 * <p>Pure domain: no Spring, no Jakarta, no persistence annotations.
 */
public final class Hand {

    private final UUID handId;

    private final UUID playerId;

    private final List<Card> cards;

    private Hand(final UUID handId, final UUID playerId, final List<Card> cards) {
        this.handId = Objects.requireNonNull(handId, "handId is required");
        this.playerId = Objects.requireNonNull(playerId, "playerId is required");
        Objects.requireNonNull(cards, "cards is required");
        final List<Card> ordered = new ArrayList<>(cards);
        ordered.forEach(card -> Objects.requireNonNull(card, "a hand cannot hold a null card"));
        ordered.sort(Comparator.comparingInt((Card card) -> card.suit().deckOrder())
                .thenComparingInt(card -> card.rank().value()));
        this.cards = List.copyOf(ordered);
        if (this.cards.stream().map(Card::cardId).distinct().count() != this.cards.size()) {
            throw new IllegalArgumentException("A hand cannot hold the same card twice");
        }
    }

    /**
     * A hand holding the given cards.
     *
     * <p>The cards are held in a canonical order — by suit in STRIDE order, then
     * by rank — rather than in the order they were dealt. Dealing order carries
     * no meaning, so two hands holding the same cards compare equal whatever
     * order they arrived in, and the canonical order is also the arrangement the
     * physical game asks players to lay their cards out in (PRD §3.3).
     *
     * @param handId   the hand's identifier
     * @param playerId the player who holds it
     * @param cards    the cards dealt, in any order
     * @return the hand
     * @throws NullPointerException     if any argument or card is null
     * @throws IllegalArgumentException if the same card appears twice
     */
    public static Hand of(final UUID handId, final UUID playerId, final List<Card> cards) {
        return new Hand(handId, playerId, cards);
    }

    /**
     * Whether this hand holds a particular card.
     *
     * <p>Compared by card identifier rather than by value, because the card
     * identifier is what a request names and what the database constrains.
     *
     * @param card the card to look for
     * @return true if the hand holds it
     */
    public boolean holds(final Card card) {
        Objects.requireNonNull(card, "card is required");
        return cards.stream().anyMatch(held -> held.cardId().equals(card.cardId()));
    }

    /**
     * The card this hand holds under a candidate's identifier, as it was dealt.
     *
     * <p>This is the only safe way to turn a card named by a request into a card
     * a rule may be applied to. {@link #holds(Card)} matches on the identifier
     * alone, so a caller that then reads {@code candidate.suit()} or
     * {@code candidate.rank()} is trusting values the caller supplied: a player
     * holding the two of tampering could name that identifier while labelling it
     * the ace of elevation of privilege, pass the possession check, escape the
     * follow-suit rule and take the trick with a card that was never dealt.
     *
     * <p>Resolving through the hand makes the submitted suit and rank inert. The
     * returned card is the one the deal put in this hand, so every rule downstream
     * — follow-suit, trump, rank — is applied to server-held state.
     *
     * <p>A null candidate is answered the same way rather than with a
     * {@link NullPointerException}. A request that named no card has named no card
     * this hand holds, and routing it through the same refusal keeps one code path
     * for one question and makes a missing card field a client error at the
     * boundary rather than a server one.
     *
     * @param candidate the card a caller named; only its identifier is trusted
     * @return the card as dealt to this hand
     * @throws CardNotInHandException if this hand does not hold that card, including
     *         when the candidate is {@code null} and so names no card at all
     */
    public Card resolve(final Card candidate) {
        final UUID candidateId = candidate == null ? null : candidate.cardId();
        return cards.stream()
                .filter(held -> held.cardId().equals(candidateId))
                .findFirst()
                .orElseThrow(() -> new CardNotInHandException(handId, candidateId));
    }

    /**
     * Whether this hand holds any card of a suit.
     *
     * <p>This is the question the follow-suit rule turns on: a player holding the
     * led suit must play it, and a player holding none of it may play anything.
     *
     * <p>A null suit is rejected rather than answered false. False here means "this
     * player cannot follow, so any card is legal" — the permissive branch of the
     * follow-suit rule — so answering a malformed question with a default would
     * fail open on the one predicate the rule turns on.
     *
     * @param suit the suit to look for
     * @return true if the hand holds at least one card of that suit
     * @throws NullPointerException if the suit is null
     */
    public boolean holdsSuit(final StrideCategory suit) {
        Objects.requireNonNull(suit, "suit is required");
        return cards.stream().anyMatch(card -> card.suit() == suit);
    }

    /**
     * The lowest-ranked card of a suit in this hand.
     *
     * <p>Used to derive the opening leader, which is the holder of the lowest
     * tampering card in the deal. No rank is named in code for that rule: the
     * printed deck's lowest tampering card is the three and the seeded deck's is
     * the two, and this returns whichever was actually dealt.
     *
     * @param suit the suit to search
     * @return the lowest card of that suit, or empty if the hand holds none
     */
    public Optional<Card> lowestOf(final StrideCategory suit) {
        Objects.requireNonNull(suit, "suit is required");
        return cards.stream()
                .filter(card -> card.suit() == suit)
                .reduce((lower, candidate) -> lower.rank().beats(candidate.rank()) ? candidate : lower);
    }

    /**
     * This hand with a card removed, as it stands after that card is played.
     *
     * <p>Resolves the card through {@link #resolve(Card)} first, so a candidate
     * carrying a held identifier but a forged suit or rank removes the card that
     * was actually dealt and cannot smuggle its own values onward.
     *
     * @param card the card being played; only its identifier is trusted
     * @return a new hand without that card
     * @throws CardNotInHandException if the hand does not hold the card
     */
    public Hand without(final Card card) {
        final Card held = resolve(card);
        final List<Card> remaining = cards.stream()
                .filter(other -> !other.cardId().equals(held.cardId()))
                .toList();
        return new Hand(handId, playerId, remaining);
    }

    /**
     * Whether this hand has been played out.
     *
     * <p>An empty hand takes no part in further tricks, which is what makes the
     * final trick of a session short at player counts where the deck does not
     * divide equally (ADR-023).
     *
     * @return true if no cards remain
     */
    public boolean isEmpty() {
        return cards.isEmpty();
    }

    /**
     * How many cards remain.
     *
     * @return the number of cards held
     */
    public int size() {
        return cards.size();
    }

    /**
     * The cards held, in canonical suit-then-rank order.
     *
     * <p>Confidential. Knowing which cards a player holds is knowing what they can
     * and cannot follow with, so this list must not be logged, interpolated into an
     * exception message, or returned to any player other than the one who holds it.
     * {@link #toString()} on this hand is redacted precisely so that a hand cannot
     * leak by accident; calling this accessor and rendering the result is the way
     * round that, and is a deliberate act.
     *
     * @return an unmodifiable list
     */
    public List<Card> cards() {
        return cards;
    }

    /**
     * The hand's identifier.
     *
     * @return the identifier
     */
    public UUID handId() {
        return handId;
    }

    /**
     * The player who holds this hand.
     *
     * @return the player identifier
     */
    public UUID playerId() {
        return playerId;
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Hand that)) {
            return false;
        }
        return handId.equals(that.handId) && playerId.equals(that.playerId) && cards.equals(that.cards);
    }

    @Override
    public int hashCode() {
        return Objects.hash(handId, playerId, cards);
    }

    /**
     * A description that names no card.
     *
     * <p>A hand is private to the player holding it: knowing which cards an
     * opponent holds is knowing what they can and cannot follow with. The default
     * record-style rendering would put the whole hand into any log line or
     * exception message that happened to interpolate it, so this states the size
     * and nothing else.
     *
     * @return a description carrying no card contents
     */
    @Override
    public String toString() {
        return "Hand[handId=" + handId + ", playerId=" + playerId + ", cards=" + cards.size() + "]";
    }
}
