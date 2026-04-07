# Initiative 06 — Automated Certificate Lifecycle (ACME)

> **Parent:** [Q3 2026 Roadmap](../Q3-2026-ROADMAP.md) · **Timeline:** Weeks 7–9 · **Owner:** Cert-vault + Gateway teams

---

## Problem Statement

Certificate rotation is the #1 manual operational burden. Operators must generate CSRs, submit to a CA, download certs, upload via the dashboard, and trigger gateway reload. If a cert expires unnoticed, HTTPS routes fail. The existing `CertificateExpiryMonitor` in the gateway detects approaching expiry but cannot act on it — there's no auto-renewal pipeline.

## Solution Overview

Integrate ACME protocol support (Let's Encrypt, ZeroSSL) into `routify-cert-vault`. The vault manages account registration, domain validation (HTTP-01 challenge), certificate issuance, and automatic renewal 30 days before expiry. Newly issued/renewed certs flow through the existing `CertOutboxPoller` → Kafka → gateway hot-reload pipeline.

```
AcmeRenewalScheduler ──→ ACME Server (Let's Encrypt)
        │                       │
        │  HTTP-01 challenge    │  Issue cert
        ◄───────────────────────┘
        │
    Store in cert-vault (AES-encrypted)
        │
    CertOutboxPoller → Kafka CERT_EVENTS → gateway CertEventConsumer
        │
    Gateway hot-reloads TLS cert in CertificateRegistry
```

---

## Detailed Implementation Steps

### Step 1: ACME Client Dependency

**File to modify:**
- `routify-cert-vault/pom.xml`

```xml
<dependency>
    <groupId>org.shredzone.acme4j</groupId>
    <artifactId>acme4j-client</artifactId>
    <version>3.5.0</version>
</dependency>
<dependency>
    <groupId>org.shredzone.acme4j</groupId>
    <artifactId>acme4j-utils</artifactId>
    <version>3.5.0</version>
</dependency>
```

**Task list:**
- [x] Add `acme4j-client` and `acme4j-utils` dependencies
- [x] Verify no conflicts with existing BouncyCastle version

---

### Step 2: Domain Model (cert-vault)

**Files to create:**
- `routify-cert-vault/src/main/resources/db/migration/V{next}__acme_tables.sql`
- `routify-cert-vault/.../domain/AcmeAccount.java`
- `routify-cert-vault/.../domain/AcmeOrder.java`
- `routify-cert-vault/.../repository/AcmeAccountRepository.java`
- `routify-cert-vault/.../repository/AcmeOrderRepository.java`

**Schema:**
```sql
CREATE TABLE routify_cert.acme_account (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    email           VARCHAR(255) NOT NULL,
    account_url     VARCHAR(2048),       -- ACME account URL after registration
    key_pair_pem    TEXT NOT NULL,        -- AES-encrypted account key pair
    provider        VARCHAR(100) NOT NULL DEFAULT 'LETSENCRYPT',
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE routify_cert.acme_order (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      UUID NOT NULL REFERENCES routify_cert.acme_account(id),
    tenant_id       UUID NOT NULL,
    domain          VARCHAR(255) NOT NULL,
    cert_group_id   UUID,                -- target cert group for issued cert
    challenge_type  VARCHAR(20) NOT NULL DEFAULT 'HTTP_01',
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    order_url       VARCHAR(2048),
    challenge_token  VARCHAR(1024),
    challenge_content TEXT,
    cert_id         UUID,                -- resulting certificate ID after issuance
    auto_renew      BOOLEAN NOT NULL DEFAULT true,
    last_renewed_at TIMESTAMPTZ,
    next_renewal_at TIMESTAMPTZ,
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_acme_order_renewal ON routify_cert.acme_order(auto_renew, next_renewal_at)
    WHERE status IN ('COMPLETED', 'PENDING');
```

**Task list:**
- [x] Create Flyway migration
- [x] Create `AcmeAccount` entity (encrypt key pair with existing `CertEncryptionService`)
- [x] Create `AcmeOrder` entity
- [x] Create repositories

---

### Step 3: ACME Service (cert-vault)

**Files to create:**
- `routify-cert-vault/.../service/AcmeService.java`
- `routify-cert-vault/.../service/AcmeChallengeStore.java`

**`AcmeService` methods:**

1. **`registerAccount(tenantId, email, provider)`**
   - Generate RSA key pair.
   - Register with ACME server (`new AccountBuilder().addEmail(email).create(session)`).
   - Store account URL + encrypted key pair.

2. **`requestCertificate(accountId, domain, certGroupId)`**
   - Create ACME order for domain.
   - Retrieve HTTP-01 challenge.
   - Store challenge token + content in `AcmeChallengeStore` (in-memory map, short-lived).
   - Trigger challenge validation.
   - On success: download cert chain + private key.
   - Store cert via existing `CertificateService.uploadCertificate()` (same path as manual upload).
   - Link to `certGroupId`.
   - Publish via `CertOutboxPoller`.

3. **`renewCertificate(orderId)`**
   - Reuse existing account.
   - Create new order for same domain.
   - Same challenge flow.
   - Replace cert in group (old cert → REVOKED, new cert → ACTIVE).

**`AcmeChallengeStore`** — in-memory `ConcurrentHashMap<String, String>` (token → content), entries expire after 5 minutes.

**Task list:**
- [x] Create `AcmeService` with register/request/renew methods
- [x] Create `AcmeChallengeStore`
- [x] Integrate with existing `CertificateService` for cert storage
- [x] Add metrics: `routify.cert.acme.renewals` counter, `routify.cert.acme.failures` counter

---

### Step 4: HTTP-01 Challenge Endpoint (cert-vault)

**File to create:**
- `routify-cert-vault/.../controller/AcmeChallengeController.java`

```java
@RestController
public class AcmeChallengeController {
    private final AcmeChallengeStore store;

    @GetMapping("/.well-known/acme-challenge/{token}")
    public ResponseEntity<String> serveChallenge(@PathVariable String token) {
        return store.get(token)
            .map(content -> ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(content))
            .orElse(ResponseEntity.notFound().build());
    }
}
```

> **Deployment note:** This endpoint must be reachable from the internet on port 80/443 for ACME validation. In Docker environments, map cert-vault port 8085 to the challenge path via the reverse proxy or gateway.

**Task list:**
- [x] Create challenge endpoint
- [x] Document firewall/proxy requirements
- [x] Add security config exception (no JWT required for ACME path)

---

### Step 5: Renewal Scheduler (cert-vault)

**File to create:**
- `routify-cert-vault/.../scheduler/AcmeRenewalScheduler.java`

```java
@Component
@RequiredArgsConstructor
public class AcmeRenewalScheduler {
    private final AcmeService acmeService;
    private final AcmeOrderRepository orderRepository;

    @Scheduled(cron = "${routify.cert.acme.renewal-cron:0 0 3 * * *}") // 03:00 UTC daily
    public void checkAndRenew() {
        Instant renewalWindow = Instant.now().plus(30, ChronoUnit.DAYS);
        List<AcmeOrder> dueForRenewal = orderRepository
            .findByAutoRenewTrueAndNextRenewalAtBeforeAndStatusIn(
                renewalWindow, List.of("COMPLETED"));

        for (AcmeOrder order : dueForRenewal) {
            try {
                acmeService.renewCertificate(order.getId());
                log.info("ACME renewal succeeded for domain={}", order.getDomain());
            } catch (Exception e) {
                log.error("ACME renewal failed for domain={}: {}", order.getDomain(), e.getMessage());
                order.setErrorMessage(e.getMessage());
                order.setStatus("RENEWAL_FAILED");
            }
        }
    }
}
```

**Task list:**
- [x] Create scheduler
- [x] Add config property `routify.cert.acme.renewal-cron`
- [x] Add config property `routify.cert.acme.renewal-days-before: 30`
- [x] Audit event on renewal success/failure

---

### Step 6: Admin-API Endpoints

**File to create:**
- `routify-admin-api/.../controller/AdminAcmeController.java`

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/admin/certs/acme/register` | Register ACME account |
| `POST` | `/api/v1/admin/certs/acme/issue` | Request certificate for domain |
| `GET` | `/api/v1/admin/certs/acme/orders` | List ACME orders (paginated) |
| `GET` | `/api/v1/admin/certs/acme/orders/{id}` | Order detail + challenge status |
| `POST` | `/api/v1/admin/certs/acme/orders/{id}/renew` | Manual renewal trigger |

**Task list:**
- [x] Create controller
- [x] Add Kafka commands for ACME operations
- [x] Add RabbitMQ query handlers in cert-vault
- [x] Wire Resilience4j circuit breaker

---

### Step 7: Dashboard UI

**Files to create:**
- `routify-dashboard/src/modules/certificates/AcmeTab.tsx`
- `routify-dashboard/src/modules/certificates/AcmeOrderRow.tsx`
- `routify-dashboard/src/modules/certificates/AcmeSetupModal.tsx`

**UX:**
- New "ACME / Auto-Renew" tab in the certificates module.
- Account setup wizard: email input, provider selector (Let's Encrypt / ZeroSSL), register button.
- "Issue Certificate" form: domain name, target cert group dropdown, auto-renew toggle.
- Order list: domain, status (PENDING/VALIDATING/COMPLETED/FAILED), last renewed, next renewal date.
- "Renew Now" button for manual trigger.
- Renewal timeline visualization (Recharts).

**Task list:**
- [x] Create ACME tab component
- [x] Create setup modal
- [x] Create order list with status badges
- [x] Add API functions to `certVaultApi.ts`
- [x] Add TypeScript types
- [x] Add MSW mock handlers

---

## Acceptance Criteria

- [x] Operator can register an ACME account and request a cert for a domain from the dashboard
- [x] HTTP-01 challenge is served correctly and cert is issued automatically
- [x] Issued cert is AES-encrypted in cert-vault and hot-reloaded to gateway
- [x] Auto-renewal triggers 30 days before expiry without operator intervention
- [x] Failed renewals generate audit events and increment `routify.cert.acme.failures` metric
- [x] Manual "Renew Now" works as a fallback

---

## Security Considerations

- ACME account key pair is AES-encrypted at rest (same encryption as cert private keys).
- Challenge tokens are ephemeral (5-minute TTL in memory).
- The `/.well-known/acme-challenge/` endpoint is excluded from JWT auth but rate-limited.
- Only SUPER_ADMIN and TENANT_ADMIN can register accounts or issue certificates.

