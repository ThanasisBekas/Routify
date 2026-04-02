# Kafka Production Topology Checklist

> **Phase 6.6** — This document outlines the mandatory requirements for running Routify's
> Kafka infrastructure in production. The `docker-compose.yml` single-broker setup is
> **development-only** and must NEVER be used in production.

## Minimum Requirements

- [ ] **3 broker nodes** (separate Availability Zones)
- [ ] **Replication factor = 3** for all application topics
- [ ] **min.insync.replicas = 2** on all topics
- [ ] **SASL_SSL listeners** enabled (see `kafka-sasl-config.properties`)
- [ ] **Topic compaction** enabled for `routify.gateway.reload` (`log.cleanup.policy=compact`)
- [ ] **DLQ topics** created manually:
  - `routify.route.events.DLQ`
  - `routify.domain.events.DLQ`
  - `routify.filter.events.DLQ`
  - `routify.request.telemetry.DLQ`
  - `routify.gateway.reload.DLQ`
- [ ] **Schema Registry** deployed (Confluent or Apicurio) for schema evolution safety
- [ ] **Monitoring**: Kafka JMX metrics exposed to Prometheus via kafka-exporter
- [ ] **Network policies**: restrict Kafka access to Routify service pods only

## Application Topics

| Topic | Producers | Consumers | Compaction | Partitions |
|---|---|---|---|---|
| `routify.route.events` | route-service | audit-service, gateway | Delete | 6 |
| `routify.filter.events` | route-service | audit-service | Delete | 3 |
| `routify.domain.events` | identity-service | audit-service | Delete | 6 |
| `routify.gateway.reload` | route-service, cert-vault | gateway | **Compact** | 3 |
| `routify.request.telemetry` | gateway | audit-service | Delete | 12 |

## Never in Production

- ❌ `replication.factor=1`
- ❌ `KAFKA_AUTO_CREATE_TOPICS_ENABLE=true` (create topics manually with correct RF)
- ❌ `PLAINTEXT` listeners without network policies
- ❌ Single-broker deployment (no fault tolerance)
- ❌ Default `min.insync.replicas=1` (allows data loss on broker failure)

## Startup Guard

The API gateway includes a `@PostConstruct` check that validates Kafka's
`default.replication.factor >= 3` when the `prod` Spring profile is active.
If the check fails, the gateway refuses to start with a clear error message.

## Topic Creation Script (Production)

```bash
#!/bin/bash
KAFKA_BOOTSTRAP=kafka-1:9092

TOPICS=(
  "routify.route.events:6:3"
  "routify.filter.events:3:3"
  "routify.domain.events:6:3"
  "routify.gateway.reload:3:3"
  "routify.request.telemetry:12:3"
)

for TOPIC_SPEC in "${TOPICS[@]}"; do
  IFS=':' read -r TOPIC PARTITIONS RF <<< "$TOPIC_SPEC"
  kafka-topics.sh --create \
    --bootstrap-server "$KAFKA_BOOTSTRAP" \
    --topic "$TOPIC" \
    --partitions "$PARTITIONS" \
    --replication-factor "$RF" \
    --config min.insync.replicas=2
done

# Compact topic for gateway reload
kafka-configs.sh --alter \
  --bootstrap-server "$KAFKA_BOOTSTRAP" \
  --entity-type topics \
  --entity-name routify.gateway.reload \
  --add-config cleanup.policy=compact

# Create DLQ topics
for TOPIC in routify.route.events routify.filter.events routify.domain.events routify.request.telemetry routify.gateway.reload; do
  kafka-topics.sh --create \
    --bootstrap-server "$KAFKA_BOOTSTRAP" \
    --topic "${TOPIC}.DLQ" \
    --partitions 3 \
    --replication-factor 3
done
```

