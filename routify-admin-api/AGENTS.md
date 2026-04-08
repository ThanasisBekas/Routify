# routify-admin-api — Agent Guide

BFF (Backend-for-Frontend) — the **sole** HTTP API for the dashboard. No other service exposes management REST endpoints.

## Runtime

- **Port:** 8082 (actuator: 9082)
- **Runtime:** Spring Web (blocking) with Virtual Threads
- **No database** — stateless aggregator; all data lives in downstream services

## Architecture Role

All dashboard interactions flow through this service:
- **Writes:** REST endpoint → build `CommandEvent` record → publish to Kafka topic → return HTTP 202 with `AsyncAcknowledgement`
- **Reads:** REST endpoint → RabbitMQ RPC via `AmqpServiceClientSupport.rpc()` → return data
- **Real-time:** Kafka consumer listens to domain event topics → broadcasts via SSE (`DashboardEventBroadcaster`) + WebSocket/STOMP (`WebSocketEventBroadcaster`)

## Package Layout

```
io.routify.admin/
├── client/       # Messaging clients (one per downstream service)
│   ├── RouteFilterMessagingClient   → Kafka commands to route-service
│   ├── RouteServiceClient           → RabbitMQ RPC to route-service + gateway
│   ├── IdentityMessagingClient      → Kafka + RabbitMQ to identity-service
│   ├── AuditMessagingClient         → RabbitMQ RPC to audit-service
│   ├── CertVaultMessagingClient     → Kafka + RabbitMQ to cert-vault
│   └── AiMessagingClient            → RabbitMQ RPC to ai-service
├── config/       # Security (JwtAuthFilter), Kafka, RabbitMQ, WebSocket, CORS, Cache
├── controller/   # REST controllers (one per feature domain)
├── dto/          # Request/response DTOs for the REST API
├── gateway/      # Gateway config controller/service/DTOs (sub-package)
├── graphql/      # GraphQL analytics API (AnalyticsGraphQLController)
├── service/      # DashboardStatsService, ExportService, ImportService, CanaryMonitorService
├── sse/          # DashboardEventBroadcaster (SSE endpoint)
└── ws/           # WebSocketController, WebSocketEventBroadcaster (STOMP)
```

## Key Patterns

### Messaging client pattern
Every downstream service has a dedicated client class in `client/`:
- Kafka clients extend `KafkaServiceClientSupport` → `publishCommand(topic, commandEvent)`
- RabbitMQ clients extend `AmqpServiceClientSupport` → `rpc(routingKey, request, TypeReference)`
- Each RPC method is annotated `@CircuitBreaker(name = "<service>")` with a fallback method
- Example: `RouteServiceClient.getRouteStats()` → `rpc(RK_ROUTE_STATS, request, class)`

### Controller pattern
- Write endpoints: build a `CommandEvent.*` record → call `messagingClient.publishCommand()` → return `AsyncAcknowledgement` (HTTP 202)
- Read endpoints: call `messagingClient.rpc()` → map result → return DTO
- Auth endpoints proxy to identity-service via RabbitMQ (login, refresh, logout)

### Real-time events
- **SSE:** `GET /api/v1/admin/events` → `DashboardEventBroadcaster` emits `SseEmitter` events
- **WebSocket:** `/ws/websocket` (raw STOMP) or `/ws` (SockJS) → `WebSocketEventBroadcaster` sends to `/topic/events`, `/topic/metrics`, `/topic/audit`
- Both are fed by Kafka consumer classes in `config/KafkaConsumerConfig.java`

## Adding a new feature endpoint

1. **Write (async):** Add `CommandEvent` record in `routify-common` → Create/extend messaging client method with `publishCommand()` → Add controller endpoint returning `AsyncAcknowledgement`
2. **Read (sync):** Add `QueryRequest`/`QueryResponse` records in `routify-common` → Add `RabbitTopology` constants → Extend messaging client with `rpc()` call → Add controller endpoint
3. Annotate RPC calls with `@CircuitBreaker` and provide a fallback method

## Build

```bash
mvn clean package -pl routify-admin-api -am -DskipTests
```

