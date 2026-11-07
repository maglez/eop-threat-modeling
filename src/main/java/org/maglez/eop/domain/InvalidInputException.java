package org.maglez.eop.domain;

/**
 * A value supplied by whoever called us is outside the range the domain accepts
 * — a negative page index, a display name carrying control characters, a note
 * longer than the column that stores it.
 *
 * <p>This type exists to make one distinction enforceable that was previously
 * only assumed: that the message on the exception was written for a caller to
 * read. The web layer answers this with a 400 problem detail carrying that
 * message verbatim, so the text must never contain an internal identifier, a
 * path, a connection string, or anything else a maintainer would want but a
 * caller has not earned.
 *
 * <p>Before this type existed the web layer mapped {@code IllegalArgumentException}
 * itself, and so promised something it could not keep. That type is thrown
 * pervasively by the JDK, Hibernate, Jackson and Spring, and
 * {@code NumberFormatException} extends it, so any library-internal message
 * could be echoed to a caller verbatim. Worse, because that mapping was more
 * specific than the catch-all for {@code Exception}, such a fault bypassed the
 * only error-level log in the application: an internal defect was reported to
 * the caller as though they had made a client-side mistake, and left no record
 * at all. Narrowing the mapping to this type closes both holes at once.
 *
 * <p>It extends {@code IllegalArgumentException} rather than
 * {@code RuntimeException}, which is deliberate and inverts the pattern the
 * other domain exceptions follow. The reason is that the persistence adapters
 * are {@code @Repository} beans, so Spring's exception translation rewrites an
 * {@code IllegalArgumentException} raised while reconstituting an entity from a
 * row into a {@code DataAccessException}. That is exactly the behaviour a
 * corrupt row deserves — a logged 500, not a 400 billed to the caller — and a
 * value object cannot tell whether it is being built from a request or from a
 * row. Extending the JDK type lets one guard clause serve both, with the layer
 * the throw passes through deciding the outcome.
 */
public class InvalidInputException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message why the value was refused, in terms a caller can act on
     */
    public InvalidInputException(final String message) {
        super(message);
    }
}
