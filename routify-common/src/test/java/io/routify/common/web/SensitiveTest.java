package io.routify.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link Sensitive} utilities: scalar helpers ({@link Sensitive#mask(String)},
 * {@link Sensitive#isMasked(String)}) and reflection-based {@link Sensitive#maskFields(Object)}.
 *
 * <p>Covers nested objects, collections, null/blank edge cases, inheritance, and
 * non-String annotated fields (ignored).
 */
class SensitiveTest {

    // ─── Scalar helpers ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Sensitive.mask()")
    class MaskScalar {

        @Test
        @DisplayName("Non-blank value is masked")
        void nonBlankIsMasked() {
            assertThat(Sensitive.mask("my-secret-key")).isEqualTo(Sensitive.MASK);
        }

        @Test
        @DisplayName("Null value returns null")
        void nullReturnsNull() {
            assertThat(Sensitive.mask(null)).isNull();
        }

        @Test
        @DisplayName("Blank value returns null")
        void blankReturnsNull() {
            assertThat(Sensitive.mask("   ")).isNull();
        }

        @Test
        @DisplayName("Empty string returns null")
        void emptyReturnsNull() {
            assertThat(Sensitive.mask("")).isNull();
        }

        @Test
        @DisplayName("Mask constant value is '[REDACTED]'")
        void maskConstant() {
            assertThat(Sensitive.MASK).isEqualTo("[REDACTED]");
        }
    }

    // ─── isMasked ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Sensitive.isMasked()")
    class IsMasked {

        @Test
        @DisplayName("MASK sentinel returns true")
        void maskSentinelReturnsTrue() {
            assertThat(Sensitive.isMasked("[REDACTED]")).isTrue();
        }

        @Test
        @DisplayName("Regular string returns false")
        void regularStringReturnsFalse() {
            assertThat(Sensitive.isMasked("some-password")).isFalse();
        }

        @Test
        @DisplayName("Null returns false")
        void nullReturnsFalse() {
            assertThat(Sensitive.isMasked(null)).isFalse();
        }

        @Test
        @DisplayName("Empty string returns false")
        void emptyReturnsFalse() {
            assertThat(Sensitive.isMasked("")).isFalse();
        }

        @Test
        @DisplayName("Case-sensitive: lowercase redacted returns false")
        void caseSensitive() {
            assertThat(Sensitive.isMasked("[redacted]")).isFalse();
        }

        @Test
        @DisplayName("Partial match returns false")
        void partialMatch() {
            assertThat(Sensitive.isMasked("[REDACTED] extra")).isFalse();
        }
    }

    // ─── maskFields: simple object ────────────────────────────────────────────

    @Nested
    @DisplayName("Sensitive.maskFields() — simple object")
    class MaskFieldsSimple {

        @Test
        @DisplayName("Annotated String fields are masked")
        void annotatedFieldsMasked() {
            var dto = new SimpleDto();
            dto.clientId = "public-id";
            dto.clientSecret = "super-secret";
            dto.password = "hunter2";

            Sensitive.maskFields(dto);

            assertThat(dto.clientId).isEqualTo("public-id"); // not annotated
            assertThat(dto.clientSecret).isEqualTo(Sensitive.MASK);
            assertThat(dto.password).isEqualTo(Sensitive.MASK);
        }

        @Test
        @DisplayName("Null annotated fields become null (not MASK)")
        void nullAnnotatedFieldsStayNull() {
            var dto = new SimpleDto();
            dto.clientId = "id";
            dto.clientSecret = null;
            dto.password = null;

            Sensitive.maskFields(dto);

            assertThat(dto.clientSecret).isNull();
            assertThat(dto.password).isNull();
        }

        @Test
        @DisplayName("Blank annotated fields become null")
        void blankAnnotatedFieldsBecomeNull() {
            var dto = new SimpleDto();
            dto.clientId = "id";
            dto.clientSecret = "   ";
            dto.password = "";

            Sensitive.maskFields(dto);

            assertThat(dto.clientSecret).isNull();
            assertThat(dto.password).isNull();
        }

        @Test
        @DisplayName("Null object is a no-op")
        void nullObjectIsNoOp() {
            Sensitive.maskFields(null); // must not throw
        }
    }

    // ─── maskFields: nested objects ────────────────────────────────────────────

    @Nested
    @DisplayName("Sensitive.maskFields() — nested objects")
    class MaskFieldsNested {

        @Test
        @DisplayName("Nested object's sensitive fields are masked recursively")
        void nestedObjectMasked() {
            var nested = new SimpleDto();
            nested.clientId = "nested-id";
            nested.clientSecret = "nested-secret";
            nested.password = "nested-pass";

            var parent = new ParentDto();
            parent.name = "parent";
            parent.child = nested;

            Sensitive.maskFields(parent);

            assertThat(parent.name).isEqualTo("parent");
            assertThat(parent.child.clientId).isEqualTo("nested-id");
            assertThat(parent.child.clientSecret).isEqualTo(Sensitive.MASK);
            assertThat(parent.child.password).isEqualTo(Sensitive.MASK);
        }

        @Test
        @DisplayName("Null nested object is skipped gracefully")
        void nullNestedObjectSkipped() {
            var parent = new ParentDto();
            parent.name = "parent";
            parent.child = null;

            Sensitive.maskFields(parent); // must not throw
            assertThat(parent.name).isEqualTo("parent");
        }
    }

    // ─── maskFields: collections ──────────────────────────────────────────────

    @Nested
    @DisplayName("Sensitive.maskFields() — collections")
    class MaskFieldsCollections {

        @Test
        @DisplayName("Collection elements are masked recursively")
        void collectionElementsMasked() {
            var item1 = new SimpleDto();
            item1.clientId = "id-1";
            item1.clientSecret = "secret-1";
            item1.password = "pass-1";

            var item2 = new SimpleDto();
            item2.clientId = "id-2";
            item2.clientSecret = "secret-2";
            item2.password = "pass-2";

            var holder = new CollectionHolder();
            holder.items = new ArrayList<>(List.of(item1, item2));

            Sensitive.maskFields(holder);

            assertThat(holder.items.get(0).clientId).isEqualTo("id-1");
            assertThat(holder.items.get(0).clientSecret).isEqualTo(Sensitive.MASK);
            assertThat(holder.items.get(1).clientSecret).isEqualTo(Sensitive.MASK);
        }

        @Test
        @DisplayName("Null collection is skipped gracefully")
        void nullCollectionSkipped() {
            var holder = new CollectionHolder();
            holder.items = null;

            Sensitive.maskFields(holder); // must not throw
        }

        @Test
        @DisplayName("Empty collection is a no-op")
        void emptyCollectionIsNoOp() {
            var holder = new CollectionHolder();
            holder.items = new ArrayList<>();

            Sensitive.maskFields(holder);

            assertThat(holder.items).isEmpty();
        }
    }

    // ─── maskFields: inheritance ──────────────────────────────────────────────

    @Nested
    @DisplayName("Sensitive.maskFields() — inheritance")
    class MaskFieldsInheritance {

        @Test
        @DisplayName("Superclass sensitive fields are masked")
        void superclassFieldsMasked() {
            var child = new ChildDto();
            child.clientId = "id";
            child.clientSecret = "inherited-secret";
            child.password = "inherited-pass";
            child.apiKey = "child-api-key";
            child.description = "child-desc";

            Sensitive.maskFields(child);

            assertThat(child.clientSecret).isEqualTo(Sensitive.MASK);
            assertThat(child.password).isEqualTo(Sensitive.MASK);
            assertThat(child.apiKey).isEqualTo(Sensitive.MASK);
            assertThat(child.description).isEqualTo("child-desc");
        }
    }

    // ─── Test DTOs ────────────────────────────────────────────────────────────

    static class SimpleDto {
        String clientId;

        @SensitiveField
        String clientSecret;

        @SensitiveField
        String password;
    }

    static class ParentDto {
        String name;
        SimpleDto child;
    }

    static class CollectionHolder {
        List<SimpleDto> items;
    }

    static class ChildDto extends SimpleDto {
        @SensitiveField
        String apiKey;

        String description;
    }
}

