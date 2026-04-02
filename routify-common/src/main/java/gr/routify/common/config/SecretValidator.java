package gr.routify.common.config;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Validates that required secrets are present <em>before</em> the Spring application
 * context finishes loading.  Registered as an {@link ApplicationListener} on the
 * {@link org.springframework.boot.SpringApplicationBuilder} in each service's main class.
 *
 * <h2>Usage</h2>
 * <p>Each service lists its required secret property keys as a comma-separated value
 * under {@code routify.required-secrets} in its {@code application.yml}:
 *
 * <pre>{@code
 * routify:
 *   required-secrets: >-
 *     spring.datasource.password,
 *     spring.rabbitmq.password
 * }</pre>
 *
 * <p>If any of those properties resolves to {@code null} or blank the application
 * terminates immediately with a clear message listing every missing secret, rather
 * than starting with a broken configuration and failing later at runtime with an
 * obscure error.
 *
 * <h2>Local development</h2>
 * <p>Set the values in a {@code .env} file and pass it to Docker Compose or export
 * them as shell environment variables before running the service locally.  The
 * provided {@code .env.example} at the project root documents every required key.
 *
 * <h2>Production</h2>
 * <p>Supply secrets via Kubernetes {@code Secret} objects (reference them as
 * environment variables in the {@code Deployment} spec) or via HashiCorp Vault.
 * Never commit real secret values to version control.
 */
public class SecretValidator implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    /**
     * Property key whose value is a comma-separated list of property keys that
     * must be non-blank at startup. Each service sets its own list.
     */
    private static final String REQUIRED_SECRETS_PROP = "routify.required-secrets";

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        Environment env = event.getEnvironment();

        String raw = env.getProperty(REQUIRED_SECRETS_PROP, "");
        if (raw.isBlank()) {
            return; // No secrets declared for this service — nothing to validate.
        }

        List<String> requiredKeys = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(key -> !key.isBlank())
                .toList();

        List<String> missingKeys = requiredKeys.stream()
                .filter(key -> {
                    String value = env.getProperty(key);
                    return value == null || value.isBlank();
                })
                .collect(Collectors.toList());

        if (!missingKeys.isEmpty()) {
            String serviceName = env.getProperty("spring.application.name", "routify-service");
            String missing = missingKeys.stream()
                    .map(k -> "  - " + k)
                    .collect(Collectors.joining("\n"));

            throw new IllegalStateException("""

                ╔══════════════════════════════════════════════════════════════════╗
                ║              ROUTIFY STARTUP FAILURE — MISSING SECRETS          ║
                ╚══════════════════════════════════════════════════════════════════╝

                Service  : %s
                Problem  : The following required secrets are not configured.
                           They must be provided as environment variables before
                           this service can start.

                Missing secrets:
                %s

                Resolution:
                  • For local development: copy .env.example → .env and fill in values,
                    then run: docker compose --env-file .env up
                  • For production: supply values via Kubernetes Secrets or Vault.
                  • Reference: see TECHNICAL_ANALYSIS.md §2.4 for full details.

                """.formatted(serviceName, missing));
        }
    }
}

