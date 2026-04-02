# Routify

> **Zero-downtime API Gateway Platform** — v2.0.0-SNAPSHOT

Routify is a self-hosted API gateway platform built on Spring Boot 3 / Spring Cloud Gateway. It provides a dynamic, hot-reloadable routing layer with a rich management dashboard, without requiring any service restarts to add, modify, or remove routes.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                        routify-dashboard                        │
│              React + TypeScript + Vite (port 5173)              │
└───────────────────────────┬─────────────────────────────────────┘
                            │ HTTP / SSE / WebSocket
┌───────────────────────────▼─────────────────────────────────────┐
│                       routify-admin-api                         │
│         BFF — aggregates all management operations              │
└──────┬────────────┬─────────────────┬───────────────────────────┘
       │ Kafka      │ RabbitMQ (req/reply)                        │
       │ Commands   └──────┬──────────┴──────────────────────────┐│
       │                   │                                      ││
┌──────▼──────┐  ┌─────────▼──────┐  ┌────────────┐  ┌─────────┐││
│  route-     │  │  identity-     │  │  audit-    │  │  cert-  │││
│  service    │  │  service       │  │  service   │  │  vault  │││
└──────┬──────┘  └────────────────┘  └────────────┘  └─────────┘││
       │ Kafka Events (route changed)                             ││
┌──────▼──────────────────────────────────────────────────────────┘│
│                     routify-api-gateway                          │
│  Spring Cloud Gateway — hot-reloads routes from Kafka events     │
└──────────────────────────────────────────────────────────────────┘
```

### Modules

| Module | Type | Description |
|---|---|---|
| [`routify-common`](routify-common/README.md) | Library | Shared domain events, types, exceptions, and observability utilities |
| [`routify-identity-service`](routify-identity-service/README.md) | Spring Boot | JWT issuance, user & tenant management |
| [`routify-route-service`](routify-route-service/README.md) | Spring Boot | Route & filter persistence; Kafka Outbox event publishing |
| [`routify-api-gateway`](routify-api-gateway/README.md) | Spring Cloud Gateway | Reactive, zero-downtime dynamic routing with hot-reload |
| [`routify-admin-api`](routify-admin-api/README.md) | Spring Boot (BFF) | Backend-for-Frontend for the dashboard; aggregates all services |
| [`routify-audit-service`](routify-audit-service/README.md) | Spring Boot | Immutable audit log; Kafka consumer, RabbitMQ query responder |
| [`routify-cert-vault`](routify-cert-vault/README.md) | Spring Boot | Encrypted TLS certificate storage; mTLS integration with the gateway |
| [`routify-dashboard`](routify-dashboard/README.md) | React / Vite | Management UI — routes, filters, users, certificates, audit log |

---

## Technology Stack

| Layer | Technology |
|---|---|
| Language | Java 21 (Virtual Threads), TypeScript |
| Framework | Spring Boot 3.4, Spring Cloud 2024.0 |
| Gateway | Spring Cloud Gateway (WebFlux / Reactor) |
| Messaging | Apache Kafka 3.9 (events), RabbitMQ 3.13 (request/reply) |
| Database | PostgreSQL 17 |
| Cache | Redis 7 |
| Migrations | Flyway |
| Auth | JWT (JJWT 0.12, RS256) |
| Frontend | React 19, Vite 6, TailwindCSS 4, TanStack Query, Zustand |
| Build | Maven 3 (multi-module), npm |
| Containers | Docker / Docker Compose |

---

## Getting Started

### Prerequisites

- Java 21+
- Maven 3.9+
- Node.js 20+ / npm 10+
- Docker & Docker Compose

### 1. Start Infrastructure

```bash
docker compose up -d
```

This starts **PostgreSQL**, **Redis**, **Kafka**, and **RabbitMQ** with health checks.

### 2. Build All Java Modules

```bash
mvn clean package -DskipTests
```

### 3. Run Services

Start each Spring Boot service (in separate terminals or as background processes):

```bash
# Identity service (JWT auth)
java -jar routify-identity-service/target/routify-identity-service-2.0.0-SNAPSHOT.jar

# Route service
java -jar routify-route-service/target/routify-route-service-2.0.0-SNAPSHOT.jar

# Audit service
java -jar routify-audit-service/target/routify-audit-service-2.0.0-SNAPSHOT.jar

# Certificate vault
java -jar routify-cert-vault/target/routify-cert-vault-2.0.0-SNAPSHOT.jar

# Admin API (BFF)
java -jar routify-admin-api/target/routify-admin-api-2.0.0-SNAPSHOT.jar

# API Gateway
java -jar routify-api-gateway/target/routify-api-gateway-2.0.0-SNAPSHOT.jar
```

### 4. Run the Dashboard

```bash
cd routify-dashboard
npm install
npm run dev
```

Dashboard is available at [http://localhost:5173](http://localhost:5173).

> **Mock mode** (no backend required): `npm run dev:mock`

---

## Infrastructure Services (Docker Compose)

| Service | Port | Credentials |
|---|---|---|
| PostgreSQL | `5432` | `routify` / `routify_dev_pass` |
| Redis | `6379` | — |
| Kafka | `9092` | — |
| RabbitMQ | `5672` | `routify` / `routify_dev_pass` |

---

## Project Structure

```
Routify/
├── docker-compose.yml          # Infrastructure (Postgres, Redis, Kafka, RabbitMQ)
├── docker/postgres/init.sql    # Database initialisation script
├── pom.xml                     # Parent BOM (dependency management)
├── routify-common/             # Shared library
├── routify-identity-service/   # Auth service
├── routify-route-service/      # Route management service
├── routify-api-gateway/        # Spring Cloud Gateway
├── routify-admin-api/          # BFF for dashboard
├── routify-audit-service/      # Audit log service
├── routify-cert-vault/         # Certificate vault
└── routify-dashboard/          # React management UI
```

---

## License

Private project — all rights reserved.

