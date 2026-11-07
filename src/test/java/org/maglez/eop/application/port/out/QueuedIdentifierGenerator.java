package org.maglez.eop.application.port.out;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import org.maglez.eop.application.port.in.CreateSessionPort;

/**
 * Identifier generator that hands out a prepared queue.
 *
 * <p>A queue rather than a single value, because {@link CreateSessionPort} draws
 * one identifier for the player and then another for every session it attempts. A
 * fake returning a constant would hide that, and a test asserting the player kept
 * its identity across a retry would pass for the wrong reason.
 *
 * <p>Running the queue dry throws rather than returning a random identifier: an
 * exhausted queue means the use case made more calls than the test expected, which
 * is a finding, not something to paper over.
 */
public final class QueuedIdentifierGenerator implements IdentifierGenerator {

    private final Deque<UUID> queued = new ArrayDeque<>();

    private int issued;

    public QueuedIdentifierGenerator(final UUID... identifiers) {
        queued.addAll(List.of(identifiers));
    }

    @Override
    public UUID nextIdentifier() {
        final var next = queued.poll();
        if (next == null) {
            throw new IllegalStateException("the identifier queue ran dry after " + issued + " identifiers");
        }
        issued++;
        return next;
    }

    /**
     * @return how many identifiers were handed out
     */
    public int issued() {
        return issued;
    }
}
