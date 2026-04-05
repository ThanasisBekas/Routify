package io.routify.gateway.net;

import io.routify.gateway.auth.properties.AuthProperties;
import io.routify.gateway.downstream.oauth2.CaffeineOauth2TokenCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Evicts all cached {@link WebClient} instances and OAuth2 tokens on a Spring Cloud Config
 * refresh, ensuring the next request rebuilds clients and fetches tokens with the updated
 * {@link AuthProperties}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebClientRegistryRefreshListener {

    private final WebClientRegistry webClientRegistry;
    private final CaffeineOauth2TokenCache tokenCache;

    /** Evicts all WebClients then flushes the token cache on config refresh. */
    @EventListener(RefreshScopeRefreshedEvent.class)
    public void onConfigRefresh(RefreshScopeRefreshedEvent event) {
        log.info("Config refresh detected ('{}') — evicting WebClient registry and OAuth2 token cache",
                event.getName());
        webClientRegistry.evictAll();
        tokenCache.invalidateAll();
        log.info("WebClient registry and OAuth2 token cache evicted — rebuilding with updated config");
    }
}

