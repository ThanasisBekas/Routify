package io.routify.gitops.git;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GitRepositoryClient} — focused on the static
 * {@code computeSha256()} method and hash comparison logic.
 */
@DisplayName("GitRepositoryClient — SHA-256 hash computation")
class GitRepositoryClientTest {

    @Test
    @DisplayName("computeSha256 returns deterministic hash for same input")
    void deterministic_sameInput_sameHash() {
        String content = "apiVersion: routify/v1\nkind: GatewayConfiguration";
        String hash1 = GitRepositoryClient.computeSha256(content);
        String hash2 = GitRepositoryClient.computeSha256(content);

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    @DisplayName("computeSha256 produces different hashes for different content")
    void differentContent_differentHash() {
        String hash1 = GitRepositoryClient.computeSha256("content-a");
        String hash2 = GitRepositoryClient.computeSha256("content-b");

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    @DisplayName("computeSha256 returns 64-character lowercase hex string")
    void hashFormat_64HexChars() {
        String hash = GitRepositoryClient.computeSha256("test content");

        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("computeSha256 handles empty string")
    void emptyString_validHash() {
        String hash = GitRepositoryClient.computeSha256("");

        assertThat(hash).hasSize(64);
        // SHA-256 of empty string is well-known
        assertThat(hash).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    @DisplayName("computeSha256 handles Unicode content correctly")
    void unicodeContent_validHash() {
        String hash = GitRepositoryClient.computeSha256("日本語テスト 🚀");

        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("computeSha256 is whitespace-sensitive")
    void whitespaceSensitive() {
        String hash1 = GitRepositoryClient.computeSha256("hello world");
        String hash2 = GitRepositoryClient.computeSha256("hello  world");

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    @DisplayName("computeSha256 detects trailing newline difference")
    void trailingNewline_differentHash() {
        String hash1 = GitRepositoryClient.computeSha256("content");
        String hash2 = GitRepositoryClient.computeSha256("content\n");

        assertThat(hash1).isNotEqualTo(hash2);
    }
}

