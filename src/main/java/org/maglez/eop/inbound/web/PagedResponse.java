package org.maglez.eop.inbound.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.maglez.eop.application.port.in.PageResult;

/**
 * A page of results as it crosses the HTTP boundary.
 *
 * <p>Hand written rather than Spring's {@code PageImpl}. Spring Boot explicitly
 * warns that serialising {@code PageImpl} to JSON is unsupported and its shape is
 * not a stable contract, so relying on it would put the API's wire format at the
 * mercy of a framework upgrade. ADR-004 asks for paged lists; this is the paged
 * envelope, declared field by field in {@code docs/api/openapi.yml}.
 *
 * @param content       the items on this page
 * @param page          zero-based index of this page
 * @param size          the requested page size
 * @param totalElements total items across all pages
 * @param totalPages    total pages at this page size
 * @param <T>           the item type
 */
@Schema(name = "Page", description = "A page of results.")
public record PagedResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    /**
     * Copies the content so the response cannot be mutated after construction.
     *
     * @throws NullPointerException if the content is null
     */
    public PagedResponse {
        content = List.copyOf(content);
    }

    /**
     * Converts a use case page into its transport form.
     *
     * @param result the page returned by a use case
     * @param <T>    the item type
     * @return the transport object
     */
    public static <T> PagedResponse<T> from(final PageResult<T> result) {
        return new PagedResponse<>(
                result.content(),
                result.page(),
                result.size(),
                result.totalElements(),
                result.totalPages());
    }
}
