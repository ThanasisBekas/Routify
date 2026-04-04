# routify-api-gateway

The reactive API Gateway for the Routify platform, built on **Spring Cloud Gateway** (WebFlux / Project Reactor). Routes and filters are hot-reloaded from Kafka events with zero downtime.

## Responsibilities

- **Dynamic routing** — routes loaded from `routify-route-service` at startup via RabbitMQ snapshot; updated in-memory via Kafka events with no restart
- **Filter chain** — executes a per-route filter pipeline; each filter type maps to a `*GatewayFilterFactory` implementation
- **AI evaluation** — routes with `AI_FILTER` or `AI_MODIFIER` filters call `routify-ai-service` via RabbitMQ RPC; blocking `sendAndReceive` is offloaded to `Schedulers.boundedElastic()`
- **Certificate registry** — `CertificateRegistry` holds in-memory PEM material fetched from `routify-cert-vault` on startup and kept current via `CERT_EVENTS` / `CERT_GROUP_EVENTS` Kafka topics
- **Request telemetry** — publishes `REQUEST_TELEMETRY` events to Kafka on every proxied request
- **Gateway config** — consumes `GATEWAY_CONFIG_EVENTS` to reload CORS / security-headers / rate-limit config across all instances

> **Reactive runtime** — never use blocking code inside this module. Kafka listener threads use `MANUAL_IMMEDIATE` ack-mode. All routes are managed exclusively through the Routify dashboard.

## Module Info

| Property | Value |
|---|---|
| Artifact | `gr.routify:routify-api-gateway` |
| Version | `1.0.2-SNAPSHOT` |
| Default port | `8080` |
| Actuator port | `9080` |
| Java | 21 |
| Runtime model | Reactive (WebFlux / Netty) |

## Key Dependencies

| Dependency | Purpose |
|---|---|
| `spring-cloud-starter-gateway` | Core reactive gateway |
| `spring-cloud-starter-loadbalancer` | `lb://` service discovery routing |
| `spring-cloud-starter-circuitbreaker-reactor-resilience4j` | Circuit breaker filter |
| `spring-boot-starter-data-redis-reactive` | Rate limiting + route cache |
| `spring-boot-starter-security` (reactive) | Security filter chain |
| `jjwt-*` | RS256 JWT validation |
| `spring-kafka` | Route change event subscription |
| `spring-boot-starter-amqp` | Route snapshot + AI RPC |
| `bcprov-jdk18on` / `bcpkix-jdk18on` | BouncyCastle — PEM parsing for mTLS |
| `routify-common` | Shared `KafkaTopics`, `RabbitTopology`, `FilterType` |

## Filter Factories

Each `FilterType` enum value maps to a concrete `*GatewayFilterFactory`. Active implementations:

| FilterType | Factory class |
|---|---|
| `AUTH_API_KEY` | `ApiKeyAuthGatewayFilterFactory` |
| `AUTH_BASIC` | `BasicAuthGatewayFilterFactory` |
| `AUTH_JWT` | `JwtAuthGatewayFilterFactory` |
| `AUTH_MTLS` | `MtlsAuthGatewayFilterFactory` |
| `AUTH_OAUTH2` | `OAuth2TokenIntrospectGatewayFilterFactory` |
| `AUTH_CLIENT_ID` | `ClientIdAuthGatewayFilterFactory` |
| `AUTH_CERT_VAULT` | `CertVaultAuthGatewayFilterFactory` |
| `DOWNSTREAM_BASIC_AUTH` | `DownstreamBasicAuthGatewayFilterFactory` |
| `DOWNSTREAM_BEARER_CC` | `DownstreamOAuth2BearerGatewayFilterFactory` |
| `RATE_LIMIT_FIXED_WINDOW` | `FixedWindowRateLimitGatewayFilterFactory` |
| `RATE_LIMIT_SLIDING_WINDOW` | `SlidingWindowRateLimitGatewayFilterFactory` |
| `REQUEST_HEADER_MODIFY` | `RequestHeaderModifyGatewayFilterFactory` |
| `RESPONSE_HEADER_MODIFY` | `ResponseHeaderModifyGatewayFilterFactory` |
| `BODY_JOLT_TRANSFORM` | `JoltTransformGatewayFilterFactory` |
| `VALIDATE_JSON_SCHEMA` | `JsonSchemaValidateGatewayFilterFactory` |
| `TIMEOUT` | `RequestTimeoutGatewayFilterFactory` |
| `CONDITIONAL_ROUTE` | `ConditionalRouteGatewayFilterFactory` |
| `USER_ID_PAYLOAD_ROUTING` | `UserIdPayloadRoutingGatewayFilterFactory` |
| `CERT_ROTATION` | `CertRotationGatewayFilterFactory` |
| `CERT_VAULT_EXPIRY_CHECK` | `CertVaultExpiryCheckGatewayFilterFactory` |
| `API_VERSIONING` | `ApiVersioningGatewayFilterFactory` |
| `CORRELATION_ID` | `CorrelationIdGatewayFilterFactory` |
| `REQUEST_LOGGER` | `RequestLoggerGatewayFilterFactory` |
| `TENANT_CONTEXT` | `TenantContextGatewayFilterFactory` |
| `SECURITY_HEADERS` | `SecurityHeadersGatewayFilterFactory` |
| `CUSTOM_METRIC` | `CustomMetricGatewayFilterFactory` |
| `CUSTOM_SPEL` | `SpelCustomGatewayFilterFactory` |
| `AI_FILTER` | `AiGatewayFilterFactory` |
| `AI_MODIFIER` | `AiModifierGatewayFilterFactory` |

## Messaging

### Kafka — consumed

| Topic | Listener | Purpose |
|---|---|---|
| `routify.route.events` | `DynamicRouteRefreshListener` | Hot-reload individual routes |
| `routify.gateway.reload` | `DynamicRouteRefreshListener` | Force full reload (e.g. after cert rotation) |
| `routify.gateway.config` | `GatewayConfigLoader` | Reload CORS / security-headers / rate-limit config |
| `routify.cert.events` | `CertEventKafkaConsumer` | Update `CertificateRegistry` |
| `routify.cert.group.events` | `CertEventKafkaConsumer` | Update `CertificateRegistry` for cert groups |

### Kafka — published

| Topic | Publisher | Purpose |
|---|---|---|
| `routify.request.telemetry` | `GatewayTelemetryPublisher` | Per-request metrics for `routify-audit-service` |

### RabbitMQ — initiated (on startup)

| Exchange | Queue | Routing Key | Purpose |
|---|---|---|---|
| `routify.route-service` | `routify.route-service.gateway-snapshot` | `route.gateway.snapshot` | Fetch active route snapshot at startup |

### RabbitMQ — served (status + cert registry)

Exchange: `routify.gateway` (direct)

| Queue | Routing Key | Requester |
|---|---|---|
| `routify.gateway.status` | `gateway.status.request` | `routify-admin-api` |
| `routify.gateway.cert-registry` | `gateway.cert.registry` | `routify-admin-api` |

### RabbitMQ — initiated (AI evaluation, per request)

| Exchange | Queue | Routing Key | Timeout |
|---|---|---|---|
| `routify.ai-service` | `routify.ai-service.filter.evaluate` | `ai.filter.evaluate` | 3 500 ms |
| `routify.ai-service` | `routify.ai-service.modifier.evaluate` | `ai.modifier.evaluate` | 5 000 ms |

## Hot-Reload Flow

```
dashboard → routify-admin-api → Kafka ROUTE_COMMANDS → routify-route-service
                                                               │
                                                    Outbox → Kafka ROUTE_EVENTS
                                                               │
                                               routify-api-gateway (DynamicRouteRefreshListener)
                                                               │
                                               In-memory RouteLocator updated (zero downtime)
```

## Building & Running

```bash
# Build
mvn clean package -pl routify-api-gateway -am -DskipTests

# Run
java -jar target/routify-api-gateway-1.0.2-SNAPSHOT.jar
```

### Required Infrastructure

- Redis (`localhost:6379`)
- Kafka (`localhost:9092`)
- RabbitMQ (`localhost:5672`)

> Start all infrastructure with `docker compose --env-file .env up -d` from the project root.

## Docker

```bash
docker build -t routify-api-gateway .
docker run -p 8080:8080 routify-api-gateway
```
