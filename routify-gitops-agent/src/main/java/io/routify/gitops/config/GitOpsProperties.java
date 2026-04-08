package io.routify.gitops.config;

import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for the GitOps reconciliation agent.
 *
 * <p>Bound from {@code routify.gitops.*} in application.yml.
 */
@Component
@ConfigurationProperties(prefix = "routify.gitops")
@Validated
@Getter
@Setter
public class GitOpsProperties {

    /** Whether the GitOps agent is enabled. */
    private boolean enabled;

    /** Git repository URL (HTTPS or SSH). */
    private String repositoryUrl;

    /** Git branch to track. */
    private String branch = "main";

    /** Path to the config YAML file within the repository. */
    private String configPath = "routify-export.yaml";

    /** Polling interval in seconds. */
    @Positive
    private int pollIntervalSeconds = 60;

    /** Path to SSH private key (optional, for SSH auth). */
    private String sshKeyPath = "";

    /** HTTPS username (optional, for HTTPS auth). */
    private String httpsUsername = "";

    /** HTTPS password (optional, for HTTPS auth). */
    private String httpsPassword = "";

    /** Admin API base URL. */
    private String adminApiUrl = "http://localhost:8082";

    /** API key for authenticating to admin-api. */
    private String apiKey;

    /** Tenant ID scope for import operations. */
    private String tenantId;

    /** Dry-run mode — preview only, no apply. Fires DRIFT_DETECTED webhook. */
    private boolean dryRun = false;

    /** Webhook URL for reporting reconciliation results (optional). */
    private String webhookUrl = "";

    /** Webhook HMAC secret for signing webhook payloads (optional). */
    private String webhookSecret = "";
}

