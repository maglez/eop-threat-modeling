package org.maglez.eop.domain;

/**
 * Whether a player currently appears to be watching the event stream.
 *
 * <p>Advisory, and sometimes wrong. The stream's subscriber registry is a
 * broadcast list, not a presence list: a client that closed its laptop lid is
 * only discovered on the next write to it, and the EOP-8 spike measured the
 * server reporting two live subscribers after both clients had gone away
 * (ADR-014). So this value can over-report {@link #CONNECTED}.
 *
 * <p>That makes it a display hint and never an input to a game rule. A rule that
 * skipped a disconnected player's turn would be built on a value that lies.
 */
public enum ConnectionStatus {

    /** Believed to be listening. Believed, not known. */
    CONNECTED,
    /** Known to have gone away, because a write to the stream failed. */
    DISCONNECTED
}
