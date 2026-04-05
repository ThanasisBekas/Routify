package io.routify.gateway.ssl;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Base SSL/TLS configuration shared by all OAuth2 provider config classes.
 * {@code uri} is required — it is unconditionally passed to {@code URI.create()} on every WebClient call.
 */
@Data
public class SSLContextProperties {

    @NotBlank(message = "Provider URI must not be blank")
    private String uri;
    private String certificatePath;
    private String certificateType;
    private String certificatePassword;
    private boolean skipHostnameVerification;

    public SSLContextProperties() {}

    public boolean isSSLConfigured() {
        return certificatePath != null && !certificatePath.isEmpty();
    }
}

