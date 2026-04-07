# Routify — Kubernetes Deployment Guide

This guide covers deploying the Routify API Gateway Platform on Kubernetes using the official Helm chart.

## Table of Contents

- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
- [Architecture on Kubernetes](#architecture-on-kubernetes)
- [Configuration](#configuration)
  - [Using External Infrastructure](#using-external-infrastructure)
  - [Secrets Management](#secrets-management)
  - [Ingress Configuration](#ingress-configuration)
  - [Resource Tuning](#resource-tuning)
- [Operations](#operations)
  - [Scaling](#scaling)
  - [Upgrades](#upgrades)
  - [Monitoring](#monitoring)
  - [Troubleshooting](#troubleshooting)
- [Production Checklist](#production-checklist)

---

## Prerequisites

| Requirement | Minimum Version |
|---|---|
| Kubernetes | 1.28+ |
| Helm | 3.14+ |
| kubectl | 1.28+ |
| Container images | Built and pushed to a registry |

Optional:
- **cert-manager** — for automated TLS certificate management
- **Prometheus Operator** — for ServiceMonitor discovery
- **External Secrets Operator** — for syncing secrets from Vault/AWS SM/GCP SM

---

## Quick Start

### 1. Build and push container images

```bash
# Build all JARs
mvn clean package -DskipTests

# Build and push images (example with ghcr.io)
for svc in routify-api-gateway routify-admin-api routify-identity-service \
           routify-route-service routify-audit-service routify-cert-vault \
           routify-ai-service routify-gitops-agent routify-dashboard; do
  docker build -t ghcr.io/routify/$svc:2.1.0 ./$svc/
  docker push ghcr.io/routify/$svc:2.1.0
done
```

### 2. Create secrets

```bash
kubectl create namespace routify

kubectl create secret generic routify-platform-secrets \
  --namespace routify \
  --from-literal=db-password='<DB_PASSWORD>' \
  --from-literal=rabbitmq-password='<RABBITMQ_PASSWORD>' \
  --from-literal=redis-password='<REDIS_PASSWORD>' \
  --from-file=jwt-private-key=jwt-private.pem \
  --from-file=jwt-public-key=jwt-public.pem \
  --from-literal=cert-vault-encryption-key='<ENCRYPTION_KEY>' \
  --from-literal=openai-api-key='<OPENAI_API_KEY>'
```

### 3. Install the chart

```bash
# With built-in infrastructure (dev/test)
helm install routify deploy/helm/routify/ \
  --namespace routify \
  --set secrets.existingSecret=routify-platform-secrets

# With external managed infrastructure (production)
helm install routify deploy/helm/routify/ \
  --namespace routify \
  -f deploy/helm/routify/values-production.yaml \
  --set secrets.existingSecret=routify-platform-secrets
```

### 4. Verify deployment

```bash
kubectl get pods -n routify -l app.kubernetes.io/instance=routify
kubectl wait --for=condition=Ready pod -l app.kubernetes.io/instance=routify -n routify --timeout=300s
```

---

## Architecture on Kubernetes

```
                         ┌─────────────────────────────┐
                         │   Kubernetes Cluster         │
                         │                              │
  Internet ─────────────►│  ┌──────────────────────┐   │
                         │  │  Gateway Service      │   │
                         │  │  (LoadBalancer, HPA)  │   │
                         │  └──────────┬───────────┘   │
                         │             │               │
  ┌──────────────────────┼─────────────┼───────────────┤
  │  Ingress Controller  │             │               │
  │  (nginx/traefik)     │   ┌─────────┴────────┐     │
  │       │              │   │  Admin API (BFF)  │     │
  │  ┌────┴────┐         │   └────────┬─────────┘     │
  │  │Dashboard│         │            │                │
  │  └─────────┘         │   ┌────────┼────────┐      │
  │                      │   │   Kafka + AMQP   │      │
  │                      │   │    message bus    │      │
  │                      │   └────────┼────────┘      │
  │                      │   ┌────────┼────────┐      │
  │                      │   │ Identity │ Route │      │
  │                      │   │ Service  │  Svc  │      │
  │                      │   ├─────────┼───────┤      │
  │                      │   │  Audit  │ Cert  │      │
  │                      │   │  Svc    │ Vault │      │
  │                      │   ├─────────┼───────┤      │
  │                      │   │ AI Svc  │GitOps │      │
  │                      │   │         │ Agent │      │
  │                      │   └─────────┴───────┘      │
  └──────────────────────┴────────────────────────────┘
```

### Pod Layout

| Service | Replicas | HPA | PDB | Init Container | DB |
|---|---|---|---|---|---|
| Gateway | 2 | ✅ (2–10) | min 2 | — | — |
| Admin API | 2 | — | min 1 | — | — |
| Identity Service | 2 | — | min 1 | Flyway | ✅ |
| Route Service | 2 | — | min 1 | Flyway | ✅ |
| Audit Service | 2 | — | min 1 | Flyway | ✅ |
| Cert Vault | 2 | — | min 1 | Flyway | ✅ |
| AI Service | 2 | — | min 1 | — | — |
| Dashboard | 2 | — | min 1 | — | — |
| GitOps Agent | 1 | — | — | — | — |

---

## Configuration

### Using External Infrastructure

For production, disable built-in Bitnami sub-charts and point to managed services:

```yaml
# values-custom.yaml
postgresql:
  enabled: false
redis:
  enabled: false
kafka:
  enabled: false
rabbitmq:
  enabled: false

externalPostgresql:
  host: "routify-db.us-east-1.rds.amazonaws.com"
  port: 5432

externalRedis:
  host: "routify-redis.abc123.ng.0001.use1.cache.amazonaws.com"
  port: 6379

externalKafka:
  bootstrapServers: "b-1.routify-kafka.abc123.c2.kafka.us-east-1.amazonaws.com:9092"

externalRabbitmq:
  host: "routify-rabbitmq.us-east-1.amazonaws.com"
  port: 5672
```

### Secrets Management

#### Option A: Inline secrets (development only)

```yaml
secrets:
  dbPassword: "changeme"
  rabbitmqPassword: "changeme"
  redisPassword: "changeme"
  jwtPrivateKey: "-----BEGIN PRIVATE KEY-----\n..."
  jwtPublicKey: "-----BEGIN PUBLIC KEY-----\n..."
  certVaultEncryptionKey: "changeme"
  openaiApiKey: "sk-..."
```

#### Option B: Existing Kubernetes Secret (recommended)

```yaml
secrets:
  existingSecret: "routify-platform-secrets"
```

#### Option C: External Secrets Operator (production)

```yaml
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata:
  name: routify-platform-secrets
  namespace: routify
spec:
  refreshInterval: 1h
  secretStoreRef:
    name: vault-backend
    kind: ClusterSecretStore
  target:
    name: routify-platform-secrets
    creationPolicy: Owner
  data:
    - secretKey: db-password
      remoteRef:
        key: routify/database
        property: password
    - secretKey: rabbitmq-password
      remoteRef:
        key: routify/rabbitmq
        property: password
    - secretKey: redis-password
      remoteRef:
        key: routify/redis
        property: password
    - secretKey: jwt-private-key
      remoteRef:
        key: routify/jwt
        property: private-key
    - secretKey: jwt-public-key
      remoteRef:
        key: routify/jwt
        property: public-key
    - secretKey: cert-vault-encryption-key
      remoteRef:
        key: routify/cert-vault
        property: encryption-key
    - secretKey: openai-api-key
      remoteRef:
        key: routify/openai
        property: api-key
```

Then reference in values:
```yaml
secrets:
  existingSecret: "routify-platform-secrets"
```

### Ingress Configuration

#### With cert-manager (automated TLS)

```yaml
ingress:
  enabled: true
  className: nginx
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt-prod
    nginx.ingress.kubernetes.io/ssl-redirect: "true"
    nginx.ingress.kubernetes.io/proxy-body-size: "10m"
    nginx.ingress.kubernetes.io/websocket-services: routify-admin-api
  tls:
    - secretName: routify-tls
      hosts:
        - routify.example.com
```

#### Gateway service

The gateway is exposed separately as a LoadBalancer (it **is** the ingress for API consumers):

```yaml
gateway:
  service:
    type: LoadBalancer  # or NodePort for bare-metal
    port: 8080
```

### Resource Tuning

Default resource limits are conservative. Tune based on your traffic profile:

```yaml
# High-traffic gateway
gateway:
  resources:
    requests: { cpu: "1", memory: 2Gi }
    limits: { cpu: "4", memory: 4Gi }
  autoscaling:
    enabled: true
    minReplicas: 3
    maxReplicas: 50
    targetCPUUtilizationPercentage: 60
```

---

## Operations

### Scaling

The gateway supports HPA. Other services can be scaled manually:

```bash
# Manual scaling
kubectl scale deployment routify-admin-api --replicas=3 -n routify

# Gateway auto-scales based on CPU (configured in values)
kubectl get hpa -n routify
```

### Upgrades

```bash
# Update image tags in values
helm upgrade routify deploy/helm/routify/ \
  --namespace routify \
  -f values-production.yaml \
  --set gateway.image.tag=2.2.0 \
  --set adminApi.image.tag=2.2.0
  # ... repeat for all services
```

Flyway init containers automatically run any new migrations before the updated service starts.

### Monitoring

Enable ServiceMonitors for Prometheus Operator:

```yaml
monitoring:
  serviceMonitor:
    enabled: true
    interval: 15s
```

Each service exposes Prometheus metrics at `/actuator/prometheus` on its management port (app port + 1000).

### Troubleshooting

```bash
# Check pod events
kubectl describe pod -l app.kubernetes.io/component=gateway -n routify

# View init container logs (Flyway migration)
kubectl logs <pod-name> -c flyway-migrate -n routify

# View application logs
kubectl logs -l app.kubernetes.io/component=identity-service -n routify --tail=100

# Check health endpoints
kubectl exec deploy/routify-admin-api -n routify -- \
  wget -qO- http://localhost:9082/actuator/health

# Validate secrets are mounted
kubectl get secret routify-platform-secrets -n routify -o jsonpath='{.data}' | jq 'keys'
```

---

## Production Checklist

- [ ] All container images built, tagged, and pushed to a private registry
- [ ] `global.imageRegistry` set to your registry
- [ ] `imagePullSecrets` configured for private registry access
- [ ] External PostgreSQL, Redis, Kafka, RabbitMQ configured (built-in infra disabled)
- [ ] Secrets created via External Secrets Operator or kubectl
- [ ] `secrets.existingSecret` references the K8s Secret
- [ ] Ingress configured with TLS (cert-manager)
- [ ] Gateway service type set appropriately (LoadBalancer / NodePort)
- [ ] Resource requests/limits tuned for expected traffic
- [ ] HPA configured for gateway with appropriate min/max replicas
- [ ] PDB `minAvailable` set (gateway ≥ 2, others ≥ 1)
- [ ] ServiceMonitors enabled for Prometheus Operator
- [ ] Network policies applied (if required)
- [ ] RBAC/PSP/Pod Security Standards configured
- [ ] Backup strategy for PostgreSQL
- [ ] Log aggregation configured (EFK/Loki)
- [ ] OpenTelemetry endpoint configured for distributed tracing

