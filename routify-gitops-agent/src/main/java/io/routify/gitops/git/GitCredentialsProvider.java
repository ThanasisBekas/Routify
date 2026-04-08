package io.routify.gitops.git;

import io.routify.gitops.config.GitOpsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.ssh.jsch.JschConfigSessionFactory;
import org.eclipse.jgit.transport.ssh.jsch.OpenSshConfig;
import org.eclipse.jgit.util.FS;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import org.springframework.stereotype.Component;

/**
 * Provides Git credentials (HTTPS username/password or SSH key) based on
 * the configured {@link GitOpsProperties}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GitCredentialsProvider {

    private final GitOpsProperties properties;

    /**
     * Returns a JGit {@link CredentialsProvider} for HTTPS authentication,
     * or {@code null} if no HTTPS credentials are configured.
     */
    public CredentialsProvider getHttpsCredentials() {
        if (StringUtils.isNotBlank(properties.getHttpsUsername())
                && StringUtils.isNotBlank(properties.getHttpsPassword())) {
            log.debug("Using HTTPS credentials for Git authentication");
            return new UsernamePasswordCredentialsProvider(
                    properties.getHttpsUsername(),
                    properties.getHttpsPassword()
            );
        }
        return null;
    }

    /**
     * Configures the SSH session factory for SSH key authentication.
     * Must be called before performing Git operations over SSH.
     */
    public void configureSshAuth() {
        if (StringUtils.isBlank(properties.getSshKeyPath())) {
            return;
        }

        log.debug("Configuring SSH key auth from: {}", properties.getSshKeyPath());

        SshSessionFactory.setInstance(new JschConfigSessionFactory() {
            @Override
            protected void configure(OpenSshConfig.Host host, com.jcraft.jsch.Session session) {
                session.setConfig("StrictHostKeyChecking", "no");
            }

            @Override
            protected JSch createDefaultJSch(FS fs) throws JSchException {
                JSch jsch = super.createDefaultJSch(fs);
                jsch.addIdentity(properties.getSshKeyPath());
                return jsch;
            }
        });
    }

    /**
     * Returns {@code true} if the repository URL uses SSH protocol.
     */
    public boolean isSshUrl() {
        String url = properties.getRepositoryUrl();
        return url.startsWith("git@") || url.startsWith("ssh://");
    }
}

