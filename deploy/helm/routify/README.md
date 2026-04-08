# Routify Helm Chart

Production-ready Helm chart for deploying the Routify API Gateway Platform on Kubernetes.

## Quick Start

```bash
# Add required secrets
kubectl create secret generic routify-platform-secrets \
  --from-literal=db-password=changeme \
  --from-literal=rabbitmq-password=changeme \
  --from-literal=redis-password=changeme \
  --from-literal=jwt-private-key="$(cat jwt-private.pem)" \
  --from-literal=jwt-public-key="$(cat jwt-public.pem)" \
  --from-literal=cert-vault-encryption-key=changeme \
  --from-literal=openai-api-key=sk-changeme

# Install with default values (includes built-in PostgreSQL, Redis, Kafka, RabbitMQ)
helm install routify deploy/helm/routify/

# Install with production values (external managed services)
helm install routify deploy/helm/routify/ \
  -f deploy/helm/routify/values-production.yaml \
  --set secrets.existingSecret=routify-platform-secrets
```

## Architecture

```
                      ┌─────────────┐
        Internet ────►│   Gateway   │ (LoadBalancer, HPA 2–10)
                      └──────┬──────┘
                             │ Kafka + RabbitMQ
    ┌──────────┐   ┌────────┼────────┐   ┌──────────┐
    │ Dashboard │──►│   Admin API    │   │ AI Svc   │
    └──────────┘   └────────┬────────┘   └──────────┘
                     ┌──────┼──────┐
               ┌─────┤      │      ├─────┐
           ┌───┴──┐┌─┴───┐┌─┴──┐┌──┴───┐
           │Ident.││Route││Cert ││Audit │
           │ Svc  ││ Svc ││Vault││ Svc  │
           └──────┘└─────┘└────┘└──────┘
```

## Values Reference

### Global Settings

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `global.imageRegistry` | string | `""` | Container image registry prefix (e.g. `ghcr.io/routify`) |
| `global.imagePullSecrets` | list | `[]` | Image pull secret names |
| `global.storageClass` | string | `""` | Default StorageClass |
| `global.domain` | string | `routify.local` | Domain for Ingress rules |
| `global.tlsEnabled` | bool | `false` | Enable TLS on Ingress |
| `global.dbUser` | string | `routify` | PostgreSQL username |
| `global.otelEndpoint` | string | `""` | OpenTelemetry OTLP endpoint |

### Secrets

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `secrets.existingSecret` | string | `""` | Name of existing K8s Secret (overrides all below) |
| `secrets.dbPassword` | string | `""` | PostgreSQL password |
| `secrets.rabbitmqPassword` | string | `""` | RabbitMQ password |
| `secrets.redisPassword` | string | `""` | Redis password |
| `secrets.jwtPrivateKey` | string | `""` | RSA private key (PEM) |
| `secrets.jwtPublicKey` | string | `""` | RSA public key (PEM) |
| `secrets.certVaultEncryptionKey` | string | `""` | AES encryption key for cert-vault |
| `secrets.openaiApiKey` | string | `""` | OpenAI API key for AI service |

### Per-Service Configuration

Each service (`gateway`, `adminApi`, `identityService`, `routeService`, `auditService`, `certVault`, `aiService`, `dashboard`, `gitopsAgent`) supports:

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `<service>.enabled` | bool | `true` | Deploy this service |
| `<service>.image.repository` | string | varies | Container image name |
| `<service>.image.tag` | string | `"2.1.0"` | Container image tag |
| `<service>.image.pullPolicy` | string | `IfNotPresent` | Image pull policy |
| `<service>.replicas` | int | `2` | Replica count (ignored if HPA enabled) |
| `<service>.resources.requests.cpu` | string | varies | CPU request |
| `<service>.resources.requests.memory` | string | varies | Memory request |
| `<service>.resources.limits.cpu` | string | varies | CPU limit |
| `<service>.resources.limits.memory` | string | varies | Memory limit |
| `<service>.pdb.enabled` | bool | `true` | Enable PodDisruptionBudget |
| `<service>.pdb.minAvailable` | int | `1` (gateway: `2`) | Minimum available pods during disruption |
| `<service>.service.type` | string | `ClusterIP` | Service type (gateway: `LoadBalancer`) |
| `<service>.service.port` | int | varies | Service port |
| `<service>.env` | object | `{}` | Additional environment variables |

### Gateway Autoscaling

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `gateway.autoscaling.enabled` | bool | `true` | Enable HPA |
| `gateway.autoscaling.minReplicas` | int | `2` | Minimum replicas |
| `gateway.autoscaling.maxReplicas` | int | `10` | Maximum replicas |
| `gateway.autoscaling.targetCPUUtilizationPercentage` | int | `70` | CPU target |

### Ingress

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `ingress.enabled` | bool | `false` | Enable Ingress for dashboard + admin-api |
| `ingress.className` | string | `nginx` | Ingress class name |
| `ingress.annotations` | object | `{}` | Ingress annotations (cert-manager, etc.) |
| `ingress.tls` | list | `[]` | TLS configuration |

### Monitoring

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `monitoring.serviceMonitor.enabled` | bool | `false` | Create ServiceMonitor CRDs |
| `monitoring.serviceMonitor.interval` | string | `15s` | Scrape interval |
| `monitoring.serviceMonitor.namespace` | string | `""` | Override ServiceMonitor namespace |

### Infrastructure Dependencies

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `postgresql.enabled` | bool | `true` | Deploy Bitnami PostgreSQL |
| `redis.enabled` | bool | `true` | Deploy Bitnami Redis |
| `kafka.enabled` | bool | `true` | Deploy Bitnami Kafka |
| `rabbitmq.enabled` | bool | `true` | Deploy Bitnami RabbitMQ |
| `externalPostgresql.host` | string | `""` | External PostgreSQL host |
| `externalRedis.host` | string | `""` | External Redis host |
| `externalKafka.bootstrapServers` | string | `""` | External Kafka bootstrap servers |
| `externalRabbitmq.host` | string | `""` | External RabbitMQ host |

## Flyway Init Containers

Services with database schemas (`identity-service`, `route-service`, `audit-service`, `cert-vault`) use init containers to run Flyway migrations before the main container starts. This ensures schema readiness and prevents the application from starting with an incompatible database.

## Health Probes

All Java services expose:
- **Liveness**: `/actuator/health/liveness` on management port (app port + 1000)
- **Readiness**: `/actuator/health/readiness` on management port

The dashboard uses a TCP probe on port 80.

## External Secrets Operator Integration

For production, use the [External Secrets Operator](https://external-secrets.io/) to sync secrets from Vault, AWS Secrets Manager, etc.:

```yaml
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata:
  name: routify-platform-secrets
spec:
  refreshInterval: 1h
  secretStoreRef:
    name: vault-backend
    kind: ClusterSecretStore
  target:
    name: routify-platform-secrets
  data:
    - secretKey: db-password
      remoteRef:
        key: routify/database
        property: password
    - secretKey: jwt-private-key
      remoteRef:
        key: routify/jwt
        property: private-key
    # ... additional keys
```

Then reference it:
```yaml
secrets:
  existingSecret: routify-platform-secrets
```

## cert-manager TLS Integration

Enable automated TLS certificate management:

```yaml
ingress:
  enabled: true
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt-prod
  tls:
    - secretName: routify-tls
      hosts:
        - routify.example.com
```

