# routify-api-gateway

The reactive API Gateway for the Routify platform, built on **Spring Cloud Gateway** (WebFlux / Project Reactor). Provides zero-downtime dynamic routing — routes and filters are hot-reloaded from Kafka events with no service restart required.

## Responsibilities

- **Dynamic routing** — routes loaded from `routify-route-service` at startup via RabbitMQ snapshot; updated in-memory via Kafka events
- **Zero-downtime hot-reload** — any route create/update/activate/deactivate/delete event is applied instantly without restarting the gateway
- **JWT authentication** — validates RS256 JWTs in `JwtAuthGatewayFilterFactory`; token validation performed in-memory
- **Rate limiting** — Redis-backed `RedisRateLimiter` per route
- **Circuit breaker** — Resilience4j circuit-breaker filter for upstream failure isolation
- **mTLS termination** — inbound mutual TLS using PEM/PKCS12 certificates managed by `routify-cert-vault`
- **Load balancing** — `lb://` scheme via Spring Cloud LoadBalancer
- **Route caching** — Redis-backed active-route cache for fast restarts
- **Metrics & actuator** — Micrometer metrics exposed via Spring Boot Actuator

## Module Info

| Property | Value |
|---|---|
| Artifact | `gr.routify:routify-api-gateway` |
| Version | `2.0.0-SNAPSHOT` |
| Default port | `8080` |
| Java | 21 |
| Runtime model | Reactive (WebFlux / Netty) |

## Key Dependencies

| Dependency | Purpose |
|---|---|
| `spring-cloud-starter-gateway` | Core reactive gateway |
| `spring-cloud-starter-loadbalancer` | `lb://` service discovery routing |
| `spring-cloud-starter-circuitbreaker-reactor-resilience4j` | Circuit breaker filter |
| `spring-cloud-starter-config` | `@RefreshScope` config refresh |
| `spring-boot-starter-data-redis-reactive` | Rate limiting + route cache |
| `spring-boot-starter-security` (reactive) | Security filter chain |
| `jjwt-*` | RS256 JWT validation |
| `spring-kafka` | Route change event subscription |
| `spring-boot-starter-amqp` | Route snapshot + status request/reply |
| `bcprov-jdk18on` / `bcpkix-jdk18on` | BouncyCastle — PEM parsing for mTLS |
| `caffeine` | In-memory OAuth2 token caching with TTL |
| `spring-boot-starter-actuator` | Health, metrics endpoints |

## Messaging

### Kafka — Topics (consumed)

| Topic | Description |
|---|---|
| `routify.route.events` | `RouteCreated`, `RouteUpdated`, `RouteActivated`, `RouteDeactivated`, `RouteDeleted` — triggers hot-reload |

### RabbitMQ — Request/Reply (initiated)

| Queue | Description |
|---|---|
| Route snapshot queue | Fetches full active-route list from `routify-route-service` at startup |
| Gateway status queue | Reports gateway health/status back to `routify-admin-api` |

## Hot-Reload Flow

```
routify-admin-api  →  Kafka command  →  routify-route-service
                                               │
                                        Kafka event published
                                               │
                                    routify-api-gateway (listener)
                                               │
                                    In-memory RouteLocator updated
                                    (zero downtime, no restart)
```

## Building & Running

```bash
# Build
mvn clean package -pl routify-api-gateway -am -DskipTests

# Run
java -jar target/routify-api-gateway-2.0.0-SNAPSHOT.jar
```

### Required Infrastructure

- Redis (`localhost:6379`)
- Kafka (`localhost:9092`)
- RabbitMQ (`localhost:5672`)

> Start all infrastructure with `docker compose up -d` from the project root.

## Docker

```bash
docker build -t routify-api-gateway .
docker run -p 8080:8080 routify-api-gateway
```

