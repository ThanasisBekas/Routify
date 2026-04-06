# Initiative Q2-02 — Record Migration for DTOs & Value Objects

> **Parent:** [Q2 2026 Java 25 Roadmap](../Q2-2026-JAVA25-ROADMAP.md) · **Timeline:** Weeks 2–4 · **Owner:** All services

---

## Problem Statement

Despite Java records being available since JDK 16, many DTOs and value objects across Routify services still use Lombok `@Data`, `@Value`, or `@Getter`/`@Setter` annotations. This adds unnecessary annotation processing overhead, makes classes mutable by default (`@Data`), and requires the Lombok IntelliJ plugin for proper IDE support.

## Solution Overview

Convert all pure data-carrier classes (DTOs, response objects, request objects, config value objects) to Java records. Preserve Lombok for JPA entities (which require mutable proxies) and builder patterns that records don't support natively.

---

## Detailed Implementation Steps

### Step 1: Inventory & Classification

**Rule:** A class is a record candidate if:
- It is a DTO, response, request, or value object (no JPA `@Entity`)
- It has no mutable state after construction
- It does not extend another class (records are `final` and extend `java.lang.Record`)
- It is not a Spring `@Component` / `@Service` / `@Configuration`

**Not record candidates:**
- JPA entities (`@Entity`) — require no-arg constructor, mutable setters, proxy-friendly
- Classes with builder patterns that accumulate state
- Classes with inheritance hierarchies

**Task list:**
- [ ] Run IntelliJ inspection "Class can be a record" across all modules
- [ ] Classify each hit as CONVERT / SKIP / REVIEW
- [ ] Create a tracking spreadsheet by module

---

### Step 2: routify-common Conversions

**Already records:** `SecurityContext`, `PageResponse`, `CommandEvent.*`, `QueryRequest.*`, `QueryResponse.*`, `DomainEvent.*`, `AsyncAcknowledgement`, `RequestTelemetryEvent`, `AiFilterDecisionEvent`, `AiModificationDecisionEvent`.

**Candidates to verify/convert:**
- Any remaining classes in `io.routify.common.web`, `io.routify.common.event`, `io.routify.common.dto`

**Task list:**
- [ ] Verify all DTOs in routify-common are already records
- [ ] Convert any remaining non-record DTOs

---

### Step 3: routify-admin-api DTO Conversions

**Directory:** `routify-admin-api/src/main/java/io/routify/admin/dto/`

Expected candidates: request/response DTOs used by controllers.

**Task list:**
- [ ] Convert all request DTOs to records
- [ ] Convert all response DTOs to records
- [ ] Verify Jackson deserialization works (records need `@JsonProperty` on compact constructor params if names don't match)
- [ ] Run `AdminAuthEndpointIT` + `AdminRoutesEndpointIT` to validate

---

### Step 4: routify-route-service DTO Conversions

**Directory:** `routify-route-service/src/main/java/io/routify/route/dto/`

**JPA entities stay as Lombok classes:**
- `Route.java` — `@Entity`, mutable
- `RouteFilter.java` — `@Entity`
- `FilterDefinition.java` — `@Entity`
- `OutboxEvent.java` — `@Entity`

**Convert:**
- All DTOs in the `dto/` package
- `RouteSnapshotDto` in `routing/` package (gateway-side)

**Task list:**
- [ ] Convert route-service DTOs to records
- [ ] Verify MapStruct mappers compile and produce correct mappings
- [ ] Run route-service unit tests

---

### Step 5: routify-identity-service DTO Conversions

**Directory:** `routify-identity-service/src/main/java/io/routify/identity/dto/`

**Keep as Lombok:** `User.java`, `Tenant.java`, `OutboxEvent.java` (JPA entities)

**Task list:**
- [ ] Convert identity-service DTOs to records
- [ ] Verify auth flow (login/refresh DTOs must serialize correctly)
- [ ] Run identity-service integration tests

---

### Step 6: routify-audit-service DTO Conversions

**JPA entities stay:** `AuditLogEntry`, `RequestLog`, `DlqEvent`, `AiFilterDecision`, `AiModificationDecision`

**Convert:** response DTOs used by RabbitMQ handlers

**Task list:**
- [ ] Convert audit-service response DTOs to records

---

### Step 7: routify-cert-vault DTO Conversions

**Directory:** `routify-cert-vault/src/main/java/io/routify/cert/dto/`

**Keep:** `Certificate.java`, `CertGroup.java`, `OutboxEvent.java` (JPA entities)

**Task list:**
- [ ] Convert cert-vault DTOs to records

---

### Step 8: routify-ai-service DTO Conversions

**Directory:** `routify-ai-service/src/main/java/io/routify/ai/dto/`

**Candidates:** `RouteEvaluationRequest`, `RouteEvaluationResponse`, `AiModificationRequest`, `AiModificationResponse`

**Task list:**
- [ ] Convert ai-service DTOs to records
- [ ] Verify Spring AI `ChatClient` response mapping still works

---

### Step 9: Sealed DTO Hierarchies

Where a family of related DTOs exists, convert to sealed interface + record subtypes:

**Example — Gateway config section DTOs:**
```java
public sealed interface GatewayConfigSection
    permits CorsConfig, SecurityHeadersConfig, RateLimitConfig, ... {
    String sectionName();
}
public record CorsConfig(boolean enabled, List<String> origins, ...) implements GatewayConfigSection {
    @Override public String sectionName() { return "cors"; }
}
```

**Task list:**
- [ ] Identify DTO families with shared behavior
- [ ] Convert to sealed interface + record subtypes where beneficial
- [ ] Do NOT force this pattern where it adds complexity without benefit

---

## Acceptance Criteria

- [ ] Zero `@Data` or `@Value` annotations on DTO / value-object classes
- [ ] All JPA entities retain Lombok annotations (no conversion)
- [ ] All MapStruct mappers compile and produce correct record mappings
- [ ] All unit and integration tests pass
- [ ] Jackson serialization/deserialization round-trips are verified for all record DTOs

