package org.maglez.eop.domain;

import java.util.UUID;

/**
 * Raised when a trick that already carries a winner is resolved a second time, or when
 * the trick row is gone. The storage predicate cannot tell those two apart, and both
 * answer 409; see {@code TrickPlayRepositoryAdapter}'s class comment for why conflating
 * them is safe only while nothing deletes a single trick row.
 *
 * <p>Signalled by the persistence layer, from the conditional update that records the
 * winner matching no row, and translated here so the use case layer never sees a
 * database-specific type.
 *
 * <p>This type exists because a security review of EOP-14 Slice C1 found the adapter
 * mapping that zero-row outcome to {@code IllegalStateException}, and therefore to a
 * 500, ten lines below a class comment promising that zero rows changed is "never
 * rethrown as a server fault". The claim was the correct one and the code was wrong.
 *
 * <p>The path is not exotic. Recording a resolution first advances the session's
 * leader seat and then stamps the winner on the trick. When the seat that led the
 * trick is also the seat that won it — an ordinary outcome, not an edge case — the
 * leader-seat update is idempotent, because it sets the column to the value it is
 * already being compared against. A replayed request therefore matches the session
 * row, passes the compare-and-set, and arrives at the winner update to find the
 * winner already stamped. Nothing is corrupt and nothing was written twice; the
 * caller is simply repeating work that is done. That is a 409.
 *
 * <p>Kept distinct from {@link TrickAlreadyOpenException} because a client that
 * wants to tell "somebody else opened this trick" from "this trick is already
 * finished" cannot do so from a shared type, and the two arrive at different points
 * in the same request sequence.
 *
 * <p>A plain Java exception with no Spring imports, per ADR-005.
 */
public class TrickAlreadyResolvedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID trickId;

    /**
     * Creates the exception for a trick whose winner is already recorded.
     *
     * @param trickId the trick that already carries a winner, or that no longer exists
     */
    public TrickAlreadyResolvedException(final UUID trickId) {
        super("Trick " + trickId + " already has a winner recorded");
        this.trickId = trickId;
    }

    /**
     * The trick that was resolved twice.
     *
     * @return the trick identifier
     */
    public UUID trickId() {
        return trickId;
    }
}
