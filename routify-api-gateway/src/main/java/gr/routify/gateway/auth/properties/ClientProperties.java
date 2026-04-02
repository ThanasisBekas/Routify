package gr.routify.gateway.auth.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Binds {@code client.*} — holds the {@code organizationId → clientId} mapping
 * used to resolve downstream {@code organization-id} headers.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "client")
public class ClientProperties {

    private Map<String, String> clientIdMapping = new HashMap<>();
}

