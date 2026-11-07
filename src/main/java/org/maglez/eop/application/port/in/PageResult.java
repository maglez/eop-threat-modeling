package org.maglez.eop.application.port.in;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * One page of results, with enough metadata for a caller to page through.
 *
 * <p>Deliberately not Spring's {@code Page}. Beyond the Hexagonal Architecture
 * reason, Spring Boot does not support serialising {@code PageImpl} as a stable
 * JSON contract, so the web layer needs its own envelope regardless.
 *
 * @param content       the items on this page
 * @param page          zero-based index of this page
 * @param size          the requested page size
 * @param totalElements total items across all pages
 * @param <T>           the item type
 */
public record PageResult<T>(List<T> content, int page, int size, long totalElements) {

    /**
     * Copies the content defensively so the page cannot be mutated after construction.
     *
     * @throws NullPointerException     if the content is null
     * @throws IllegalArgumentException if any count is negative or the size is not positive
     */
    public PageResult {
        Objects.requireNonNull(content, "content is required");
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative, was " + page);
        }
        if (size < 1) {
            throw new IllegalArgumentException("size must be at least 1, was " + size);
        }
        if (totalElements < 0) {
            throw new IllegalArgumentException("totalElements must not be negative, was " + totalElements);
        }
        content = List.copyOf(content);
    }

    /**
     * Total number of pages available at this page size.
     *
     * @return the page count, zero when there is nothing to page through
     */
    public int totalPages() {
        return (int) ((totalElements + size - 1) / size);
    }

    /**
     * Converts the items on this page, keeping the paging metadata unchanged.
     * This is how a domain page becomes a page of transport objects without the
     * use case knowing what those objects are.
     *
     * @param mapper converts one item
     * @param <R>    the converted item type
     * @return a page of converted items
     */
    public <R> PageResult<R> map(final Function<? super T, ? extends R> mapper) {
        Objects.requireNonNull(mapper, "mapper is required");
        return new PageResult<>(content.stream().<R>map(mapper::apply).toList(), page, size, totalElements);
    }
}
