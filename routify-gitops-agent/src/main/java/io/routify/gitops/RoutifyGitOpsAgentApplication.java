package io.routify.gitops;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Routify GitOps Agent — autonomous reconciliation agent that watches a Git
 * repository for changes to gateway configuration (routify-export.yaml) and
 * applies them via the admin-api import endpoint.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Polls a Git repository on a configurable interval (default: 60s)</li>
 *   <li>Computes SHA-256 hash of the config file and compares with last-applied hash</li>
 *   <li>Calls admin-api import/preview to validate changes</li>
 *   <li>Applies valid changes via admin-api import endpoint</li>
 *   <li>Reports results via webhook notifications</li>
 *   <li>Supports drift-detection-only mode (dry-run)</li>
 *   <li>Accepts GitHub/GitLab webhook push events for immediate reconciliation</li>
 * </ul>
 *
 * <h2>Port allocations</h2>
 * <ul>
 *   <li>8087 — application HTTP (webhook trigger endpoint)</li>
 *   <li>9087 — management / actuator (Prometheus scrape)</li>
 * </ul>
 */
@SpringBootApplication
@EnableScheduling
public class RoutifyGitOpsAgentApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(RoutifyGitOpsAgentApplication.class)
                .run(args);
    }
}

