package org.maglez.eop.application.port.in;

import org.maglez.eop.domain.Trick;
import java.util.UUID;

import org.maglez.eop.application.service.TrickJournal;
import org.maglez.eop.domain.HandNotDealtException;
import org.maglez.eop.domain.NoTrickToResolveException;
import org.maglez.eop.domain.PlayerNotRecognisedException;
import org.maglez.eop.domain.SessionNotFoundException;
import org.maglez.eop.domain.SessionNotJoinableException;
import org.maglez.eop.domain.TrickAlreadyResolvedException;
import org.maglez.eop.domain.TrickNotCompleteException;
import org.maglez.eop.domain.WinningPlayNotInTrickException;

/**
 * Inbound port for the resolvetrick operation.
 *
 * <p>Implemented by {@code ResolveTrickService} in the application service layer. A driving
 * adapter calls the method here; the service orchestrates domain and outbound ports.
 */
public interface ResolveTrickPort {

    /**
     * Resolves the session's current trick.
     *
     * <p>When this resolution empties the last hand, the game is completed too. That completion is
     * a compare-and-set against a session a concurrent facilitator call may already have completed,
     * in which case {@code SessionNotInProgressException} is raised and swallowed as success. Since
     * EOP-190 both the completion and that catch live in {@link TrickJournal} rather than here, so
     * this method neither throws nor catches it and it is deliberately absent from the list below.
     *
     * @param sessionId the session whose trick is to be resolved
     * @param playerToken the requester's identity token, which may be null or unrecognised
     * @return the trick with its winner decided
     * @throws NullPointerException if sessionId is null
     * @throws SessionNotFoundException if no session has that identifier
     * @throws PlayerNotRecognisedException if the token names nobody at this table
     * @throws HandNotDealtException if the hands have not been dealt
     * @throws NoTrickToResolveException if no trick has been opened yet
     * @throws TrickNotCompleteException if a seat holding cards has not played into the trick
     * @throws TrickAlreadyResolvedException if the trick already has a winner
     * @throws WinningPlayNotInTrickException if the winning play was not made into this trick
     * @throws SessionNotJoinableException if the session is no longer playable
     
     */
    Trick execute(UUID sessionId, String playerToken);
}
