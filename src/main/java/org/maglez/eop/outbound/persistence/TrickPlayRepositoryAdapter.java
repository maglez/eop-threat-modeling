package org.maglez.eop.outbound.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.maglez.eop.domain.AlreadyPlayedInTrickException;
import org.maglez.eop.domain.Card;
import org.maglez.eop.domain.CardAlreadyPlayedException;
import org.maglez.eop.domain.CardNotInHandException;
import org.maglez.eop.domain.GameSession;
import org.maglez.eop.domain.Hand;
import org.maglez.eop.domain.HandAlreadyDealtException;
import org.maglez.eop.domain.HandCompleteException;
import org.maglez.eop.domain.HandNotDealtException;
import org.maglez.eop.domain.Hands;
import org.maglez.eop.domain.NotYourSeatException;
import org.maglez.eop.domain.OutOfTurnException;
import org.maglez.eop.domain.PlayerNotInSessionException;
import org.maglez.eop.domain.SessionNotFoundException;
import org.maglez.eop.domain.SessionNotJoinableException;
import org.maglez.eop.domain.SessionStatus;
import org.maglez.eop.domain.Trick;
import org.maglez.eop.domain.TrickAlreadyOpenException;
import org.maglez.eop.domain.TrickAlreadyResolvedException;
import org.maglez.eop.domain.TrickPlay;
import org.maglez.eop.application.port.out.HandRepository;
import org.maglez.eop.application.port.out.TrickRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores and rebuilds the hands and tricks of a session.
 *
 * <p>This class implements both trick-play ports. That is deliberate rather than
 * economy: playing a card removes a row from {@code hand_card} and inserts rows into
 * {@code trick_play} and {@code trick_play_component}, and those writes have to
 * succeed or fail together. Splitting them across two adapters would hand that
 * atomicity to somebody else, and the obvious decomposition gives two transactions,
 * which can leave a card gone from a hand with no play recorded.
 *
 * <p>An earlier version of this paragraph went further and claimed the only other
 * option was a transaction declared in the use case layer, on the wrong side of the
 * dependency rule. That was false and is retracted. A transactional method in the
 * outer ring composes two adapters perfectly well through Spring's default
 * {@code REQUIRED} propagation, because a method already annotated joins an enclosing
 * transaction rather than starting its own, and nothing about that arrangement puts a
 * framework import inside {@code usecase}. ADR-024 carries the mechanism and the
 * retraction. Naming an option that exists as an option that does not is how an
 * argument avoids being made.
 *
 * <p>What survives is ownership, not impossibility. The transaction is a detail of the
 * persistence mechanism, so the ring that owns the mechanism owns its atomic unit, and
 * composing these two writes further out would move an invariant into every caller
 * that has to remember it.
 *
 * <p>Two responsibilities live here and nowhere else. The first is the transaction
 * boundary, for the reason above. The second is translating a constraint violation
 * into a domain exception: Spring Data types and JPA entities stop at this class, and
 * what continues inwards is a domain exception the web layer already knows how to
 * answer. {@link DataIntegrityViolationException} is the exception to that, which an
 * earlier version of this paragraph wrongly denied by saying it stops here too.
 *
 * <p>The translation is three-way, not two-way, and the two-way version of this
 * sentence was wrong in both of its previous forms. Five of the seven constraint names
 * this class knows are translated into a domain exception. Two — {@code
 * fk_hand_player_seat} and {@code fk_trick_play_player_seat}, both marked {@code
 * Backstop only} where they are declared — are recognised by name and deliberately
 * <em>not</em> translated: they go to {@code backstopFired}, which logs the name at
 * {@code WARN} and hands the violation back to be rethrown as a 500, because
 * {@code assertSeated} passed on the same facts moments earlier and a caller who did
 * nothing wrong should not be told they did. Anything whose name this class does not
 * know is rethrown unlogged, so a constraint added later fails loudly rather than being
 * answered as whichever domain exception happened to be tested first — that is argued on
 * {@code mentions}, where it happens, and not on {@code backstopFired}, which an earlier
 * version of this paragraph cited instead.
 *
 * <p>Every write begins with a conditional update on {@code game_session}. That is
 * two things at once. It is the compare-and-set that ADR-020 makes the concurrency
 * protocol, so a request carrying a stale view of the leader seat changes no rows and
 * is refused rather than applied. And it takes the row lock on the session before any
 * hand or trick row is touched, which is the lock order ADR-023 fixes and therefore
 * what makes two simultaneous requests serialise rather than deadlock.
 *
 * <p>It is emphatically <em>not</em> an authorisation check, and this class does not
 * perform one on the caller's behalf. None of the conditional statements takes a
 * player identifier, so none of them can refuse a caller on the grounds of who they
 * are; they refuse on the grounds of what the session's state is. Two of the four
 * writes, {@link #recordDeal} and {@link #appendPlay}, additionally assert that the
 * player a row names is seated at the seat that row claims — but that is a check on
 * the <em>row</em>, not on the requester, and it is the only membership check in the
 * class. {@link #openTrick} and {@link #recordResolution} make none. Neither do any
 * of the four reads, and there they are not merely absent but impossible: the ports
 * carry no acting-player parameter for a read to check against.
 *
 * <p>Establishing that the requester belongs in this session is therefore the use
 * case's obligation, and it has to be discharged before any method on either port is
 * called. Undischarged, the failure paths are an oracle: a caller who has done no
 * more than guess a session identifier learns from {@link #sessionMoved(UUID,
 * Integer)} that the session exists, what status it is in, whether hands have been
 * dealt, and which seat currently leads. Every one of those is the right answer to
 * give a member and the wrong answer to give a stranger, and only the layer above can
 * tell the two apart.
 *
 * <p>Zero rows changed is never treated as success and never rethrown as a server
 * fault. It means the world moved underneath the request. For the four session
 * compare-and-sets {@link #sessionMoved(UUID, Integer)} spends one extra read on the
 * already-failing path to say which way it moved; for the winner update in {@link
 * #recordResolution} no read is spent, because both things zero can mean there — the
 * trick is already resolved, or the trick row is gone — are answered the same way, by
 * {@link TrickAlreadyResolvedException} and so a 409. Conflating them is deliberate and
 * is only safe while nothing deletes a single trick row; the only delete in this class
 * is of a {@code hand_card}. If a later slice adds one, this is the paragraph to revisit,
 * because the second meaning would then need a 404 of its own. That case was an
 * {@code IllegalStateException}, and so a 500, until a security review of this slice
 * noticed it contradicted this paragraph.
 */
@Repository
public class TrickPlayRepositoryAdapter implements HandRepository, TrickRepository {

    private static final Logger LOG = LoggerFactory.getLogger(TrickPlayRepositoryAdapter.class);

    /**
     * The only session status in which cards may be dealt or played.
     *
     * <p>Held here rather than passed in because it is policy, and the repository
     * interfaces take the status as a parameter precisely so that policy stays out of
     * them.
     */
    private static final SessionStatus PLAYABLE = SessionStatus.IN_PROGRESS;

    private static final String SESSION_ID_REQUIRED = "sessionId is required";

    private static final String OCCURRED_AT_REQUIRED = "occurredAt is required";

    private static final String TRICK_ID_REQUIRED = "trickId is required";

    /** Binds {@code hand} to {@code player (id, seat_order)}. Backstop only. */
    private static final String HAND_SEAT_CONSTRAINT = "fk_hand_player_seat";

    /** Binds {@code trick_play} to {@code player (id, seat_order)}. Backstop only. */
    private static final String PLAY_SEAT_CONSTRAINT = "fk_trick_play_player_seat";

    private static final String HAND_PER_SEAT_CONSTRAINT = "uq_hand_session_seat";

    private static final String TRICK_SEQUENCE_CONSTRAINT = "uq_trick_session_sequence";

    private static final String PLAY_PER_SEAT_CONSTRAINT = "uq_trick_play_trick_seat";

    private static final String PLAY_PER_PLAYER_CONSTRAINT = "uq_trick_play_trick_player";

    private static final String PLAY_PER_CARD_CONSTRAINT = "uq_trick_play_trick_card";

    private final GameSessionJpaRepository sessionRows;

    private final PlayerJpaRepository playerRows;

    private final HandJpaRepository handRows;

    private final HandCardJpaRepository handCardRows;

    private final TrickJpaRepository trickRows;

    private final TrickPlayJpaRepository playRows;

    private final TrickPlayComponentJpaRepository componentRows;

    private final CardJpaRepository cardRows;

    /**
     * Creates the adapter.
     *
     * <p>Package private: the application depends on {@link HandRepository} and
     * {@link TrickRepository}, and nothing outside this package should be able to
     * assemble one of these with its own collaborators.
     *
     * @param sessionRows   sessions, and the conditional updates that guard every write
     * @param playerRows    seated players, read to settle a seat before a row asserts it
     * @param handRows      dealt hands
     * @param handCardRows  the cards still held in those hands
     * @param trickRows     tricks
     * @param playRows      plays made into tricks
     * @param componentRows the components named on a play
     * @param cardRows      the card catalogue, which is the only authority on a card's face
     */
    TrickPlayRepositoryAdapter(
            final GameSessionJpaRepository sessionRows,
            final PlayerJpaRepository playerRows,
            final HandJpaRepository handRows,
            final HandCardJpaRepository handCardRows,
            final TrickJpaRepository trickRows,
            final TrickPlayJpaRepository playRows,
            final TrickPlayComponentJpaRepository componentRows,
            final CardJpaRepository cardRows) {
        this.sessionRows = Objects.requireNonNull(sessionRows, "sessionRows is required");
        this.playerRows = Objects.requireNonNull(playerRows, "playerRows is required");
        this.handRows = Objects.requireNonNull(handRows, "handRows is required");
        this.handCardRows = Objects.requireNonNull(handCardRows, "handCardRows is required");
        this.trickRows = Objects.requireNonNull(trickRows, "trickRows is required");
        this.playRows = Objects.requireNonNull(playRows, "playRows is required");
        this.componentRows = Objects.requireNonNull(componentRows, "componentRows is required");
        this.cardRows = Objects.requireNonNull(cardRows, "cardRows is required");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Hands> findBySessionId(final UUID sessionId) {
        Objects.requireNonNull(sessionId, SESSION_ID_REQUIRED);
        final List<HandJpaEntity> rows = handRows.findByGameSessionIdOrderBySeatOrderAsc(sessionId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        final List<UUID> handIds = rows.stream().map(HandJpaEntity::getId).toList();
        final Map<UUID, List<UUID>> cardIdsByHand = new HashMap<>();
        for (final HandCardJpaEntity held : handCardRows.findByHandIdIn(handIds)) {
            cardIdsByHand.computeIfAbsent(held.getHandId(), key -> new ArrayList<>()).add(held.getCardId());
        }
        final Map<UUID, Card> catalogue = catalogue(
                cardIdsByHand.values().stream().flatMap(List::stream).toList());
        final Map<Integer, Hand> handsBySeat = new LinkedHashMap<>();
        for (final HandJpaEntity row : rows) {
            final List<Card> cards = cardIdsByHand.getOrDefault(row.getId(), List.of()).stream()
                    .map(cardId -> resolve(catalogue, cardId))
                    .toList();
            handsBySeat.put(row.getSeatOrder(), row.toDomain(cards));
        }
        return Optional.of(Hands.reconstitute(handsBySeat));
    }

    @Override
    @Transactional(readOnly = true)
    public OptionalInt findCurrentLeaderSeat(final UUID sessionId) {
        Objects.requireNonNull(sessionId, SESSION_ID_REQUIRED);
        return sessionRows
                .findById(sessionId)
                .map(GameSessionJpaEntity::getCurrentLeaderSeat)
                .map(TrickPlayRepositoryAdapter::seatRead)
                .orElseGet(OptionalInt::empty);
    }

    @Override
    @Transactional
    public void recordDeal(
            final UUID sessionId, final Hands hands, final int openingLeaderSeat, final Instant occurredAt) {
        Objects.requireNonNull(sessionId, SESSION_ID_REQUIRED);
        Objects.requireNonNull(hands, "hands is required");
        Objects.requireNonNull(occurredAt, OCCURRED_AT_REQUIRED);

        final int claimed = sessionRows.claimDeal(
                sessionId, seatToWrite(openingLeaderSeat, "openingLeaderSeat"), PLAYABLE, at(occurredAt));
        if (claimed == 0) {
            throw sessionMoved(sessionId, null);
        }

        final Map<UUID, Integer> seatsByPlayer = seatsByPlayer(sessionId);
        for (final Map.Entry<Integer, Hand> dealt : hands.handsBySeat().entrySet()) {
            assertSeated(sessionId, seatsByPlayer, dealt.getValue().playerId(), dealt.getKey());
        }

        for (final Map.Entry<Integer, Hand> dealt : hands.handsBySeat().entrySet()) {
            final Hand hand = dealt.getValue();
            try {
                handRows.saveAndFlush(HandJpaEntity.fromDomain(sessionId, dealt.getKey(), hand));
            }
            catch (final DataIntegrityViolationException collision) {
                throw dealFailure(sessionId, collision);
            }
            final List<HandCardJpaEntity> held = hand.cards().stream()
                    .map(card -> HandCardJpaEntity.of(hand.handId(), card.cardId()))
                    .toList();
            try {
                handCardRows.saveAllAndFlush(held);
            }
            catch (final DataIntegrityViolationException collision) {
                throw dealFailure(sessionId, collision);
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Trick> findCurrentTrick(final UUID sessionId) {
        Objects.requireNonNull(sessionId, SESSION_ID_REQUIRED);
        return trickRows.findFirstByGameSessionIdOrderBySequenceDesc(sessionId).map(this::assemble);
    }

    /**
     * Reads every trick of a session, in the order they were played.
     *
     * <p>Nothing is filtered. A trick still on the table is returned alongside the resolved ones,
     * unresolved, because whether a trick is finished is a question the trick answers about itself
     * once it has been rebuilt. A query that asked for {@code winner_play_id IS NOT NULL} would be
     * a second authority on that, and one that a caller deriving a running score would be wrong to
     * trust: the plays of an unfinished trick still scored their threats.
     *
     * <p>Four reads serve the whole session rather than three per trick. Assembling each trick on
     * its own would cost {@code 1 + 3 * 23} = 70 round trips at three players — the 68-card deck
     * deals 23/23/22, so a hand runs to 23 tricks — for an answer whose size the deck already
     * bounds. The derivation is stated rather than reduced to its total on purpose: it is what makes
     * the figure visibly wrong the next time the deck changes size, which a bare total was not.
     *
     * <p>Authorises nobody, and cannot: there is no acting player in the signature to check against
     * (ADR-024). A caller that has not already established membership is handing a stranger a
     * session's whole history, so that has to be settled before this is called.
     *
     * @param sessionId identifier of the session
     * @return the session's tricks in sequence order, empty when none has been opened
     */
    @Override
    @Transactional(readOnly = true)
    public List<Trick> findTricks(final UUID sessionId) {
        Objects.requireNonNull(sessionId, SESSION_ID_REQUIRED);
        final List<TrickJpaEntity> rows = trickRows.findByGameSessionIdOrderBySequenceAsc(sessionId);
        if (rows.isEmpty()) {
            return List.of();
        }
        final List<TrickPlayJpaEntity> playedRows = playRows.findByTrickIdIn(
                rows.stream().map(TrickJpaEntity::getId).toList());
        final Map<UUID, Card> catalogue = catalogue(
                playedRows.stream().map(TrickPlayJpaEntity::getCardId).toList());
        final Map<UUID, List<String>> componentsByPlay = componentsByPlay(playedRows);
        final Map<UUID, List<TrickPlayJpaEntity>> playsByTrick = new HashMap<>();
        for (final TrickPlayJpaEntity played : playedRows) {
            playsByTrick.computeIfAbsent(played.getTrickId(), key -> new ArrayList<>()).add(played);
        }
        final List<Trick> tricks = new ArrayList<>(rows.size());
        for (final TrickJpaEntity row : rows) {
            tricks.add(assemble(row, playsByTrick.getOrDefault(row.getId(), List.of()), catalogue, componentsByPlay));
        }
        return List.copyOf(tricks);
    }

    @Override
    @Transactional
    public void openTrick(
            final UUID sessionId, final Trick trick, final int expectedLeaderSeat, final Instant occurredAt) {
        Objects.requireNonNull(sessionId, SESSION_ID_REQUIRED);
        Objects.requireNonNull(trick, "trick is required");
        Objects.requireNonNull(occurredAt, OCCURRED_AT_REQUIRED);

        final int touched = sessionRows.touchWhileLeaderSeatIs(sessionId, expectedLeaderSeat, PLAYABLE, at(occurredAt));
        if (touched == 0) {
            throw sessionMoved(sessionId, expectedLeaderSeat);
        }

        try {
            trickRows.saveAndFlush(TrickJpaEntity.fromDomain(sessionId, trick));
        }
        catch (final DataIntegrityViolationException collision) {
            if (mentions(collision, TRICK_SEQUENCE_CONSTRAINT)) {
                throw new TrickAlreadyOpenException(sessionId, trick.sequence());
            }
            throw collision;
        }
    }

    @Override
    @Transactional
    public void appendPlay(
            final UUID sessionId, final UUID trickId, final int expectedLeaderSeat, final TrickPlay play) {
        Objects.requireNonNull(sessionId, SESSION_ID_REQUIRED);
        Objects.requireNonNull(trickId, TRICK_ID_REQUIRED);
        Objects.requireNonNull(play, "play is required");

        final int touched =
                sessionRows.touchWhileLeaderSeatIs(sessionId, expectedLeaderSeat, PLAYABLE, at(play.playedAt()));
        if (touched == 0) {
            throw sessionMoved(sessionId, expectedLeaderSeat);
        }

        assertSeated(sessionId, seatsByPlayer(sessionId), play.playerId(), play.seatOrder());

        // Reads the session's hands and filters, rather than querying one seat directly.
        // HandJpaRepository declares no by-seat finder on purpose, so that a hand is never
        // read without the table it belongs to, and adding one here to save four rows would
        // put the first per-seat read into the class that must not have one.
        final HandJpaEntity hand = handRows.findByGameSessionIdOrderBySeatOrderAsc(sessionId).stream()
                .filter(row -> row.getSeatOrder() == play.seatOrder())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Session " + sessionId + " has no hand at seat " + play.seatOrder()));
        if (!hand.getPlayerId().equals(play.playerId())) {
            throw new IllegalStateException("The hand at seat " + play.seatOrder() + " belongs to another player");
        }

        final int removed = handCardRows.removeCardFromHand(hand.getId(), play.card().cardId());
        if (removed == 0) {
            throw new CardNotInHandException(hand.getId(), play.card().cardId());
        }

        try {
            playRows.saveAndFlush(TrickPlayJpaEntity.fromDomain(trickId, play));
        }
        catch (final DataIntegrityViolationException collision) {
            throw playFailure(trickId, play, collision);
        }

        final List<TrickPlayComponentJpaEntity> named = new ArrayList<>();
        final List<String> components = play.components();
        for (int ordinal = 0; ordinal < components.size(); ordinal++) {
            named.add(TrickPlayComponentJpaEntity.of(play.trickPlayId(), ordinal, components.get(ordinal)));
        }
        componentRows.saveAllAndFlush(named);
    }

    @Override
    @Transactional
    public void recordResolution(
            final UUID sessionId,
            final Trick resolved,
            final int expectedLeaderSeat,
            final OptionalInt nextLeaderSeat,
            final Instant occurredAt) {
        Objects.requireNonNull(sessionId, SESSION_ID_REQUIRED);
        Objects.requireNonNull(resolved, "resolved is required");
        Objects.requireNonNull(nextLeaderSeat, "nextLeaderSeat is required");
        Objects.requireNonNull(occurredAt, OCCURRED_AT_REQUIRED);
        final TrickPlay winner = resolved.winner()
                .orElseThrow(() -> new IllegalStateException("Trick " + resolved.trickId() + " is not resolved"));

        // An empty next leader is written as null rather than as some substitute seat: after the
        // last trick of a hand there is no seat that can lead, and a number in the column would
        // say there is. seatToWrite is only consulted for a seat that reaches the column.
        final Integer seatToLead = nextLeaderSeat.isPresent()
                ? seatToWrite(nextLeaderSeat.getAsInt(), "nextLeaderSeat")
                : null;
        final int advanced =
                sessionRows.advanceLeaderSeat(sessionId, expectedLeaderSeat, seatToLead, PLAYABLE, at(occurredAt));
        if (advanced == 0) {
            throw sessionMoved(sessionId, expectedLeaderSeat);
        }

        // Zero here is a conflict and not a fault. When the seat that led the trick also won
        // it, advanceLeaderSeat above sets the column to the value it compared against, so it
        // is idempotent and a replayed request gets past it. This statement is then the first
        // one to notice the resolution already happened. Mapping that to IllegalStateException
        // billed an ordinary replay as a 500 and contradicted this class's own comment.
        final int recorded = trickRows.recordWinner(resolved.trickId(), winner.trickPlayId());
        if (recorded == 0) {
            throw new TrickAlreadyResolvedException(resolved.trickId());
        }
    }

    /**
     * Rebuilds a trick from its row, its plays and the components named on them.
     *
     * <p>The plays are ordered by rotation from the trick's leader seat rather than by
     * the moment they were stored. {@code Trick} reads the led suit from the first play
     * in the list, so the order is load bearing, and {@code played_at} is the wrong
     * authority for it: two plays can share a timestamp, and nothing in the schema
     * breaks the tie. Rotation is exact instead, because seats are unique within a
     * trick and the rotation is the same rule the domain applied when it accepted the
     * plays in the first place.
     *
     * @param row the trick row
     * @return the trick, resolved if its winner is recorded
     */
    private Trick assemble(final TrickJpaEntity row) {
        final List<TrickPlayJpaEntity> rows = playRows.findByTrickId(row.getId());
        return assemble(row, rows, catalogue(rows.stream().map(TrickPlayJpaEntity::getCardId).toList()),
                componentsByPlay(rows));
    }

    /**
     * Rebuilds a trick from its row and plays that have already been read.
     *
     * <p>Separated from the overload above so that a whole session's tricks can be built from
     * four reads rather than three per trick. Everything the rebuild needs is passed in, and
     * nothing here touches a repository.
     *
     * @param row              the trick row
     * @param rows             the plays belonging to this trick, in any order
     * @param catalogue        the cards those plays name, by card identifier
     * @param componentsByPlay the components named on each play, by play identifier
     * @return the trick, resolved if its winner is recorded
     */
    private Trick assemble(final TrickJpaEntity row, final List<TrickPlayJpaEntity> rows,
            final Map<UUID, Card> catalogue, final Map<UUID, List<String>> componentsByPlay) {
        final int leaderSeat = row.getLeaderSeat();
        final List<TrickPlay> plays = rows.stream()
                .sorted(Comparator.comparingInt(
                        play -> Math.floorMod(play.getSeatOrder() - leaderSeat, GameSession.MAXIMUM_PLAYERS)))
                .map(play -> play.toDomain(
                        resolve(catalogue, play.getCardId()),
                        componentsByPlay.getOrDefault(play.getId(), List.of())))
                .toList();
        return row.toDomain(plays, winnerAmong(row, plays));
    }

    /**
     * Reads the components named on the given plays.
     *
     * <p>Ordered by ordinal within each play, because the order a player named the parts of the
     * system in is the order they meant, and the ordinal column is the only record of it.
     *
     * @param rows the plays to read components for
     * @return the component names of each play, by play identifier, empty for a play that named none
     */
    private Map<UUID, List<String>> componentsByPlay(final List<TrickPlayJpaEntity> rows) {
        final Map<UUID, List<String>> componentsByPlay = new HashMap<>();
        for (final TrickPlayComponentJpaEntity named
                : componentRows.findByTrickPlayIdInOrderByTrickPlayIdAscOrdinalAsc(
                        rows.stream().map(TrickPlayJpaEntity::getId).toList())) {
            componentsByPlay
                    .computeIfAbsent(named.getTrickPlayId(), key -> new ArrayList<>())
                    .add(named.getComponentName());
        }
        return componentsByPlay;
    }

    /**
     * Revalidates a leader seat read out of {@code game_session}.
     *
     * <p>The other three reads hand every row to a domain factory that re-runs the
     * invariants, so a tampered or pre-migration row is refused rather than believed.
     * This one returns a bare column and had no such gate, and it is the column that
     * can least afford to be missing one: the value drives the rotation arithmetic in
     * {@link #assemble(TrickJpaEntity)} and, one layer up, becomes the {@code
     * expectedLeaderSeat} witness that is the whole turn-order guard. {@code
     * chk_game_session_current_leader_seat} bounds it from changeset 005 onwards, but a
     * database provisioned before that changeset holds rows the constraint never saw.
     *
     * <p>Raised as a server fault rather than as a refusal aimed at the caller. An
     * out-of-range seat in one of our columns is our corruption, not their request.
     *
     * <p>Package private rather than private so that a test can reach it. That is not
     * a convenience: {@code chk_game_session_current_leader_seat} refuses the very row
     * this guard exists to survive, so an integration test cannot manufacture one and
     * the refusal is only observable by calling the function. Testing it through the
     * adapter would mean dropping the constraint, which would leave the suite proving
     * the guard against a schema the application never runs on.
     *
     * @param seatOrder the seat the column holds
     * @return that seat
     */
    static OptionalInt seatRead(final int seatOrder) {
        if (seatOrder < 0 || seatOrder >= GameSession.MAXIMUM_PLAYERS) {
            throw new IllegalStateException(
                    "Leader seat " + seatOrder + " is outside the seats a session has");
        }
        return OptionalInt.of(seatOrder);
    }

    /**
     * Bounds a leader seat this class is about to write.
     *
     * <p>{@code chk_game_session_current_leader_seat} would catch an out-of-range
     * value, but it would catch it as an untranslated constraint violation on the way
     * out: a bare 500 naming a database object, several frames from the call that
     * supplied the seat. Refusing it here is the explicit rejection at the boundary the
     * project rules ask for, and it costs one comparison.
     *
     * <p>{@code expectedLeaderSeat} is deliberately not bounded the same way, which is
     * why this takes the parameter name. That one is a witness rather than a value to
     * store: an out-of-range witness matches no row, which is already the right
     * outcome, and the caller is then told which seat actually leads. Only seats that
     * reach a column are checked here.
     *
     * <p>Package private for the same reason as {@link #seatRead(int)}: the negative
     * seat limb is unreachable through the adapter, because every caller of this in
     * production hands it a seat a domain object already bounded.
     *
     * @param seatOrder the seat about to be written
     * @param name      the parameter it arrived as
     * @return that seat
     */
    static int seatToWrite(final int seatOrder, final String name) {
        if (seatOrder < 0 || seatOrder >= GameSession.MAXIMUM_PLAYERS) {
            throw new IllegalArgumentException(
                    name + " " + seatOrder + " is outside the seats a session has");
        }
        return seatOrder;
    }

    /**
     * Finds the recorded winner among the plays of the trick that recorded it.
     *
     * <p>An unresolved trick has no winner and that is the ordinary case. A resolved
     * trick whose winner is not one of its own plays is not an ordinary case at all:
     * {@code fk_trick_winner_play} proves only that the winner is <em>some</em> play,
     * and it was measured during Slice B to accept a play from a different trick and
     * even from a different session. So the constraint cannot be relied on here, and a
     * winner that is not present is refused loudly rather than dropped, which would
     * quietly reconstitute the trick as unresolved and let it be resolved a second
     * time with a different outcome.
     *
     * @param row   the trick row
     * @param plays the plays made into that trick
     * @return the winning play, or {@code null} when the trick is unresolved
     */
    private TrickPlay winnerAmong(final TrickJpaEntity row, final List<TrickPlay> plays) {
        final UUID winnerPlayId = row.getWinnerPlayId();
        if (winnerPlayId == null) {
            return null;
        }
        return plays.stream()
                .filter(play -> play.trickPlayId().equals(winnerPlayId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Trick " + row.getId() + " names a winner that was not played into it"));
    }

    /**
     * Reads the seat of every player in the session.
     *
     * <p>Read before the row that asserts a seat, not after it fails. The foreign keys
     * on {@code hand} and {@code trick_play} do refuse a forged seat, but they refuse
     * it by raising a constraint violation, and once that has been raised the
     * transaction is rollback only and no further read can be trusted to answer
     * whether the player is in the session at all. Deciding first is what makes the
     * difference between a 403 and a 404 available to the caller.
     *
     * <p>Checking first would be wrong if seats moved, because then the check and the
     * insert could disagree. They cannot: {@code seat_order} is mapped as not
     * updatable and a seat is fixed when a player is seated. Allocating a seat is the
     * racy operation, and that one is still settled by the database in {@code
     * SessionRepositoryAdapter}.
     *
     * <p>The keys are also weaker than this map on one dimension, so the two are not
     * redundant and the second is not a substitute for the first. Both bind {@code
     * (player_id, seat_order)} to {@code player (id, seat_order)}, and nothing binds
     * that player row to the session the hand belongs to. A hand naming a player from a
     * <em>different</em> session, at a seat that player legitimately holds there,
     * satisfies both keys. This map is session scoped, so it is the only thing that
     * refuses it.
     *
     * @param sessionId the session
     * @return each seated player's identifier against the seat they occupy
     */
    private Map<UUID, Integer> seatsByPlayer(final UUID sessionId) {
        final Map<UUID, Integer> seats = new HashMap<>();
        for (final PlayerJpaEntity player : playerRows.findByGameSessionIdOrderBySeatOrderAsc(sessionId)) {
            seats.put(player.getId(), player.getSeatOrder());
        }
        return Map.copyOf(seats);
    }

    /**
     * Refuses a player who is not at the seat a row is about to claim for them.
     *
     * <p>Two refusals, and the difference between them is the whole point. A player of
     * this session at another seat is told so, because they are entitled to know the
     * seats in a session they are in. A player who is not in this session is answered
     * exactly as though the session did not exist, because telling a stranger that
     * their guess named a real session is the information they were fishing for.
     *
     * @param sessionId     the session the row belongs to
     * @param seatsByPlayer every seated player in that session
     * @param playerId      the player the row names
     * @param seatOrder     the seat the row claims for them
     */
    private void assertSeated(
            final UUID sessionId,
            final Map<UUID, Integer> seatsByPlayer,
            final UUID playerId,
            final int seatOrder) {
        final Integer occupied = seatsByPlayer.get(playerId);
        if (occupied == null) {
            throw new PlayerNotInSessionException(sessionId);
        }
        if (occupied.intValue() != seatOrder) {
            throw new NotYourSeatException(occupied, seatOrder);
        }
    }

    /**
     * Explains a conditional update on {@code game_session} that changed no rows.
     *
     * <p>One extra read, on a path that is already failing, in exchange for an answer
     * that says which way the world moved rather than that something went wrong. The
     * read is safe here in a way it would not be after a constraint violation: no
     * exception has been raised, so the transaction is still usable.
     *
     * <p>All six answers are refusals a caller can earn, including the one that says no
     * hands have been dealt. It is tempting to read that branch as unreachable — the leader
     * seat is only ever read from {@link #findCurrentLeaderSeat(UUID)}, which answers empty
     * until the deal claims it, so a caller with a witness must have got it from a dealt
     * session — but a caller does not have to have read anything to send a seat, and asking
     * to open the first trick before dealing is an ordinary mistake rather than a broken
     * invariant. It answers {@link HandNotDealtException} and a 409.
     *
     * <p>That branch previously answered {@link SessionNotJoinableException} carrying
     * {@code IN_PROGRESS}, which told the caller a session was not joinable while handing
     * them the status that says it is; @architecture-guardian found it in the EOP-14 Slice
     * C1 review. The status was right and the explanation contradicted itself, which is a
     * vocabulary gap rather than a policy error: {@link HandAlreadyDealtException} named one
     * state of {@code current_leader_seat} and nothing named the other, so the other had to
     * borrow a type that could not describe it.
     *
     * <p>EOP-14 Slice E split that branch in two for the same reason. A null leader seat now
     * means either that no hand has been dealt or that a hand has been dealt and played out,
     * and only the hand rows tell them apart, so one more read buys
     * {@link HandCompleteException} for the second. Nothing reaches it today: for the last
     * trick to have been resolvable every seat that still held a card had already played, so
     * no play can be in flight behind it. It is here because the alternative is a branch that
     * tells a caller its cards have not been dealt when they were dealt and finished, and an
     * unreachable branch answering a lie is worth less than an unreachable branch answering
     * the truth. Should the deal-once gate ever be reopened, this is the branch that already
     * knows the difference.
     *
     * @param sessionId          the session the update named
     * @param expectedLeaderSeat the leader seat the caller observed, or {@code null}
     *                           when the update required no deal to have happened yet
     * @return the exception to raise
     */
    private RuntimeException sessionMoved(final UUID sessionId, final Integer expectedLeaderSeat) {
        final Optional<GameSessionJpaEntity> row = sessionRows.findById(sessionId);
        if (row.isEmpty()) {
            return new SessionNotFoundException(sessionId);
        }
        final GameSessionJpaEntity session = row.get();
        if (session.getStatus() != PLAYABLE) {
            return new SessionNotJoinableException(sessionId, session.getStatus());
        }
        final Integer leaderSeat = session.getCurrentLeaderSeat();
        if (expectedLeaderSeat == null) {
            return new HandAlreadyDealtException(sessionId);
        }
        if (leaderSeat == null) {
            return handRows.existsByGameSessionId(sessionId)
                    ? new HandCompleteException(sessionId)
                    : new HandNotDealtException(sessionId);
        }
        return new OutOfTurnException(leaderSeat, expectedLeaderSeat);
    }

    /**
     * Translates a collision while filing a dealt hand.
     *
     * @param sessionId the session being dealt
     * @param collision the violation the database raised
     * @return the exception to raise
     */
    private RuntimeException dealFailure(final UUID sessionId, final DataIntegrityViolationException collision) {
        if (mentions(collision, HAND_PER_SEAT_CONSTRAINT)) {
            return new HandAlreadyDealtException(sessionId);
        }
        if (mentions(collision, HAND_SEAT_CONSTRAINT)) {
            return backstopFired(HAND_SEAT_CONSTRAINT, collision);
        }
        return collision;
    }

    /**
     * Translates a collision while filing a play.
     *
     * <p>The two seat-shaped uniques answer the same exception because they refuse the
     * same thing from two directions: one player may make one play in one trick,
     * whether the second attempt arrives under their seat or under their identifier.
     * The card-shaped unique is kept separate, because a card already played is a
     * different fact about a different subject and a caller cannot act on the two in
     * the same way.
     *
     * @param trickId   the trick being played into
     * @param play      the play that was refused
     * @param collision the violation the database raised
     * @return the exception to raise
     */
    private RuntimeException playFailure(
            final UUID trickId, final TrickPlay play, final DataIntegrityViolationException collision) {
        if (mentions(collision, PLAY_PER_SEAT_CONSTRAINT) || mentions(collision, PLAY_PER_PLAYER_CONSTRAINT)) {
            return new AlreadyPlayedInTrickException(trickId, play.seatOrder());
        }
        if (mentions(collision, PLAY_PER_CARD_CONSTRAINT)) {
            return new CardAlreadyPlayedException(trickId, play.card().cardId());
        }
        if (mentions(collision, PLAY_SEAT_CONSTRAINT)) {
            return backstopFired(PLAY_SEAT_CONSTRAINT, collision);
        }
        return collision;
    }

    /**
     * Records that a foreign key refused a seat this class had already accepted.
     *
     * <p>Both seat-binding foreign keys are backstops, and a backstop that fires is
     * not a caller's mistake: {@link #assertSeated} passed on the same facts moments
     * earlier, so either the seat moved under a column mapped as immutable or a row
     * was written past this adapter. The constraint name is logged at warning level
     * because that is the only way the incident is visible, and the violation is
     * rethrown so the caller receives a server fault rather than a refusal implying
     * they did something wrong.
     *
     * @param constraintName the constraint that fired
     * @param collision      the violation the database raised
     * @return the violation, to be rethrown
     */
    private RuntimeException backstopFired(
            final String constraintName, final DataIntegrityViolationException collision) {
        LOG.warn(
                "Constraint {} refused a seat this adapter had already accepted; a use case check was missed",
                constraintName,
                collision);
        return collision;
    }

    /**
     * Reads the faces of the cards a set of rows refers to.
     *
     * @param cardIds the card identifiers found on those rows
     * @return each card against its identifier
     */
    private Map<UUID, Card> catalogue(final List<UUID> cardIds) {
        final Map<UUID, Card> catalogue = new HashMap<>();
        for (final CardJpaEntity row : cardRows.findAllById(cardIds)) {
            final Card card = row.toDomain();
            catalogue.put(card.cardId(), card);
        }
        return Map.copyOf(catalogue);
    }

    /**
     * Removes all hands and hand-cards for a session so a new game can be dealt.
     *
     * <p>Deletes in FK order: hand-cards first, then hands.
     *
     * @param sessionId the session whose hands are to be cleared
     */
    @Override
    @Transactional
    public void clearHandsForNewGame(final UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId is required");
        final List<UUID> handIds = handRows.findByGameSessionIdOrderBySeatOrderAsc(sessionId)
                .stream()
                .map(HandJpaEntity::getId)
                .toList();
        if (!handIds.isEmpty()) {
            handCardRows.deleteByHandIdIn(handIds);
            handRows.deleteByGameSessionId(sessionId);
        }
    }

    /**
     * Removes all tricks and their plays for a session so a new game can be dealt.
     *
     * <p>Deletes in FK order: components first, then plays, then tricks.
     *
     * @param sessionId the session whose tricks are to be cleared
     */
    @Override
    @Transactional
    public void clearTricksForNewGame(final UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId is required");
        final List<UUID> trickIds = trickRows.findIdsByGameSessionId(sessionId);
        if (!trickIds.isEmpty()) {
            final List<UUID> playIds = playRows.findIdsByTrickIdIn(trickIds);
            if (!playIds.isEmpty()) {
                componentRows.deleteByTrickPlayIdIn(playIds);
                playRows.deleteByTrickIdIn(trickIds);
            }
            trickRows.deleteByGameSessionId(sessionId);
        }
    }

    /**
     * Looks up a card that storage guarantees is present.
     *
     * @param catalogue the pre-fetched card map
     * @param cardId    the identifier to look up
     * @return the card
     */
    private static Card resolve(final Map<UUID, Card> catalogue, final UUID cardId) {
        final Card card = catalogue.get(cardId);
        if (card == null) {
            throw new IllegalStateException("Card " + cardId + " is referenced by a row but absent from the catalogue");
        }
        return card;
    }

    /**
     * Converts a domain instant into the offset the columns store.
     *
     * @param occurredAt the instant
     * @return the same moment at UTC
     */
    private static OffsetDateTime at(final Instant occurredAt) {
        return occurredAt.atOffset(ZoneOffset.UTC);
    }

    /**
     * Whether a violation, or anything that caused it, names a constraint.
     *
     * <p>Matching on the name rather than the exception type is deliberate. The type
     * is the same for every unique, foreign key and check violation, so the name is
     * the only thing that says which rule was broken. Anything unrecognised is
     * rethrown, so a constraint added later fails loudly instead of being answered as
     * whichever domain exception happened to be tested first.
     *
     * @param failure        the violation
     * @param constraintName the constraint to look for
     * @return whether the violation names it
     */
    private static boolean mentions(final Throwable failure, final String constraintName) {
        Throwable cause = failure;
        while (cause != null) {
            final String message = cause.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains(constraintName)) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
