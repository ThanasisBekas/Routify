package io.routify.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link PageResponse} factory methods and computed fields.
 *
 * <p>Verifies correct computation of {@code totalPages}, {@code first}, and {@code last}
 * flags across edge cases: empty results, single page, multi-page, exact boundary, and
 * zero-size guard.
 */
class PageResponseTest {

    // ─── PageResponse.of() ────────────────────────────────────────────────────

    @Nested
    @DisplayName("PageResponse.of() factory method")
    class OfFactory {

        @Test
        @DisplayName("First page of multi-page result")
        void firstPageOfMultiPageResult() {
            var page = PageResponse.of(List.of("a", "b", "c"), 0, 3, 10);

            assertThat(page.content()).containsExactly("a", "b", "c");
            assertThat(page.page()).isEqualTo(0);
            assertThat(page.size()).isEqualTo(3);
            assertThat(page.totalElements()).isEqualTo(10);
            assertThat(page.totalPages()).isEqualTo(4); // ceil(10/3) = 4
            assertThat(page.first()).isTrue();
            assertThat(page.last()).isFalse();
        }

        @Test
        @DisplayName("Last page of multi-page result")
        void lastPageOfMultiPageResult() {
            var page = PageResponse.of(List.of("j"), 3, 3, 10);

            assertThat(page.content()).containsExactly("j");
            assertThat(page.page()).isEqualTo(3);
            assertThat(page.totalPages()).isEqualTo(4);
            assertThat(page.first()).isFalse();
            assertThat(page.last()).isTrue();
        }

        @Test
        @DisplayName("Middle page is neither first nor last")
        void middlePage() {
            var page = PageResponse.of(List.of("d", "e", "f"), 1, 3, 10);

            assertThat(page.page()).isEqualTo(1);
            assertThat(page.first()).isFalse();
            assertThat(page.last()).isFalse();
        }

        @Test
        @DisplayName("Single page result is both first and last")
        void singlePage() {
            var page = PageResponse.of(List.of("x", "y"), 0, 10, 2);

            assertThat(page.totalPages()).isEqualTo(1);
            assertThat(page.first()).isTrue();
            assertThat(page.last()).isTrue();
        }

        @Test
        @DisplayName("Empty result with zero total elements")
        void emptyResult() {
            var page = PageResponse.of(List.of(), 0, 20, 0);

            assertThat(page.content()).isEmpty();
            assertThat(page.totalElements()).isEqualTo(0);
            assertThat(page.totalPages()).isEqualTo(0);
            assertThat(page.first()).isTrue();
            assertThat(page.last()).isTrue(); // page 0 >= totalPages(0) - 1 = -1
        }

        @Test
        @DisplayName("Exact boundary: totalElements is a multiple of size")
        void exactBoundary() {
            var page = PageResponse.of(List.of("a", "b"), 0, 2, 6);

            assertThat(page.totalPages()).isEqualTo(3); // 6/2 = 3 (no remainder)
        }

        @Test
        @DisplayName("Size zero defaults totalPages to 1 (no division by zero)")
        void sizeZeroGuard() {
            var page = PageResponse.of(List.of(), 0, 0, 5);

            assertThat(page.totalPages()).isEqualTo(1);
        }

        @Test
        @DisplayName("Large dataset computes correctly")
        void largeDataset() {
            var page = PageResponse.of(List.of("item"), 499, 20, 10_000);

            assertThat(page.totalPages()).isEqualTo(500);
            assertThat(page.first()).isFalse();
            assertThat(page.last()).isTrue(); // 499 >= 500 - 1
        }

        @Test
        @DisplayName("Generic type parameter is preserved")
        void genericTypePreserved() {
            PageResponse<Integer> page = PageResponse.of(List.of(1, 2, 3), 0, 3, 3);

            assertThat(page.content()).containsExactly(1, 2, 3);
        }
    }

    // ─── PageResponse.from() ──────────────────────────────────────────────────

    @Nested
    @DisplayName("PageResponse.from() Spring Data Page adapter")
    class FromFactory {

        @Test
        @DisplayName("Converts Spring Data Page to PageResponse preserving all fields")
        void convertsSpringDataPage() {
            Page<String> springPage = new PageImpl<>(
                    List.of("a", "b", "c"),
                    PageRequest.of(0, 3),
                    10
            );

            var page = PageResponse.from(springPage);

            assertThat(page.content()).containsExactly("a", "b", "c");
            assertThat(page.page()).isEqualTo(0);
            assertThat(page.size()).isEqualTo(3);
            assertThat(page.totalElements()).isEqualTo(10);
            assertThat(page.totalPages()).isEqualTo(4);
            assertThat(page.first()).isTrue();
            assertThat(page.last()).isFalse();
        }

        @Test
        @DisplayName("Converts empty Spring Data Page")
        void convertsEmptySpringDataPage() {
            Page<String> springPage = new PageImpl<>(
                    Collections.emptyList(),
                    PageRequest.of(0, 20),
                    0
            );

            var page = PageResponse.from(springPage);

            assertThat(page.content()).isEmpty();
            assertThat(page.totalElements()).isEqualTo(0);
            assertThat(page.totalPages()).isEqualTo(0);
            assertThat(page.first()).isTrue();
            assertThat(page.last()).isTrue();
        }

        @Test
        @DisplayName("Last page from Spring Data is detected correctly")
        void lastPageFromSpringData() {
            Page<String> springPage = new PageImpl<>(
                    List.of("z"),
                    PageRequest.of(3, 3),
                    10
            );

            var page = PageResponse.from(springPage);

            assertThat(page.page()).isEqualTo(3);
            assertThat(page.first()).isFalse();
            assertThat(page.last()).isTrue();
        }
    }

    // ─── Record equality ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Record equality")
    class RecordEquality {

        @Test
        @DisplayName("Two PageResponse records with same fields are equal")
        void equalRecords() {
            var a = PageResponse.of(List.of("x"), 0, 10, 1);
            var b = PageResponse.of(List.of("x"), 0, 10, 1);

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("PageResponse records with different content are not equal")
        void unequalRecords() {
            var a = PageResponse.of(List.of("x"), 0, 10, 1);
            var b = PageResponse.of(List.of("y"), 0, 10, 1);

            assertThat(a).isNotEqualTo(b);
        }
    }
}

