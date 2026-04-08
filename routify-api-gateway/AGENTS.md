# routify-api-gateway — Agent Guide

Reactive API Gateway built on **Spring Cloud Gateway (WebFlux/Netty)**. Routes and filters are hot-reloaded from Kafka events with zero downtime.

## Runtime

- **Port:** 8080 (actuator: 9080)
- **Runtime:** Reactive (WebFlux) — **NEVER use blocking code** (no `Thread.sleep`, blocking I/O, JPA, `CompletableFuture.get()`)
- Offload any blocking work to `Schedulers.boundedElastic()`

## Architecture Role

- Loads all active routes from `routify-route-service` at startup via RabbitMQ snapshot
- Hot-reloads individual routes via `ROUTE_EVENTS` Kafka topic (no restart)
- Executes a per-route filter pipeline — each `FilterType` maps to a `*GatewayFilterFactory`
- Publishes `REQUEST_TELEMETRY` events to Kafka on every proxied request
- Maintains an in-memory `CertificateRegistry` for mTLS — fed by cert-vault at startup + Kafka events

## Package Layout

```
io.routify.gateway/
├── auth/           # JWT validation (reactive SecurityWebFilterChain)
├── certificate/    # CertificateRegistry, CertificateVaultLoader, PemCertificateParser, CertEventKafkaConsumer
├── client/         # RabbitMQ clients for route snapshot + cert material fetch
├── cluster/        # Multi-instance coordination
├── config/         # GatewaySecurityConfig, GatewayKafkaConfig, GatewayRabbitConfig,
│                   # DynamicCorsConfigurationSource, GatewayConfigLoader, Resilience4JConfig
├── downstream/     # Downstream auth (basic auth, OAuth2 client credentials)
├── fallback/       # Circuit breaker fallback handlers
├── filter/         # 28+ GatewayFilterFactory implementations (one per FilterType)
├── messaging/      # GatewayStatusRabbitHandler (serves status RPC)
├── net/            # Low-level network utilities
├── ratelimit/      # Rate limiting (Redis-backed fixed + sliding window)
├── routing/        # DynamicRouteDefinitionLocator, DynamicRouteRefreshListener,
│                   # RouteDefinitionBuilder, GatewayConfigRefResolver
├── ssl/            # Dynamic SSL context management
└── telemetry/      # GatewayTelemetryPublisher (publishes REQUEST_TELEMETRY to Kafka)
```

## Key Patterns

### Filter factory pattern
Each `FilterType` enum value maps 1:1 to a `*GatewayFilterFactory` class in `filter/`:
- Factory receives filter config `Map<String, Object>` from the route definition
- Must return a reactive `GatewayFilter` — no blocking calls
- AI filters (`AiGatewayFilterFactory`, `AiModifierGatewayFilterFactory`) call ai-service via RabbitMQ RPC — `sendAndReceive` is offloaded to `Schedulers.boundedElastic()`

### Route hot-reload flow
```
Kafka ROUTE_EVENTS → DynamicRouteRefreshListener → DynamicRouteDefinitionLocator
                                                   (updates in-memory ConcurrentHashMap)
```
`RouteDefinitionBuilder` converts `RouteSnapshotDto` → Spring Cloud `RouteDefinition` with all filter factories wired.

### Certificate hot-reload
```
Kafka CERT_EVENTS / CERT_GROUP_EVENTS → CertEventKafkaConsumer → CertificateRegistry
                                                                  (versioned, thread-safe)
```
At startup: `CertificateVaultLoader` fetches decrypted PEM from cert-vault via `RK_CERTS_FETCH_MATERIAL`.

### Gateway config reload
```
Kafka GATEWAY_CONFIG_EVENTS → GatewayConfigLoader → DynamicCorsConfigurationSource,
                                                      GlobalSecurityHeadersFilter,
                                                      rate-limit policies, etc.
```

## Adding a new filter type

1. Add enum value to `FilterType` in `routify-common`
2. Create `<Name>GatewayFilterFactory.java` in `filter/` — implement `GatewayFilterFactory` or extend appropriate base
3. Register in `RouteDefinitionBuilder.resolveFilterFactory()` switch expression
4. The filter config `Map<String, Object>` comes from the filter definition stored in route-service

## Critical constraints

- **No JPA/Hibernate** — this module has no database dependency
- **No `@Transactional`** — everything is reactive
- **Kafka consumers use `MANUAL_IMMEDIATE` ack mode** — acknowledged per-record after processing
- All RPC calls to other services (ai-service, cert-vault) must use `Schedulers.boundedElastic()` offloading

## Build

```bash
mvn clean package -pl routify-api-gateway -am -DskipTests
```

