package org.maglez.eop.application.port.out;

import org.maglez.eop.application.port.in.SessionEvent;

/**
 * Port through which the application announces that a session changed.
 *
 * <p>Declared here so that the transport stays outside. The use cases know that a
 * player joined; they do not know that the announcement travels as a server-sent
 * event, and nothing in this layer imports the emitter type that carries it.
 *
 * <p>Publishing must not fail a request. A subscriber that has gone away without
 * saying so is the normal case rather than an exceptional one — the EOP-8 spike
 * watched the server report two live subscribers after both clients had been
 * killed — so the implementation drops the dead ones and returns. A player whose
 * join succeeded must not receive an error because somebody else's browser closed.
 */
public interface SessionEventPublisher {

    /**
     * Announces a change to everyone currently listening to that session.
     *
     * <p>Delivery is best effort and unordered with respect to the HTTP response
     * that triggered it, so a client can see its own change twice: once in the
     * response body and once as a notification. Clients are required to tolerate
     * that rather than the server required to prevent it.
     *
     * @param event what changed, and where
     */
    void publish(SessionEvent event);
}
