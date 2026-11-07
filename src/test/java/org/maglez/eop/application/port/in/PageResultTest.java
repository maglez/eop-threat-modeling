package org.maglez.eop.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PageResult")
class PageResultTest {

    private static final List<String> TWO_ITEMS = List.of("a", "b");

    @Test
    @DisplayName("rounds the page count up, so a partial last page still counts")
    void shouldRoundTotalPagesUp() {
        assertThat(new PageResult<>(TWO_ITEMS, 0, 2, 5).totalPages()).isEqualTo(3);
        assertThat(new PageResult<>(TWO_ITEMS, 0, 2, 4).totalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("reports no pages when there is nothing to page through")
    void shouldReportZeroPagesWhenEmpty() {
        assertThat(new PageResult<>(List.of(), 0, 20, 0).totalPages()).isZero();
    }

    @Test
    @DisplayName("copies the content, so mutating the caller's list cannot change the page")
    void shouldCopyContentDefensively() {
        final List<String> mutable = new ArrayList<>(TWO_ITEMS);

        final PageResult<String> result = new PageResult<>(mutable, 0, 2, 2);
        mutable.add("c");

        assertThat(result.content()).containsExactly("a", "b");
    }

    @Test
    @DisplayName("maps the items and leaves the paging metadata alone")
    void shouldMapContentOnly() {
        final PageResult<Integer> mapped = new PageResult<>(TWO_ITEMS, 1, 2, 7).map(String::length);

        assertThat(mapped.content()).containsExactly(1, 1);
        assertThat(mapped.page()).isEqualTo(1);
        assertThat(mapped.size()).isEqualTo(2);
        assertThat(mapped.totalElements()).isEqualTo(7);
        assertThat(mapped.totalPages()).isEqualTo(4);
    }

    @Test
    @DisplayName("rejects a malformed page")
    void shouldRejectMalformedPages() {
        assertThatNullPointerException().isThrownBy(() -> new PageResult<>(null, 0, 1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new PageResult<>(List.of(), -1, 1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new PageResult<>(List.of(), 0, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new PageResult<>(List.of(), 0, 1, -1));
        assertThatNullPointerException().isThrownBy(() -> new PageResult<>(TWO_ITEMS, 0, 2, 2).map(null));
    }
}
