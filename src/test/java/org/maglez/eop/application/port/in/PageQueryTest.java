package org.maglez.eop.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.maglez.eop.domain.InvalidInputException;

@DisplayName("PageQuery")
class PageQueryTest {

    @Test
    @DisplayName("accepts the first page at the default size")
    void shouldProvideAFirstPage() {
        final PageQuery query = PageQuery.firstPage();

        assertThat(query.page()).isZero();
        assertThat(query.size()).isEqualTo(PageQuery.DEFAULT_SIZE);
    }

    @Test
    @DisplayName("rejects a negative page rather than treating it as the first")
    void shouldRejectNegativePage() {
        assertThatExceptionOfType(InvalidInputException.class)
                .isThrownBy(() -> new PageQuery(-1, 10))
                .withMessageContaining("page must not be negative");
    }

    @Test
    @DisplayName("rejects a non-positive size")
    void shouldRejectSizeBelowOne() {
        assertThatExceptionOfType(InvalidInputException.class)
                .isThrownBy(() -> new PageQuery(0, 0))
                .withMessageContaining("size must be at least 1");
    }

    @Test
    @DisplayName("rejects a size above the cap, so one request cannot pull the whole table")
    void shouldRejectSizeAboveMaximum() {
        assertThatExceptionOfType(InvalidInputException.class)
                .isThrownBy(() -> new PageQuery(0, PageQuery.MAX_SIZE + 1))
                .withMessageContaining("size must be at most 100");
    }

    @Test
    @DisplayName("accepts the boundary values on either side")
    void shouldAcceptBoundaryValues() {
        assertThat(new PageQuery(0, 1).size()).isEqualTo(1);
        assertThat(new PageQuery(0, PageQuery.MAX_SIZE).size()).isEqualTo(PageQuery.MAX_SIZE);
    }
}
