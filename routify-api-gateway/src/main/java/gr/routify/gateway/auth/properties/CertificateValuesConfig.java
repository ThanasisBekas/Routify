package gr.routify.gateway.auth.properties;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Filter config holding a list of {@code clientId → certificate} mappings
 * used by the mTLS auth filter.
 */
@Getter
@Setter
@Validated
@NoArgsConstructor
@AllArgsConstructor
public class CertificateValuesConfig {

    private List<CertificateClientMapping> values;

    /**
     * A single entry mapping a client-ID header value to its expected certificate logical ID.
     */
    @Getter
    @Setter
    @Validated
    public static class CertificateClientMapping {

        @NotEmpty
        private String clientIdRequestHeader;
        @NotEmpty
        private String clientIdValue;

        @NotEmpty
        private String clientCertificateRequestHeader;
        @NotEmpty
        private String clientCertificateValue;
    }
}

