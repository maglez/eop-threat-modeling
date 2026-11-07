package org.maglez.eop.application.port.in;

import org.maglez.eop.domain.InvalidInputException;

/**
 * A request for one page of results.
 *
 * <p>Deliberately not Spring's {@code Pageable}. The use case layer takes no
 * framework dependency, so paging is expressed in plain Java here and mapped to
 * whatever the persistence adapter needs on the far side of the port.
 *
 * @param page zero-based page index
 * @param size number of items per page
 */
public record PageQuery(int page, int size) {

    /** Page size used when the caller does not ask for one. */
    public static final int DEFAULT_SIZE = 20;

    /** Largest page a caller may request, so one request cannot pull the whole table. */
    public static final int MAX_SIZE = 100;

    /**
     * Rejects an out-of-range page request rather than silently clamping it.
     * Silent clamping hides client bugs; an explicit rejection does not.
     *
     * @throws InvalidInputException if the page index is negative or the size is out of range
     */
    public PageQuery {
        if (page < 0) {
            throw new InvalidInputException("page must not be negative, was " + page);
        }
        if (size < 1) {
            throw new InvalidInputException("size must be at least 1, was " + size);
        }
        if (size > MAX_SIZE) {
            throw new InvalidInputException("size must be at most " + MAX_SIZE + ", was " + size);
        }
    }

    /**
     * A request for the first page at the default size.
     *
     * @return the default page query
     */
    public static PageQuery firstPage() {
        return new PageQuery(0, DEFAULT_SIZE);
    }
}
