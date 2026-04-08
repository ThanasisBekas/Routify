package io.routify.gitops.git;

import io.routify.gitops.config.GitOpsProperties;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GitCredentialsProvider} — HTTPS credential resolution
 * and SSH URL detection.
 */
@DisplayName("GitCredentialsProvider")
class GitCredentialsProviderTest {

    private GitOpsProperties properties;
    private GitCredentialsProvider provider;

    @BeforeEach
    void setUp() {
        properties = new GitOpsProperties();
        properties.setRepositoryUrl("https://github.com/org/repo.git");
        provider = new GitCredentialsProvider(properties);
    }

    @Test
    @DisplayName("getHttpsCredentials returns provider when username and password are set")
    void httpsCredentials_bothSet_returnsProvider() {
        properties.setHttpsUsername("user");
        properties.setHttpsPassword("pass");

        CredentialsProvider creds = provider.getHttpsCredentials();

        assertThat(creds).isNotNull();
    }

    @Test
    @DisplayName("getHttpsCredentials returns null when username is blank")
    void httpsCredentials_blankUsername_returnsNull() {
        properties.setHttpsUsername("");
        properties.setHttpsPassword("pass");

        assertThat(provider.getHttpsCredentials()).isNull();
    }

    @Test
    @DisplayName("getHttpsCredentials returns null when password is blank")
    void httpsCredentials_blankPassword_returnsNull() {
        properties.setHttpsUsername("user");
        properties.setHttpsPassword("");

        assertThat(provider.getHttpsCredentials()).isNull();
    }

    @Test
    @DisplayName("getHttpsCredentials returns null when both are blank")
    void httpsCredentials_bothBlank_returnsNull() {
        properties.setHttpsUsername("");
        properties.setHttpsPassword("");

        assertThat(provider.getHttpsCredentials()).isNull();
    }

    @Test
    @DisplayName("isSshUrl returns true for git@ prefix")
    void isSshUrl_gitAt_true() {
        properties.setRepositoryUrl("git@github.com:org/repo.git");

        assertThat(provider.isSshUrl()).isTrue();
    }

    @Test
    @DisplayName("isSshUrl returns true for ssh:// prefix")
    void isSshUrl_sshProtocol_true() {
        properties.setRepositoryUrl("ssh://git@github.com/org/repo.git");

        assertThat(provider.isSshUrl()).isTrue();
    }

    @Test
    @DisplayName("isSshUrl returns false for HTTPS URL")
    void isSshUrl_https_false() {
        properties.setRepositoryUrl("https://github.com/org/repo.git");

        assertThat(provider.isSshUrl()).isFalse();
    }

    @Test
    @DisplayName("isSshUrl returns false for HTTP URL")
    void isSshUrl_http_false() {
        properties.setRepositoryUrl("http://github.com/org/repo.git");

        assertThat(provider.isSshUrl()).isFalse();
    }
}

