# Routify — AI Agent Guide

## Architecture Overview

Routify is a **zero-downtime API Gateway Platform** built as a Maven multi-module monorepo with one React frontend.

```
routify-dashboard  (React/Vite, port 5173)
       │
routify-admin-api  (BFF, port 8082) ← sole backend for the dashboard
       │ Kafka commands (writes) + RabbitMQ request/reply (reads)
       ├── routify-identity-service  (port 8083) — JWT issuance, users, tenants, API key lifecycle, webhook subscriptions
       ├── routify-route-service     (port 8081) — route/filter persistence + Outbox
       ├── routify-audit-service     (port 8084) — append-only audit log + request replay
       ├── routify-cert-vault        (port 8085) — AES-encrypted TLS certs + cert groups
       └── routify-ai-service        (port 8086) — LLM filter/modifier evaluation (OpenAI)
routify-api-gateway  (port 8080) — Spring Cloud Gateway, hot-reloads routes from Kafka
       │ RabbitMQ RPC (AI_FILTER / AI_MODIFIER evaluation)
       └── routify-ai-service  (called directly via RabbitMQ, not via admin-api)
routify-gitops-agent (port 8087) — autonomous Git→admin-api reconciliation agent (API key auth, Redis state)
routify-common       (shared library) — events, DTOs, exceptions, headers, topology constants
```

**Key data flow rule:** `routify-admin-api` is the *only* gateway to backend services. Services never call each other via HTTP. All writes go via **Kafka commands**; all reads go via **RabbitMQ request/reply** (10 s timeout, `RabbitTopology.REPLY_TIMEOUT_MS`).

**Hot-reload flow:** dashboard → admin-api → Kafka command topic → route-service → Kafka event topic → api-gateway (in-memory RouteLocator updated, zero restart).

## Canonical Source Files for Messaging

All inter-service messaging constants live in `routify-common`:
- **Kafka topics** → `event.io.routify.common.KafkaTopics` — never use string literals for topic names.
- **RabbitMQ exchanges/queues/routing-keys** → `event.io.routify.common.RabbitTopology` — each service owns one direct exchange.
- **HTTP headers** → `web.io.routify.common.RoutifyHeaders` — includes `X-Tenant-Id`, `X-Auth-User-Id`, `X-Correlation-Id`, `X-Auth-Tenant-Id`, `X-Auth-Role`, `X-Routify-Replay`, `X-Auth-Email`, `X-Auth-Type`, `X-Api-Key`, `X-Auth-Token`, `X-Route-Version`, etc.

### Key `KafkaTopics` constants (beyond the obvious command/event pairs)
- `FILTER_EVENTS` / `FILTER_COMMANDS` — filter lifecycle, separate from route topics.
- `TENANT_COMMANDS` — tenant create/suspend/reactivate, consumed by `routify-identity-service`.
- `GATEWAY_RELOAD` — forces a full gateway reload (e.g. certificate rotation).
- `GATEWAY_CONFIG_EVENTS` — persists gateway-wide config (CORS, security headers, rate-limit) to all gateway instances.
- `AUDIT_EVENTS`, `REQUEST_TELEMETRY` — consumed by `routify-audit-service`.
- `AI_FILTER_DECISIONS`, `AI_MODIFICATION_EVENTS` — published by `routify-ai-service` after every LLM evaluation.
- `AUTH_COMMANDS` — logout blacklisting, consumed by `routify-identity-service`.
- `APIKEY_COMMANDS` — API key create/revoke/rotate, consumed by `routify-identity-service`. Keys are persisted in PostgreSQL and projected to Redis for gateway reads.
- `WEBHOOK_COMMANDS` — webhook subscription create/update/delete, consumed by `routify-identity-service`. Subscriptions are persisted in PostgreSQL (`routify_identity` schema).
- `CERT_GROUP_EVENTS` — certificate group lifecycle, consumed by `routify-api-gateway`.
- All failed events are forwarded to DLQ topics named `<original-topic>.DLQ` (e.g. `routify.route.events.DLQ`), consumed exclusively by `routify-audit-service`. Named constants: `DLQ_ROUTE_EVENTS`, `DLQ_FILTER_EVENTS`, `DLQ_TENANT_EVENTS`, `DLQ_USER_EVENTS`, `DLQ_GATEWAY_RELOAD`, `DLQ_GATEWAY_CONFIG`, `DLQ_CERT_EVENTS`, `DLQ_CERT_GROUP_EVENTS`, `DLQ_REQUEST_TELEMETRY`, `DLQ_ROUTE_COMMANDS`, `DLQ_FILTER_COMMANDS`, `DLQ_USER_COMMANDS`, `DLQ_TENANT_COMMANDS`, `DLQ_AUTH_COMMANDS`, `DLQ_APIKEY_COMMANDS`, `DLQ_WEBHOOK_COMMANDS`, `DLQ_CERT_COMMANDS`, `DLQ_AI_FILTER_DECISIONS`, `DLQ_AI_MODIFICATION_EVENTS`.

### `routify-ai-service` communication
The API Gateway calls `routify-ai-service` via **RabbitMQ RPC** (not HTTP). Exchange: `RabbitTopology.EXCHANGE_AI_SERVICE` (`routify.ai-service`). Two queues:
- `QUEUE_AI_FILTER_EVALUATE` / `RK_AI_FILTER_EVALUATE` — filter verdict (ALLOW/BLOCK/FLAG).
- `QUEUE_AI_MODIFIER_EVALUATE` / `RK_AI_MODIFIER_EVALUATE` — request mutation (PII scrubbing, payload translation).
  Reply timeouts: `AI_FILTER_REPLY_TIMEOUT_MS` = 3 500 ms; `AI_MODIFIER_REPLY_TIMEOUT_MS` = 5 000 ms.

> Note: `routify-ai-service` has **no REST controllers**. The dashboard "Test Policy" dry-run feature hits `POST /api/v1/admin/ai-filter/test-policy` on `routify-admin-api`, which proxies the call to `routify-ai-service` via `AiMessagingClient` over RabbitMQ (same `EXCHANGE_AI_SERVICE`). `AI_MODIFIER` dry-runs go to `POST /api/v1/admin/ai-modifier/test-modification`. The gateway always uses RabbitMQ via `AiGatewayFilterFactory` / `AiModifierGatewayFilterFactory`; blocking `sendAndReceive` is offloaded to `Schedulers.boundedElastic()` to avoid blocking the Netty event loop. For air-gapped/local deployments, swap `spring-ai-starter-model-openai` → `spring-ai-starter-model-ollama` in `routify-ai-service/pom.xml` — no Java logic changes required (`ChatClient` is provider-agnostic).

### Additional `RabbitTopology` queues (beyond obvious CRUD queries)
- **Auth** (all on `EXCHANGE_IDENTITY_SERVICE`): `QUEUE_AUTH_LOGIN` / `QUEUE_AUTH_REFRESH` / `QUEUE_AUTH_CHANGE_PASSWORD` / `QUEUE_USERS_CHANGE_PASSWORD` — admin-api proxies all auth operations over RabbitMQ to identity-service. The dashboard hits `/api/v1/auth/*` on admin-api, never directly on identity-service.
- **API Keys** (on `EXCHANGE_IDENTITY_SERVICE`): `QUEUE_APIKEYS_QUERY` / `QUEUE_APIKEYS_GET` / `QUEUE_APIKEYS_CREATE` / `QUEUE_APIKEYS_REVOKE` / `QUEUE_APIKEYS_ROTATE` — full API key lifecycle. Create and rotate are sync RPC (raw key must be returned). Revoke is sync for immediate confirmation. Dashboard hits `/api/v1/admin/api-keys` on admin-api.
- **Webhooks** (on `EXCHANGE_IDENTITY_SERVICE`): `QUEUE_WEBHOOKS_QUERY` / `QUEUE_WEBHOOKS_GET` / `QUEUE_WEBHOOKS_DELIVERIES` / `QUEUE_WEBHOOKS_TEST` — webhook subscription management. Writes (create/update/delete) go via `WEBHOOK_COMMANDS` Kafka topic. Test ping is sync RPC. The `WebhookEventConsumer` in identity-service subscribes to `ROUTE_EVENTS`, `FILTER_EVENTS`, `CERT_EVENTS`, `AI_FILTER_DECISIONS` and dispatches matching webhook HTTP POSTs with HMAC-SHA256 signatures (`X-Routify-Signature`). Delivery is retried 3× with exponential backoff (30 s → 2 min → 15 min). Subscriptions with 10+ consecutive failures are auto-suspended. Delivery log is retained for 7 days (configurable via `routify.webhooks.delivery-retention-days`). Dashboard hits `/api/v1/admin/webhooks` on admin-api.
- **Roles** (on `EXCHANGE_IDENTITY_SERVICE`): `QUEUE_ROLES_QUERY` / `QUEUE_ROLES_GET` / `QUEUE_ROLES_COMMAND` — role definition management for granular RBAC. All operations are sync RPC. Built-in roles (SUPER_ADMIN, TENANT_ADMIN, OPERATOR, VIEWER) have well-known UUIDs and default permission sets seeded by Flyway. Custom roles are tenant-scoped. Dashboard hits `/api/v1/admin/roles` on admin-api. Feature flag: `routify.rbac.granular-enabled` (default `false`) controls whether JWT tokens include the `permissions` claim.
- **Route extras**: `QUEUE_ROUTES_CLONE` (`routes.clone`) — sync RPC for route cloning; `QUEUE_ROUTE_STATS` (`route.stats`); `QUEUE_GATEWAY_CONFIG_GET` / `QUEUE_GATEWAY_CONFIG_SAVE` — gateway-wide CORS/security/rate-limit config stored by route-service.
- **Workspace**: `QUEUE_TENANTS_LIST_ACTIVE` (`tenants.list-active`) — active workspace list for login dropdown; `QUEUE_TENANTS_COMMAND` (`tenants.command`) — sync suspend/reactivate tenant.
- **Gateway**: `QUEUE_GATEWAY_CERT_REGISTRY` (`gateway.cert.registry`) — gateway serves a snapshot of its live in-memory `CertificateRegistry` (fingerprint, expiry, source, status per logical cert ID).
- **Audit** (on `EXCHANGE_AUDIT_SERVICE`): `QUEUE_AUDIT_EVENTS_QUERY` / `QUEUE_AUDIT_REQUESTS_QUERY` / `QUEUE_AUDIT_REQUESTS_STATS` — event log, request log, and per-route request stats queries. Audit replay queues: `QUEUE_AUDIT_REPLAY_FAILED_QUERY`, `QUEUE_AUDIT_REPLAY_PENDING_QUERY`, `QUEUE_AUDIT_REPLAY_STATS`, `QUEUE_AUDIT_REPLAY_SINGLE`, `QUEUE_AUDIT_REPLAY_BULK` — served by audit-service, consumed by admin-api's `AdminReplayController` (`/api/v1/admin/audit/replay`). `QUEUE_AUDIT_ROUTE_HEALTH` (`audit.route.health`) — per-route health stats (latency percentiles, error rates, status code distribution) for the Gateway Health Dashboard v2.
- **AI audit** (on `EXCHANGE_AUDIT_SERVICE`): `QUEUE_AUDIT_AI_FILTER_STATS` (`audit.ai-filter.stats`) and `QUEUE_AUDIT_AI_FILTER_QUERY` (`audit.ai-filter.query`) — serve AI filter decision analytics to admin-api.
- **Tenant usage** (on `EXCHANGE_AUDIT_SERVICE`): `QUEUE_AUDIT_USAGE_CURRENT` (`audit.usage.current`) and `QUEUE_AUDIT_USAGE_HISTORY` (`audit.usage.history`) — serve current-period usage vs plan limits and daily usage history for the tenant usage analytics dashboard.
- **Route SLO** (on `EXCHANGE_ROUTE_SERVICE`): `QUEUE_ROUTE_SLO_GET` (`route-slo.get`) / `QUEUE_ROUTE_SLO_SAVE` (`route-slo.save`) — per-route SLO configuration (availability target, p99 latency target, evaluation window) for the Gateway Health Dashboard v2. Stored in `routify.route_slo` table.
- **Cert-vault** (on `EXCHANGE_CERT_VAULT`): `QUEUE_CERTS_QUERY` / `QUEUE_CERTS_GET` / `QUEUE_CERTS_ACTIVE_LIST` / `QUEUE_CERTS_STATS` / `QUEUE_CERTS_GATEWAY_SNAPSHOT` / `QUEUE_CERT_GROUPS_QUERY` / `QUEUE_CERT_GROUPS_GET` / `QUEUE_CERT_GROUPS_MEMBERS` — cert and cert-group queries. `QUEUE_CERTS_FETCH_MATERIAL` (`certs.fetch-material`) is **internal only** — gateway fetches decrypted PEM material for its in-memory `CertificateRegistry`; never call this from admin-api. **ACME** (also on `EXCHANGE_CERT_VAULT`): `QUEUE_ACME_REGISTER` / `QUEUE_ACME_ISSUE` / `QUEUE_ACME_ORDERS_QUERY` / `QUEUE_ACME_ORDER_GET` / `QUEUE_ACME_RENEW` — automated certificate lifecycle via ACME protocol (Let's Encrypt / ZeroSSL). All ACME operations are sync RPC (operator needs immediate feedback). Admin-api hits `/api/v1/admin/certs/acme/*`.
- **RabbitMQ message headers**: `RabbitTopology.HEADER_FROM_SERVICE` (`X-From-Service`), `HEADER_CONFIG_SECTION`, `HEADER_CHANGED_BY`, `HEADER_TENANT_ID`, `HEADER_USER_ID`, `HEADER_COMMAND` — set by `AmqpServiceClientSupport` automatically.

## Project Conventions

**Java services:**
- Java 25 with **Virtual Threads** enabled (`spring.threads.virtual.enabled: true`) on all services. The reactive gateway also sets this property, but it runs on Netty/Reactor so the setting only affects ancillary blocking tasks (Kafka consumers, RabbitMQ listeners) — **never use blocking code in gateway filter chains**.
- Spring Boot **4.0.5**, Spring Cloud **2025.1.1**, Spring AI **1.1.4**, JJWT **0.13.0**, Resilience4j **2.2.0**, MapStruct **1.6.3**.
- `routify-api-gateway` is **reactive** (WebFlux/Reactor/Netty) — never use blocking code there.
- Exceptions extend the **sealed** `RoutifyException` hierarchy (`NotFound`, `Conflict`, `Validation`, `BadRequest`, `Unauthorized`, `Forbidden`, `RateLimitExceeded`, `QuotaExceeded`, `GatewayError`, `HeuristicError`) — never throw raw `RuntimeException`. Error responses are serialised by `exception.io.routify.common.GlobalExceptionHandler` as **RFC 9457 ProblemDetail** JSON (`type`, `title`, `status`, `detail`, `errorCode`).
- Use **MapStruct** for DTO↔entity mappings (annotation processor configured in parent `pom.xml`). Lombok + MapStruct binding order matters: `lombok-mapstruct-binding` is declared explicitly. Lombok version is overridden to **1.18.44** in the parent POM for JDK 25 compatibility.
- All Kafka producers use `acks=all` + idempotent mode. Kafka writes go through the **Transactional Outbox** pattern in three services: **route-service** (`OutboxPoller`), **identity-service** (`IdentityOutboxPoller`), and **cert-vault** (`CertOutboxPoller`). All pollers share the same design: poll every 250 ms, retry failed after 30 s, max 5 attempts, graceful shutdown with `ReentrantLock` + 10 s drain timeout. Configurable via `routify.outbox.*` properties (`poll-interval-ms`, `batch-size`, `max-retries`, `retry-interval-ms`). **Route-service** additionally uses a PostgreSQL `NOTIFY/LISTEN` trigger (`V4__outbox_notify_trigger.sql` + `OutboxNotifyListener`) to wake the poller immediately on insert — the scheduled poll is a safety-net fallback.
- **Kafka command idempotency**: All command consumers (route-service, identity-service, cert-vault) persist processed command IDs in a `processed_commands` table (`ProcessedCommandRepository`). The `commandId` field on `CommandEvent` is the idempotency key — duplicate deliveries are detected and skipped before execution. Migration: `V5__add_processed_commands.sql` (route-service, identity-service) / `V4__add_processed_commands.sql` (cert-vault).
- **Application-level caching** uses **Caffeine** in-process caches (`@EnableCaching` + `CacheConfig` class per service, `@Cacheable`/`@CacheEvict` annotations). Three services have caches: **route-service** (`gatewaySnapshot` — maximumSize=1, TTL=60s, evicted on outbox publish), **identity-service** (`users` — maximumSize=500, TTL=120s; `tenants` — maximumSize=100, TTL=300s; both evicted on any mutation), **admin-api** (`activeWorkspaces` — maximumSize=1, TTL=30s, evicted on tenant create/update/suspend/reactivate). Cache stats are enabled via `recordStats()` and auto-exposed as Micrometer `cache_*` metrics.
- Database migrations use **Flyway** (`classpath:db/migration`). `ddl-auto: validate` — never `update`. Each service uses its own schema: `routify` (route-service), `routify_identity` (identity-service), `routify_audit` (audit-service), `routify_cert` (cert-vault).
- Write endpoints that dispatch Kafka commands return `AsyncAcknowledgement` (HTTP 202) from `web.io.routify.common.AsyncAcknowledgement`.
- Actuator management ports are `9080`–`9086` (app port + 1000). Prometheus metrics scraped there.
- **Distributed tracing** uses **Micrometer Tracing + OpenTelemetry OTLP** exporter. All services export spans to **Grafana Tempo** (`http://localhost:4318/v1/traces`). W3C trace propagation is enabled. Sampling probability defaults to `1.0` (override with `TRACING_SAMPLING_PROBABILITY`). Tracing is disabled in the test profile (`management.tracing.enabled: false`).
- Metrics are registered in `RoutifyMetrics` (from `routify-common`) under the `routify.*` namespace. ACME lifecycle metrics: `routify.cert.acme.renewals` (successful auto-renewals counter), `routify.cert.acme.failures` (failed ACME operations counter). Quota metric: `routify.gateway.requests.quota_exceeded` (requests rejected due to monthly tenant quota exceeded). Cluster metric: `routify.gateway.cluster.config-version` (gauge, local config version counter per gateway instance).
- Sensitive DTO fields (secrets, passwords, API keys) are masked via `@SensitiveField` annotation + `Sensitive.maskFields(dto)` before returning to clients. On incoming writes, check `Sensitive.isMasked(value)` before overwriting stored secrets.
- All `routify-admin-api` REST endpoints use the `/api/v1/admin/` prefix (e.g. `/api/v1/admin/routes`, `/api/v1/admin/gateway`, `/api/v1/admin/audit/replay`, `/api/v1/admin/roles`). Auth endpoints use `/api/v1/auth/`. Gateway config endpoints live in a separate package (`gateway.controller.io.routify.admin.GatewayConfigController`). `AdminDashboardController` serves aggregated dashboard stats (`GET /api/v1/admin/stats`, `GET /api/v1/admin/dashboard/gateway-status`) and an SSE event stream (`GET /api/v1/admin/events`) via `DashboardStatsService` and `DashboardEventBroadcaster`. Each downstream messaging client in admin-api (e.g. `RouteServiceClient`, `RouteFilterMessagingClient`, `IdentityMessagingClient`, `AuditMessagingClient`, `CertVaultMessagingClient`, `AiMessagingClient`) is wrapped with a **Resilience4j circuit breaker** named after the service (e.g. `"route-service"`, `"identity-service"`) with a 5 s time limiter.
- **Route Import/Export (GitOps)**: `AdminExportImportController` provides declarative YAML/JSON gateway configuration management at `/api/v1/admin/routes/export` (GET — export), `/api/v1/admin/routes/import/preview` (POST — dry-run diff), and `/api/v1/admin/routes/import` (POST — apply). Export uses `ExportService` to query all routes, filters, and gateway config via existing RabbitMQ clients, serializes to YAML (SnakeYAML block style) or JSON, and masks sensitive config values. Import uses `ImportService` to parse YAML/JSON, validate `apiVersion: routify/v1`, compute name-based diffs, and publish `CommandEvent.CreateRoute`/`UpdateRoute`/`CreateFilter`/`UpdateFilter` via Kafka. Import is additive (no deletes). Idempotent `commandId` generation uses `SHA-256(tenantId + resourceName + operation)`. DTO: `dto.export.io.routify.common.GatewayExportV1` (shared library). Schema: `docs/schema/route-export-v1.yaml`. CLI examples: `docs/cli-examples.md`.
- **GitOps Reconciliation Agent (`routify-gitops-agent`)**: Standalone Spring Boot service (port 8087, actuator 9087) that continuously reconciles a Git repository with the live gateway configuration. Uses JGit to clone/fetch a configurable repo + branch, reads the config YAML file, computes SHA-256 hash, and compares with last-applied hash stored in Redis (`routify:gitops:last-hash:{tenantId}`). When changes are detected: calls admin-api `/api/v1/admin/routes/import/preview` then `/api/v1/admin/routes/import` to apply. Authenticates to admin-api using an API key (`X-Api-Key` header), not JWT. Supports dry-run mode (`routify.gitops.dry-run=true`) that detects drift without applying. Reports results via configurable webhook URL (HMAC-SHA256 signed). Accepts GitHub/GitLab webhook push events at `POST /api/v1/gitops/webhook` for immediate reconciliation. Status/history exposed at `GET /api/v1/gitops/status` and `GET /api/v1/gitops/history`. Reconciliation history (last 50 results) stored in Redis list (`routify:gitops:history:{tenantId}`). Micrometer metrics: `routify.gitops.reconciliations` (counter, tagged by outcome), `routify.gitops.latency` (timer). Configuration via `routify.gitops.*` properties. Dashboard module: `src/modules/gitops/`.

**`routify-common` shared utilities (beyond events/topics):**
- `client.io.routify.common.AmqpServiceClientSupport` — base class for all RabbitMQ request/reply clients; extend it and call `rpc(routingKey, request, TypeReference)`. Also exposes `send()` for fire-and-forget.
- `client.io.routify.common.KafkaServiceClientSupport` — base class for Kafka producers; provides `publish()` (async), `publishSync()` (broker-ACK), `publishCommand()` (standard command envelope), and `publishEvent()` (domain event) helpers.
- `event.io.routify.common.CommandEvent` — **sealed interface** (Java 21) representing every write command sent over Kafka. All concrete command types are `record`s that implement it (e.g. `CommandEvent.CreateRoute`, `CommandEvent.UpdateFilter`, `CommandEvent.PromoteRoute`). Deserialise with `objectMapper.readValue(json, CommandEvent.class)` and use a `switch` expression on the sealed type. The `commandId` field is the idempotency key. `CommandEvent.Unknown` is the fallback for unrecognised `"type"` discriminators.
- `event.io.routify.common.DomainEvent` — base type for domain event payloads published on event topics.
- `event.io.routify.common.QueryRequest` / `QueryResponse` — typed wrappers for all RabbitMQ request/reply calls. Each query variant is a nested record (e.g. `QueryRequest.AiFilterEvaluate`).
- `kafka.io.routify.common.KafkaDlqErrorHandlerFactory` — shared DLQ `DefaultErrorHandler` with exponential back-off (1 s × 2.0, max 30 s ≈ 5 retries); deserialisation errors go straight to DLQ. Use: `factory.setCommonErrorHandler(KafkaDlqErrorHandlerFactory.create(kafkaTemplate))`.
- `security.io.routify.common.SecurityContext` — Java 21 **record** (`userId: UUID`, `tenantId: UUID`, `username`, `role`, `correlationId`, `permissions: Set<String>`) stored in a `ThreadLocal`; set by the JWT filter in each service. Call `SecurityContext.current()` / `SecurityContext.set()` / `SecurityContext.clear()`. Convenience helpers: `hasRole(String)`, `isSuperAdmin()`, `isTenantAdmin()`, `hasPermission(Permission)`, `hasPermission(String)`. MDC enrichment: call `ctx.setMdc()` to populate SLF4J MDC with `userId`, `tenantId`, `correlationId`; call `SecurityContext.clearMdc()` on scope end. Static `putMdc(userId, tenantId, correlationId)` is available for Kafka consumers that extract identifiers from record headers.
- `security.io.routify.common.RedisKeys` — centralised Redis key prefixes (e.g. `BLOCKLIST_PREFIX = "routify:token:blocklist:"`, `APIKEY_PREFIX = "routify:apikeys:"` — API key authentication via Redis Hash with fields `tenantId`, `userId`, `role`, `email`, optional `expiresAt`; `QUOTA_PREFIX = "routify:quota:"` — monthly request counter per tenant, incremented by gateway; `QUOTA_WARNED_PREFIX = "routify:quota:warned:"` — dedup flag for 80% quota warning webhooks; `GATEWAY_INSTANCES_PREFIX = "routify:gateway:instances:"` — per-instance heartbeat Hash (hostname, port, configVersion, routeCount, filterCount, startedAt, lastReloadAt, lastHeartbeatAt, TTL=30s); `GATEWAY_INSTANCES_SET = "routify:gateway:instances"` — Set of all registered gateway instance IDs; `GATEWAY_CONFIG_VERSION = "routify:gateway:config-version"` — shared monotonic counter incremented on every route/filter/config reload).
- `config.io.routify.common.SecretValidator` — validates required secrets at startup via `routify.required-secrets` config property.
- `domain.io.routify.common.FilterType` — enum of all gateway filter types (keep in sync with TypeScript `FilterType` union in dashboard and every `*GatewayFilterFactory` in the gateway). Active types include `AUTH_*` (`AUTH_API_KEY`, `AUTH_BASIC`, `AUTH_JWT`, `AUTH_MTLS`, `AUTH_OAUTH2`, `AUTH_CLIENT_ID`, `AUTH_CERT_VAULT`), `DOWNSTREAM_BASIC_AUTH`, `DOWNSTREAM_BEARER_CC`, `RATE_LIMIT_FIXED_WINDOW`, `RATE_LIMIT_SLIDING_WINDOW`, `REQUEST_HEADER_MODIFY`, `RESPONSE_HEADER_MODIFY`, `AI_FILTER`, `AI_MODIFIER`, `BODY_JOLT_TRANSFORM`, `VALIDATE_JSON_SCHEMA`, `TIMEOUT`, `CONDITIONAL_ROUTE`, `USER_ID_PAYLOAD_ROUTING`, `CERT_ROTATION`, `CERT_VAULT_EXPIRY_CHECK`, `API_VERSIONING`, `CORRELATION_ID`, `REQUEST_LOGGER`, `TENANT_CONTEXT`, `SECURITY_HEADERS`, `CUSTOM_METRIC`, `CUSTOM_SPEL`. Several legacy values are `@Deprecated` (no factory implementation — kept for DB compatibility only; `RouteDefinitionBuilder` logs a warning and skips them): `AUTH_NONE`, `RATE_LIMIT_TOKEN_BUCKET`, `PATH_REWRITE`, `PATH_STRIP_PREFIX`, `PATH_ADD_PREFIX`, `QUERY_PARAM_MODIFY`, `BODY_JSONATA_TRANSFORM`, `BODY_SPEL_TRANSFORM`, `VALIDATE_REGEX`, `VALIDATE_SIZE`, `CIRCUIT_BREAKER`, `RETRY`.
- `domain.io.routify.common.TenantPlan` — enum (`FREE`, `STARTER`, `PRO`, `ENTERPRISE`) encoding `maxRoutes`, `maxFilters`, `monthlyRequestQuota` quotas. **Quotas are enforced at two layers**: (1) route-service checks `currentRouteCount < plan.maxRoutes()` and `currentFilterCount < plan.maxFilters()` before executing `CreateRoute`/`CreateFilter` commands (throws `RoutifyException.QuotaExceeded`); (2) gateway's `TenantContextGatewayFilterFactory` atomically increments a Redis monthly counter (`RedisKeys.QUOTA_PREFIX + tenantId + ":" + YearMonth`) and returns HTTP 429 with `Retry-After` when `count > plan.monthlyRequestQuota()`. Both layers maintain a local `ConcurrentHashMap<UUID, TenantPlan>` plan cache populated from `TENANT_EVENTS` Kafka topic. ENTERPRISE plan (Integer.MAX_VALUE) bypasses all quota checks. Gateway quota can be disabled via `routify.gateway.quota.enabled: false`.
- `domain.io.routify.common.WebhookEventType` — enum of all platform event types that can trigger webhook notifications (e.g. `ROUTE_ACTIVATED`, `CERT_EXPIRING`, `AI_FILTER_BLOCKED`, `QUOTA_WARNING`, `QUOTA_EXCEEDED`, `GATEWAY_CONFIG_DRIFT`). Keep in sync with TypeScript `WebhookEventType` union in the dashboard `src/types/index.ts`.
- `domain.io.routify.common.UserRole` / `RouteStatus` / `RouteEnvironment` — additional domain enums in the same package. `UserRole` values: `SUPER_ADMIN`, `TENANT_ADMIN`, `VIEWER`, `OPERATOR`. `RouteStatus` values: `DRAFT`, `ACTIVE`, `DISABLED`, `ARCHIVED` (lifecycle: `DRAFT` → `ACTIVE` ↔ `DISABLED` → `ARCHIVED`). `RouteEnvironment` values: `STAGING`, `PRODUCTION` (default `PRODUCTION`; staging routes only match when `X-Route-Environment: STAGING` header is present; promotion copies staging → production atomically and archives the staging version).
- `domain.io.routify.common.Permission` — enum of 27 fine-grained RBAC permission constants (e.g. `ROUTES_READ`, `ROUTES_WRITE`, `CERTS_ADMIN`, `TENANTS_SUSPEND`). Used in JWT `permissions` claim, `SecurityContext.hasPermission()`, and `@PreAuthorize` annotations. Keep in sync with TypeScript `Permission` union in `src/types/index.ts`.
- `web.io.routify.common.RoutifyHeaders.resolveActor(userId, principalName)` — resolves the acting principal for audit/command attribution (resolution order: `X-User-Id` header → principal name → `"system"`).
- `event.io.routify.common.RequestTelemetryEvent` / `AiFilterDecisionEvent` / `AiModificationDecisionEvent` — event payloads published to their respective Kafka topics (telemetry, AI filter decisions, AI modification events).
- `web.io.routify.common.PageResponse` — generic paginated response **record** wrapping `content`, `page`, `size`, `totalElements`, `totalPages`, `first`, `last`. Factory methods: `PageResponse.of(content, page, size, totalElements)` and `PageResponse.from(Spring Data Page<T>)`. Used by all RabbitMQ query handlers that return paginated results.

**Frontend (`routify-dashboard`):**
- Feature code lives in `src/modules/<feature>/`. Shared primitives go in `src/components/ui/`.
- All HTTP calls use the single `apiClient` (Axios) in `src/api/client.ts` — it handles JWT injection, `X-Tenant-Id` header, and the 401→refresh lock.
- Error extraction uses `extractApiError()` from `src/lib/utils.ts` — the single source for API error formatting. Never create separate error utility files. The same file exports `cn()` (`clsx` + `tailwind-merge`) for conditional class merging.
- Access token stored in **Zustand** memory only (`src/store/authStore.ts`); refresh token is an **HttpOnly cookie** — JS never reads it.
- Server state managed by **TanStack Query**; client-only state by **Zustand**.
- Forms use **React Hook Form + Zod**.
- Route topology editor uses **`@xyflow/react`** (`src/modules/routes/`, `src/modules/workflow-builder/`).
- Routing uses **React Router 7** (`react-router-dom` v7). Charts use **Recharts**. Icons use **Lucide React**. Toasts use **sonner**.
- Custom hooks in `src/hooks/`: `useWebSocket` (STOMP-lite over native WS, exponential backoff, singleton per URL), `useRealtimeQuery` (wraps TanStack Query with automatic WS-event-driven cache invalidation — replaces polling), `useBootstrapAuth` (silent session restore via HttpOnly cookie on page load), `useDocumentTitle` (sets `document.title` to `"PageName — Routify"`, restores on unmount).
- TypeScript types are split: `src/types/index.ts` (domain types: `UserRole`, `FilterType`, `Page<T>`, `ApiError`, etc.) and `src/types/ws.ts` (WebSocket types: `WsEventType`, `WsMessage`, `CircuitBreakerState`, `WsStatus`).
- Real-time gateway events are delivered via **WebSocket/STOMP** through `WebSocketProvider` (`src/components/WebSocketProvider.tsx`). The admin-api exposes two endpoints: `GET /api/v1/admin/events` (SSE, `text/event-stream`) and a STOMP broker at `/ws` (SockJS fallback) / `/ws/websocket` (raw WS). The dashboard connects to `/ws/websocket` via raw STOMP and subscribes to `/topic/events` (domain events), `/topic/metrics` (live gateway metrics), and `/topic/audit` (live audit entries). `wsStore` (`src/store/wsStore.ts`) holds connection status, recent events, circuit-breaker state, and live metrics.
- `src/modules/ai/` — AI filter and AI modifier stats pages. `src/modules/api-keys/` — API key lifecycle management (create, revoke, rotate). `src/modules/audit/` — audit log viewer. `src/modules/auth/` — login, password change, protected route guard. `src/modules/certificates/` — cert vault + cert groups management. `src/modules/filters/` — filter definition and configuration. `src/modules/gateway/` — gateway health dashboard v2 with 13 tabs: Overview, Routes Health (latency heatmap), SLOs (error budget tracking), Circuit Breakers (live WS), Fleet (multi-gateway cluster instances and config versions), CORS, Security Headers, Rate Limiting, Resilience, Auth Providers, Networking, Tenant Isolation, Global Filters. New tabs added in `tabs/RoutesHealthTab.tsx`, `tabs/SloTab.tsx`, `tabs/CircuitBreakersTab.tsx`, `tabs/FleetTab.tsx`. Shared `TraceLink` component in `components/TraceLink.tsx` links to Grafana Tempo. `components/InstanceCard.tsx` renders per-instance fleet status cards. `src/modules/gitops/` — GitOps reconciliation agent dashboard (status, history, sync trigger). `src/modules/roles/` — role definitions and permission management (granular RBAC). `src/modules/routes/` — route management. `src/modules/settings/` — platform settings. `src/modules/users/` — user management. `src/modules/webhooks/` — webhook subscription management (create, delete, test ping, delivery log). `src/modules/workspaces/` — workspace (tenant) management with usage analytics (UsageOverview progress bars, UsageTrendChart daily request chart). `src/modules/workflow-builder/` — visual route topology editor (shares `@xyflow/react` with `src/modules/routes/`).
- Separate API modules in `src/api/`: `authApi.ts`, `routesApi.ts`, `filtersApi.ts`, `usersApi.ts`, `tenantsApi.ts`, `auditApi.ts`, `certVaultApi.ts`, `gatewayApi.ts`, `aiApi.ts`, `apiKeysApi.ts`, `webhooksApi.ts`, `rolesApi.ts`, `exportImportApi.ts`, `gitopsApi.ts`.
- Key env vars: `VITE_API_BASE_URL` (defaults to `http://localhost:8082`) — used by both `apiClient` and as the base for the derived WebSocket URL. `VITE_MOCK=true` enables MSW mode. `VITE_GRAFANA_URL` (defaults to `http://localhost:3001`) — base URL for Grafana Tempo trace link-out in the Gateway Health Dashboard v2 and audit modals. In dev the Vite proxy forwards `/api`, `/ws`, and `/sse` to `localhost:8082` so `VITE_API_BASE_URL` can be left unset. Set in `.env.local`.

## Developer Workflows

### Start infrastructure (required before any service)
```bash
docker compose --env-file .env up -d
# PostgreSQL :5432, Redis :6379, Kafka :9092, RabbitMQ :5672/:15672
# Tempo :3200/:4317/:4318, Prometheus :9091, Grafana :3001
```

### Build all Java modules
```bash
mvn clean package -DskipTests
# Build a single service (with its dependencies):
mvn clean package -pl routify-route-service -am -DskipTests
```

### Run services locally
Use the pre-configured IntelliJ run configurations in `.run/` (`Routify All Services`, `Routify Full Stack`, per-service configs).  
Or from CLI: `java -jar routify-<service>/target/routify-<service>-2.0.2-SNAPSHOT.jar`

### Frontend development
```bash
cd routify-dashboard
npm install
npm run dev           # requires backend running
npm run dev:mock      # MSW offline mode — no backend needed
npm run build         # production build → dist/
npm run lint          # ESLint + Prettier check
npm run format        # Prettier auto-fix
npm run typecheck     # tsc type-check only (no emit)
```
Mock handlers live in `src/mocks/handlers/`. Mock mode is enabled by `VITE_MOCK=true`.

### Full containerised stack
```bash
mvn clean package -DskipTests
docker compose -f docker-compose.yml -f docker-compose.app.yml up -d
# Dashboard served on :3000
```

### Reset all data
```bash
./scripts/reset-data.sh                # wipe and restart infra
./scripts/reset-data.sh --no-restart   # wipe only
./scripts/reset-data.sh --skip-grafana # keep Grafana dashboards
```

### Setup Docker (branch-aware)
```bash
./scripts/setup-docker.sh                         # interactive branch picker, infra only
./scripts/setup-docker.sh develop                  # infra for develop branch
./scripts/setup-docker.sh develop app              # infra + all application services
```
Copies `environments/.env.<branch>` → `.env` and brings up the requested Docker Compose stack. Modes: `infra` (default), `app`/`full` (infra + all services).

### Testing

**Java integration tests** use **Testcontainers** (Kafka, RabbitMQ, Redis, PostgreSQL). Convention: `*IT.java` suffix (run by maven-failsafe-plugin). Each service with ITs has its own base class:
- `AdminApiIntegrationBase` (admin-api) — MockMvc, unsigned JWT generation (`generateTestJwt()`), mock RabbitMQ reply listeners (`mockRabbitReply()`), and Kafka test consumer (`drainTopic()`).
- `RouteServiceIntegrationBase` (route-service) — Testcontainers for Postgres/Kafka/RabbitMQ, outbox poller, and `ProcessedCommandRepository` cleanup.
- `IdentityServiceIntegrationBase` (identity-service) — Testcontainers for Postgres/Kafka/RabbitMQ/Redis, JWT service, and auth/user repositories.

> **ITs disabled by default:** `<skipITs>true</skipITs>` is set globally in the parent POM due to a Docker Engine 29.x / Testcontainers incompatibility. Re-enable with `mvn verify -DskipITs=false`. The `docker-java` client is overridden to **3.7.1** for API version negotiation with Docker Engine 29.x. Testcontainers version is managed by Boot 4.0.5 (TC 2.0.4). TC 2.x renamed artifacts with `testcontainers-` prefix (e.g. `testcontainers-junit-jupiter`, `testcontainers-kafka`).

```bash
mvn verify                             # unit tests only (ITs skipped by default)
mvn verify -DskipITs=false             # unit + integration tests (requires Docker)
mvn verify -Pquick                     # unit tests only (legacy alias, same as default)
mvn verify -pl routify-admin-api -am   # single module with deps
```

**Frontend unit tests** use **Vitest** + happy-dom + Testing Library. Tests live in `src/__tests__/` and `src/**/*.{test,spec}.{ts,tsx}`.

```bash
cd routify-dashboard
npm test                               # watch mode
npm run test:ci                        # single run (CI)
```

**Frontend E2E tests** use **Playwright** running against MSW mock mode (no backend). Auth setup pattern (`e2e/auth.setup.ts`) stores session in `e2e/.auth/user.json`.

```bash
cd routify-dashboard
npm run test:e2e                       # headless
npm run test:e2e:ui                    # interactive UI mode
npm run test:e2e:ci                    # headless with GitHub reporter (CI)
```

**CI containerised build** uses `docker-compose.ci.yml` overlay with `Dockerfile.ci` (copies pre-built JARs, no in-Docker Maven build):

```bash
mvn clean package -DskipTests
docker compose -f docker-compose.yml -f docker-compose.app.yml -f docker-compose.ci.yml build
docker compose -f docker-compose.yml -f docker-compose.app.yml -f docker-compose.ci.yml up -d
```

> **JDK 25 compatibility:** Spring Kafka sealed-class compatibility with JDK 25 is resolved in the current dependency set (Boot 4.0.5). The compiled class overrides (`org/springframework/kafka/listener/`) that previously patched Spring Kafka have been removed — they are no longer needed.

## Environment / Secrets

Copy `environments/.env.develop` → `.env` (or generate via the **"Generate .env"** GitHub Actions workflow). Required variables: `DB_PASS`, `RABBITMQ_PASS`, `REDIS_PASS`, `JWT_PRIVATE_KEY`, `JWT_PUBLIC_KEY`, `CERT_VAULT_ENCRYPTION_KEY`. Optional: `ADMIN_INITIAL_PASSWORD` (initial admin seed — identity-service `DataSeeder`), `GRAFANA_PASSWORD` (Grafana admin). For the AI service: `OPENAI_API_KEY` (fail-fast at startup if absent). Services validate required secrets at boot via `routify.required-secrets` config property.

IntelliJ `.run/*.run.xml` configs auto-load `$PROJECT_DIR$/environments/.env.develop` via `<envFilePaths>` — no manual copying needed for local dev.

## Service Ports Quick Reference

| Service | App | Actuator |
|---|---|---|
| routify-api-gateway | 8080 | 9080 |
| routify-route-service | 8081 | 9081 |
| routify-admin-api | 8082 | 9082 |
| routify-identity-service | 8083 | 9083 |
| routify-audit-service | 8084 | 9084 |
| routify-cert-vault | 8085 | 9085 |
| routify-ai-service | 8086 | 9086 |
| routify-gitops-agent | 8087 | 9087 |
| routify-dashboard (dev) | 5173 | — |

