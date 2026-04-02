package gr.routify.gateway.auth.properties;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.cloud.gateway.filter.factory.AbstractNameValueGatewayFilterFactory;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Filter config holding a list of {@code name=value} pairs used by the client-ID auth filter.
 */
@Getter
@Setter
@Validated
@NoArgsConstructor
@AllArgsConstructor
public class NameValuesConfig {

    private List<AbstractNameValueGatewayFilterFactory.NameValueConfig> values;
}

