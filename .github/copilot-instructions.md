# Routify — Copilot Instructions

Refer to the root `AGENTS.md` for the full architecture guide, messaging topology, and project conventions.

## Quick Reference

- **Monorepo**: Maven multi-module (Java 25) + React/Vite frontend (`routify-dashboard/`)
- **Gateway**: `routify-api-gateway` is **reactive** (WebFlux) — never use blocking code there
- **Messaging**: Kafka for commands (writes), RabbitMQ for queries (reads). Constants in `routify-common` — never use string literals for topic/queue names
- **Exceptions**: Always use `RoutifyException.*` subtypes — never throw raw `RuntimeException`
- **DTOs**: Use MapStruct for entity↔DTO mappings
- **Frontend**: TanStack Query for server state, Zustand for client state, React Hook Form + Zod for forms
- **API calls**: Always use `apiClient` from `src/api/client.ts` — never create new Axios instances
- **Error handling**: Use `extractApiError()` from `src/lib/utils.ts` — never create separate error utils

