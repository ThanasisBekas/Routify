package gr.routify.gateway.fallback;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

/**
 * Fallback controller for circuit breaker responses.
 * Returns RFC 9457 Problem Detail JSON responses.
 */
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    @GetMapping(value = "/503", produces = MediaType.APPLICATION_PROBLEM_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> serviceUnavailable() {
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(Map.of(
                        "type", "about:blank",
                        "title", "Service Unavailable",
                        "status", 503,
                        "detail", "The upstream service is currently unavailable. Please try again.",
                        "timestamp", Instant.now().toString()
                )));
    }

    @GetMapping(value = "/gateway", produces = MediaType.APPLICATION_PROBLEM_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> gatewayFallback() {
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(Map.of(
                        "type", "about:blank",
                        "title", "Bad Gateway",
                        "status", 502,
                        "detail", "The gateway encountered an error processing your request.",
                        "timestamp", Instant.now().toString()
                )));
    }

    @GetMapping(value = "/timeout", produces = MediaType.APPLICATION_PROBLEM_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> timeoutFallback() {
        return Mono.just(ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(Map.of(
                        "type", "about:blank",
                        "title", "Gateway Timeout",
                        "status", 504,
                        "detail", "The request timed out. Please try again.",
                        "timestamp", Instant.now().toString()
                )));
    }
}

