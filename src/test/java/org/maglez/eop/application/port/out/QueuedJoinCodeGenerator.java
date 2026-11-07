package org.maglez.eop.application.port.out;

import java.util.ArrayDeque;
import java.util.Deque;
import org.maglez.eop.domain.JoinCode;

/**
 * Join code generator that hands out a prepared queue.
 *
 * <p>Join codes are drawn blind in production, so a test that wants to rehearse a
 * collision has to decide the sequence itself. The codes are given as strings and
 * validated on the way in, which means a test cannot accidentally arrange a
 * sequence the real generator could never produce.
 */
public final class QueuedJoinCodeGenerator implements JoinCodeGenerator {

    private final Deque<JoinCode> queued = new ArrayDeque<>();

    private int issued;

    public QueuedJoinCodeGenerator(final String... codes) {
        for (final String code : codes) {
            queued.add(new JoinCode(code));
        }
    }

    @Override
    public JoinCode nextJoinCode() {
        final var next = queued.poll();
        if (next == null) {
            throw new IllegalStateException("the join code queue ran dry after " + issued + " codes");
        }
        issued++;
        return next;
    }

    /**
     * @return how many codes were handed out
     */
    public int issued() {
        return issued;
    }
}
