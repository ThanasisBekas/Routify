package gr.routify.gateway.auth.util;

import gr.routify.gateway.auth.properties.AuthProperties;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;

import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.Objects.nonNull;

/**
 * Static utilities for extracting Bearer tokens, mapping claims to headers,
 * and building Basic auth headers.
 */
@Slf4j
@UtilityClass
public class AuthenticationUtils {

    /**
     * Extracts the Bearer token from the Authorization header.
     * Returns {@code null} if absent or malformed.
     *
     * @param request the inbound HTTP request
     * @return the token string, or {@code null}
     */
    public static String extractBearerToken(ServerHttpRequest request) {
        try {
            String authorizationHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
                return null;
            }
            String token = authorizationHeader.substring("Bearer ".length()).strip();
            return token.isEmpty() ? null : token;
        } catch (Exception exception) {
            log.info("Unable to extract Bearer token from Authorization header of request with path {}",
                    request.getPath());
            return null;
        }
    }

    /**
     * Maps token claims to request headers according to the provided config.
     * Missing claim values are ignored.
     *
     * @param mappingConfig claim-name to header-name mappings
     * @param claims        claims from the OAuth2 verification response
     * @return headers to set on the downstream request
     */
    public static Map<String, String> mapClaimsToHeaders(Map<String, String> mappingConfig,
                                                          Map<String, Object> claims) {
        return mappingConfig.entrySet()
                .stream()
                .filter(entry -> nonNull(claims.get(entry.getKey())))
                .collect(Collectors.toMap(
                        Map.Entry::getValue,
                        entry -> String.valueOf(claims.get(entry.getKey()))));
    }

    /**
     * Builds a Base64-encoded Basic Authorization header from the OAuth2 client ID and secret.
     *
     * @param oauth2Config the OAuth2 configuration containing client ID and secret
     * @return the Basic authorization header value
     */
    public static String buildBasicAuthorizationHeader(AuthProperties.Oauth2Config oauth2Config) {
        if (isBlankOrNull(oauth2Config.getClientId()) || isBlankOrNull(oauth2Config.getClientSecret())) {
            throw new IllegalArgumentException(
                    "Client id and Secret must be set in order to generate basic authorization header");
        }
        String valueToEncode = oauth2Config.getClientId() + ":" + oauth2Config.getClientSecret();
        return "Basic " + Base64.getEncoder().encodeToString(valueToEncode.getBytes());
    }

    private static boolean isBlankOrNull(String s) {
        return s == null || s.isBlank();
    }
}

