package io.routify.cert.controller;

import io.routify.cert.service.AcmeChallengeStore;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP-01 ACME challenge endpoint.
 *
 * <p>Serves challenge tokens for ACME domain validation. This endpoint must be
 * reachable from the internet on port 80/443 for Let's Encrypt validation.
 * In Docker environments, map cert-vault port 8085 to the challenge path via
 * the reverse proxy or gateway.
 *
 * <p>This endpoint is excluded from JWT authentication in
 * {@link io.routify.cert.config.CertVaultSecurityConfig}.
 */
@RestController
@RequiredArgsConstructor
public class AcmeChallengeController {

    private final AcmeChallengeStore store;

    @GetMapping("/.well-known/acme-challenge/{token}")
    public ResponseEntity<String> serveChallenge(@PathVariable String token) {
        return store.get(token)
                .map(content -> ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(content))
                .orElse(ResponseEntity.notFound().build());
    }
}

