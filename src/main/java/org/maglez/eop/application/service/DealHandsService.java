package org.maglez.eop.application.service;

import java.util.Objects;
import java.util.UUID;
import org.maglez.eop.domain.GameSession;

import org.maglez.eop.domain.NotFacilitatorException;

import org.maglez.eop.application.port.in.DealHandsPort;
import org.maglez.eop.application.port.in.NewGamePort;
import org.maglez.eop.application.port.in.ResolvePlayerPort;
import org.maglez.eop.application.port.in.StartSessionPort;
import org.maglez.eop.application.port.out.HandRepository;
import org.maglez.eop.domain.NoTamperingCardDealtException;
import org.maglez.eop.domain.TooFewPlayersException;

/**
 * Deals the whole deck to the seated players and records the opening lead.
 *
 * <p>This is a use case in its own right rather than a step inside {@link StartSessionPort}. The
 * seam is chosen rather than forced, and it is worth being exact about that, because an earlier draft
 * of this javadoc claimed no alternative existed. {@link HandRepository#recordDeal} refuses a session
 * that had not started at the moment of the write, so the status transition has to be
 * <em>written</em> first &mdash; but not necessarily committed, since a caller already inside a
 * transaction reads its own writes. A {@code @Transactional} composer in the outer ring could
 * therefore join both writes into one atomic act without putting
 * {@code org.springframework.transaction} anywhere near this layer. It is declined for a different
 * reason: {@link StartSessionPort} announces {@code GAME_STARTED} over in-process SSE, and no
 * transaction can roll back a delivered frame, so a deal that failed after that announcement would
 * leave every subscriber told a game had started that was never dealt. Keeping the two apart also
 * keeps their failures apart: "you are not the facilitator" and "there are only two of you" are
 * different answers from "the deal has already happened", and a caller that receives them through
 * one call cannot tell which write it needs to retry (ADR-025).
 *
 * <p>Dealing twice is not a hazard that this class defends against. {@code recordDeal} writes the
 * opening leader seat only where none is recorded, so two facilitators pressing at once produce one
 * deal and one refusal, decided by the database rather than by a check up here. A status or
 * already-dealt pre-check in this method would read the state, let go of it, and then write &mdash;
 * an illusion of safety that duplicates the only check that actually holds (ADR-020). What this
 * class owes instead is the thing no port and no collaborator can do for itself: establishing who is
 * asking, before anything else happens.
 *
 * <p>Authorisation is therefore the first statement in {@link #execute}, and once it has passed,
 * this class does nothing else of its own &mdash; the deal itself belongs to {@link HandDealer},
 * which {@link NewGamePort} needs in exactly the same form and which carried a verbatim second
 * copy of it until EOP-190. Read {@link HandDealer} for why the shuffle, the seat count, the silent
 * return and the card-free announcement are shaped the way they are. {@link HandRepository} takes no
 * acting player and its javadoc says plainly that authorising the requester is the caller's
 * obligation, so this method is the only place it can happen. The refusals underneath it are
 * informative by design &mdash; they distinguish a session that has not started from one already
 * dealt &mdash; which is the right answer for a member of the table and an information leak for
 * anybody else (ADR-024).
 */
public class DealHandsService implements DealHandsPort {

    private final ResolvePlayerPort resolvePlayerPort;
    private final HandDealer handDealer;

    /**
     * Creates the use case.
     *
     * @param resolvePlayerPort resolves the identity token into a seated player
     * @param handDealer shuffles, deals, records the opening lead and announces the deal
     */
    public DealHandsService(
            final ResolvePlayerPort resolvePlayerPort, final HandDealer handDealer) {
        this.resolvePlayerPort =
                Objects.requireNonNull(resolvePlayerPort, "resolvePlayerPort is required");
        this.handDealer = Objects.requireNonNull(handDealer, "handDealer is required");
    }

    /**
     * Deals the deck to every seated player and records which seat leads the first trick.
     *
     * @param sessionId the session whose players are dealt to
     * @param playerToken the identity token of the player asking to deal
     * @throws NullPointerException if sessionId is null
     * @throws org.maglez.eop.domain.SessionNotFoundException if no such session exists
     * @throws org.maglez.eop.domain.PlayerNotRecognisedException if the token names nobody at this
     *     table, or no token was given
     * @throws NotFacilitatorException if the caller is seated but is not the facilitator
     * @throws TooFewPlayersException if fewer than {@link GameSession#MINIMUM_PLAYERS_TO_START}
     *     players are seated
     * @throws org.maglez.eop.domain.SessionNotJoinableException if the session was not playable at
     *     the moment of the write, which includes a session that had not yet started
     * @throws org.maglez.eop.domain.HandAlreadyDealtException if this session has already been dealt
     * @throws org.maglez.eop.domain.PlayerNotInSessionException if a seat named in the deal is not
     *     seated in this session
     * @throws org.maglez.eop.domain.NotYourSeatException if a player named in the deal does not hold
     *     the seat the deal gives them
     * @throws NoTamperingCardDealtException if the shuffled deck contained no Tampering card, so no
     *     opening lead can be derived
     */
    public void execute(final UUID sessionId, final String playerToken) {
        final var resolved = resolvePlayerPort.execute(sessionId, playerToken);
        final var session = resolved.session();
        final var actingPlayer = resolved.player();

        if (!actingPlayer.canStartPlay()) {
            throw new NotFacilitatorException(sessionId, actingPlayer.playerId());
        }

        handDealer.deal(session);
    }
}
