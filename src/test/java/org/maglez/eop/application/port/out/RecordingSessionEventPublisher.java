package org.maglez.eop.application.port.out;

import java.util.ArrayList;
import java.util.List;
import org.maglez.eop.application.port.in.SessionEvent;

/**
 * Session event publisher that remembers what it was asked to announce.
 *
 * <p>Whether an event was published, and how many times, is the whole contract from
 * the use case's point of view — the real publisher's fan-out is tested separately.
 * The optional shared log lets a test assert that the database write happened before
 * the announcement, which matters because a subscriber told about a start before the
 * row moved could re-read the old status.
 */
public final class RecordingSessionEventPublisher implements SessionEventPublisher {

    private final List<SessionEvent> published = new ArrayList<>();
    private final List<String> interactions;

    public RecordingSessionEventPublisher() {
        this(new ArrayList<>());
    }

    /**
     * Creates the fake sharing an interaction log with another fake.
     *
     * @param interactions the log to append port names to
     */
    public RecordingSessionEventPublisher(final List<String> interactions) {
        this.interactions = interactions;
    }

    @Override
    public void publish(final SessionEvent event) {
        interactions.add("publish");
        published.add(event);
    }

    /**
     * @return the events announced, in order
     */
    public List<SessionEvent> published() {
        return List.copyOf(published);
    }
}
