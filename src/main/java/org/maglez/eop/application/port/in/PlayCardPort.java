package org.maglez.eop.application.port.in;

import org.maglez.eop.domain.Trick;
import org.maglez.eop.domain.CardNotFoundException;
import org.maglez.eop.domain.HandCompleteException;
import org.maglez.eop.domain.HandNotDealtException;
import org.maglez.eop.domain.PlayerNotInSessionException;

/**
 * Inbound port for the playcard operation.
 *
 * <p>Implemented by {@code PlayCardService} in the application service layer. A driving
 * adapter calls the method here; the service orchestrates domain and outbound ports.
 */
public interface PlayCardPort {

    /**
     * Plays the named card for the player the token identifies.
     *
     * @param command what the player asked to play
     * @return the trick with this play appended
     * @throws NullPointerException if command is null
     * @throws org.maglez.eop.domain.SessionNotFoundException if no such session exists
     * @throws org.maglez.eop.domain.PlayerNotRecognisedException if the token names nobody at this
     *     table, or no token was given
     * @throws HandNotDealtException if this session has not been dealt
     * @throws PlayerNotInSessionException if the acting player holds no hand in this session
     * @throws HandCompleteException if every card dealt in this session has already been played
     * @throws CardNotFoundException if the card identifier names no card in the deck
     * @throws org.maglez.eop.domain.CardNotInHandException if the player does not hold that card
     * @throws org.maglez.eop.domain.OutOfTurnException if it is not this seat's turn
     * @throws org.maglez.eop.domain.MustFollowSuitException if the player holds the led suit and
     *     played something else
     * @throws org.maglez.eop.domain.NotYourSeatException if the play does not belong to the acting
     *     seat
     * @throws org.maglez.eop.domain.PlayerMismatchException if the seat is held by another player
     * @throws org.maglez.eop.domain.AlreadyPlayedInTrickException if this seat has already played
     *     into this trick
     * @throws org.maglez.eop.domain.CardAlreadyPlayedException if that card was already played
     * @throws org.maglez.eop.domain.TrickAlreadyOpenException if another request opened a trick first
     * @throws org.maglez.eop.domain.SessionNotJoinableException if the session was not playable at
     *     the moment of the write
     
     */
    Trick execute(PlayCardCommand command);
}
