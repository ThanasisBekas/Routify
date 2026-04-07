# Initiative 16 — Helm Chart & Kubernetes-Native Deployment

> **Parent:** [Q4 2026 Roadmap](../Q4-2026-ROADMAP.md) · **Timeline:** Weeks 9–12 · **Owner:** Platform / DevOps team  
> **Prerequisites:** All services stable, Q4-03 (Multi-Gateway Cluster) for gateway HPA

---

## Problem Statement

Routify's production deployment story is Docker Compose only (`docker-compose.app.yml`). Production Kubernetes deployments require manually writing manifests, discovering correct environment variables, configuring health probes, and setting resource limits. There are no HPA rules, no PodDisruptionBudgets, no init-container migration strategy, and no integration with Kubernetes-native secret management.

## Solution Overview

A production-ready Helm chart with sensible defaults, per-service sub-charts, init containers for Flyway migrations, HPA for the gateway, PDB for all services, and integration with external secret operators.

---

## Detailed Implementation Steps

### Step 1: Chart Scaffolding

**Directory structure:**
```
deploy/helm/routify/
├── Chart.yaml
├── values.yaml
├── values-production.yaml
├── templates/
│   ├── _helpers.tpl
│   ├── NOTES.txt
│   └── tests/
│       └── test-connection.yaml
└── charts/
    ├── gateway/
    │   ├── Chart.yaml
    │   ├── values.yaml
    │   └── templates/
    │       ├── deployment.yaml
    │       ├── service.yaml
    │       ├── hpa.yaml
    │       ├── pdb.yaml
    │       └── servicemonitor.yaml
    ├── admin-api/
    │   └── templates/ ...
    ├── identity-service/
    │   └── templates/ ...
    ├── route-service/
    │   └── templates/ ...
    ├── audit-service/
    │   └── templates/ ...
    ├── cert-vault/
    │   └── templates/ ...
    ├── ai-service/
    │   └── templates/ ...
    ├── dashboard/
    │   └── templates/ ...
    └── gitops-agent/       (optional, disabled by default)
        └── templates/ ...
```

**Task list:**
- [x] Create `Chart.yaml` with metadata and sub-chart dependencies
- [x] Create `_helpers.tpl` with common template functions (labels, selectors, fullname)
- [x] Create `NOTES.txt` with post-install instructions

---

### Step 2: Values Schema

**`values.yaml` structure:**
```yaml
global:
  imageRegistry: ""                    # Override: ghcr.io/routify
  imagePullSecrets: []
  storageClass: ""
  domain: routify.local
  tlsEnabled: false

# Per-service blocks (example: gateway)
gateway:
  enabled: true
  image:
    repository: routify-api-gateway
    tag: "2.1.0"
    pullPolicy: IfNotPresent
  replicas: 2
  resources:
    requests: { cpu: 250m, memory: 512Mi }
    limits: { cpu: "1", memory: 1Gi }
  autoscaling:
    enabled: true
    minReplicas: 2
    maxReplicas: 10
    targetCPUUtilizationPercentage: 70
  pdb:
    minAvailable: 2
  service:
    type: LoadBalancer
    port: 8080
  env:
    REDIS_HOST: "{{ .Release.Name }}-redis"
    KAFKA_BOOTSTRAP: "{{ .Release.Name }}-kafka:9092"
    RABBITMQ_HOST: "{{ .Release.Name }}-rabbitmq"

# Same pattern for: adminApi, identityService, routeService,
# auditService, certVault, aiService, dashboard, gitopsAgent

# Infrastructure (optional — disable if using external services)
postgresql:
  enabled: true         # Set false for external PostgreSQL
  auth:
    existingSecret: routify-db-secret
    secretKeys:
      adminPasswordKey: password

redis:
  enabled: true
  auth:
    existingSecret: routify-redis-secret

kafka:
  enabled: true

rabbitmq:
  enabled: true
  auth:
    existingSecret: routify-rabbitmq-secret

# Secrets
secrets:
  jwtPrivateKey: ""             # Base64-encoded RSA private key
  jwtPublicKey: ""              # Base64-encoded RSA public key
  certVaultEncryptionKey: ""
  openaiApiKey: ""
  # Or reference existing Kubernetes secrets:
  existingSecret: ""            # Name of existing K8s Secret with all keys
```

**Task list:**
- [x] Define complete `values.yaml` with all services
- [x] Create `values-production.yaml` overlay with production defaults
- [x] Document all values in `deploy/helm/routify/README.md`

---

### Step 3: Service Templates

**Per-service template pattern (example: `identity-service/templates/deployment.yaml`):**
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "routify.identityService.fullname" . }}
  labels: {{ include "routify.identityService.labels" . | nindent 4 }}
spec:
  replicas: {{ .Values.identityService.replicas }}
  selector:
    matchLabels: {{ include "routify.identityService.selectorLabels" . | nindent 6 }}
  template:
    spec:
      initContainers:
        - name: flyway-migrate
          image: "{{ .Values.identityService.image.repository }}:{{ .Values.identityService.image.tag }}"
          command: ["java", "-cp", "/app/app.jar", "org.springframework.boot.loader.launch.JarLauncher"]
          args: ["--spring.main.web-application-type=none", "--spring.flyway.enabled=true", "--spring.jpa.hibernate.ddl-auto=validate"]
          env: {{ include "routify.identityService.env" . | nindent 12 }}
      containers:
        - name: identity-service
          image: "{{ .Values.identityService.image.repository }}:{{ .Values.identityService.image.tag }}"
          ports:
            - containerPort: 8083
              name: http
            - containerPort: 9083
              name: management
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: management
            initialDelaySeconds: 30
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: management
            initialDelaySeconds: 15
            periodSeconds: 5
          resources: {{ toYaml .Values.identityService.resources | nindent 12 }}
          env: {{ include "routify.identityService.env" . | nindent 12 }}
```

**Services with init containers (Flyway):** identity-service, route-service, audit-service, cert-vault.  
**Services without init containers:** gateway (no DB), admin-api (no DB), ai-service (no DB), dashboard (static files).

**Task list:**
- [x] Create deployment template for each service (8 services + dashboard)
- [x] Create service template for each
- [x] Create init containers for Flyway services
- [x] Create environment variable helper templates
- [x] Create secret references in env templates

---

### Step 4: HPA & PDB

**Gateway HPA:**
```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: {{ include "routify.gateway.fullname" . }}
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: {{ include "routify.gateway.fullname" . }}
  minReplicas: {{ .Values.gateway.autoscaling.minReplicas }}
  maxReplicas: {{ .Values.gateway.autoscaling.maxReplicas }}
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: {{ .Values.gateway.autoscaling.targetCPUUtilizationPercentage }}
```

**PDB for all services:**
```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: {{ include "routify.<service>.fullname" . }}
spec:
  minAvailable: {{ .Values.<service>.pdb.minAvailable | default 1 }}
  selector:
    matchLabels: {{ include "routify.<service>.selectorLabels" . | nindent 6 }}
```

**Task list:**
- [x] Create HPA template for gateway (conditional on `gateway.autoscaling.enabled`)
- [x] Create PDB templates for all services
- [x] Default PDB: `minAvailable: 1` (gateway: 2)

---

### Step 5: Ingress & Networking

**Templates:**
- `admin-api/templates/ingress.yaml` — routes `/api` and `/ws` to admin-api.
- `dashboard/templates/ingress.yaml` — routes `/` to dashboard nginx.
- Gateway uses its own `Service` (LoadBalancer type) — it is NOT behind the cluster Ingress.

**Task list:**
- [x] Create ingress templates (conditional on `ingress.enabled`)
- [x] Support both `networking.k8s.io/v1` Ingress and Gateway API (future)
- [x] Add TLS configuration from Let's Encrypt via cert-manager annotations

---

### Step 6: ServiceMonitor for Prometheus Operator

**Template per service:**
```yaml
{{- if .Values.monitoring.serviceMonitor.enabled }}
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: {{ include "routify.<service>.fullname" . }}
spec:
  selector:
    matchLabels: {{ include "routify.<service>.selectorLabels" . | nindent 6 }}
  endpoints:
    - port: management
      path: /actuator/prometheus
      interval: 15s
{{- end }}
```

**Task list:**
- [x] Create ServiceMonitor template for each service
- [x] Conditional on `monitoring.serviceMonitor.enabled`
- [x] Document Prometheus Operator integration

---

### Step 7: Testing & CI

**Helm chart tests:**
- `helm lint deploy/helm/routify/`
- `helm template routify deploy/helm/routify/ --values deploy/helm/routify/values.yaml | kubectl apply --dry-run=client -f -`
- Smoke test: deploy to Kind cluster, verify all pods are Ready, hit admin-api health endpoint.

**CI integration:**
```yaml
- name: Helm lint
  run: helm lint deploy/helm/routify/

- name: Helm template validation
  run: |
    helm template routify deploy/helm/routify/ \
      --set secrets.jwtPrivateKey=test \
      --set secrets.jwtPublicKey=test \
      --set secrets.certVaultEncryptionKey=test \
      | kubectl apply --dry-run=client -f -

- name: Kind smoke test
  run: |
    kind create cluster
    helm install routify deploy/helm/routify/ --wait --timeout 5m
    kubectl wait --for=condition=Ready pod -l app.kubernetes.io/instance=routify --timeout=300s
```

**Task list:**
- [x] Add `helm lint` to CI
- [x] Add template validation to CI
- [x] Create Kind-based smoke test
- [x] Document quick-start: `helm install routify deploy/helm/routify/`

---

### Step 8: Documentation

**Files to create:**
- `deploy/helm/routify/README.md` — values reference, quick start, production recommendations
- `docs/kubernetes-deployment.md` — full deployment guide with external DB/Redis/Kafka/RabbitMQ configuration

**Task list:**
- [x] Write Helm chart README with values table
- [x] Write Kubernetes deployment guide
- [x] Document External Secrets Operator integration
- [x] Document cert-manager integration for TLS

---

## Acceptance Criteria

- [x] `helm install routify deploy/helm/routify/` deploys all services successfully
- [x] All pods reach Ready state within 5 minutes
- [x] Flyway init containers run migrations before main containers start
- [x] Gateway HPA scales from 2 to 10 pods under CPU load
- [x] PDBs prevent total service disruption during node drain
- [x] ServiceMonitors are discovered by Prometheus Operator
- [x] External secrets can be referenced instead of inline values
- [x] `helm lint` and template validation pass in CI

