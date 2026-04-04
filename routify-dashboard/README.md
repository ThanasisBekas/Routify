# routify-dashboard

Management UI for the Routify API Gateway Platform, built with **React 19**, **TypeScript**, **Vite 6**, and **TailwindCSS 4**.

## Features

- **Route management** — create, update, activate/deactivate, and delete API routes with a visual flow editor (`@xyflow/react`)
- **Filter management** — configure pre/post filters per route
- **Workflow builder** — visual route topology editor (`src/modules/workflow-builder/`, shares `@xyflow/react`)
- **User & tenant management** — invite users, manage tenants and roles
- **Certificate vault** — upload and manage TLS certificates (PEM / PKCS12) and cert groups for mTLS
- **Audit log** — searchable, paged view of all platform events and request logs; supports replay
- **AI filter / modifier** — stats pages and dry-run "Test Policy" / "Test Modification" feature
- **Gateway status** — real-time gateway health dashboard; live circuit-breaker states
- **Real-time updates** — live domain events and metrics via **WebSocket/STOMP** (`/ws/websocket`); SSE fallback (`/api/v1/admin/events`)
- **Mock mode** — full offline development using Mock Service Worker (MSW)

## Tech Stack

| Library | Purpose |
|---|---|
| React 19 | UI framework |
| TypeScript 5.8 | Type safety |
| Vite 6 | Build tool & dev server |
| TailwindCSS 4 | Utility-first styling |
| TanStack Query 5 | Server state & caching |
| Zustand 5 | Client state management (`authStore`, `wsStore`) |
| React Router 7 | Client-side routing |
| React Hook Form + Zod | Form handling & validation |
| Axios | HTTP client (`src/api/client.ts`) |
| Recharts | Analytics charts |
| `@xyflow/react` | Route topology flow editor |
| Lucide React | Icon set |
| MSW 2 | Mock Service Worker for offline dev |
| `sonner` | Toast notifications |

## Project Structure

```
src/
├── api/            # Axios API modules (authApi, routesApi, filtersApi, auditApi,
│                   #   certVaultApi, gatewayApi, aiApi, usersApi, tenantsApi)
├── components/     # Shared UI (AppLayout, WebSocketProvider, …)
│   └── ui/         # Design system primitives (Button, Modal, Table, …)
├── hooks/          # Custom React hooks (useWebSocket, …)
├── lib/            # Utility functions
├── mocks/          # MSW handlers for mock mode (src/mocks/handlers/)
├── modules/        # Feature modules
│   ├── auth/       # Login, token management
│   ├── routes/     # Route CRUD + flow editor
│   ├── filters/    # Filter management
│   ├── workflow-builder/ # Visual route topology editor
│   ├── users/      # User & tenant management
│   ├── workspaces/ # Workspace (tenant) management
│   ├── certificates/ # Certificate vault UI
│   ├── audit/      # Audit log + replay viewer
│   ├── ai/         # AI filter / modifier stats
│   ├── gateway/    # Gateway status dashboard
│   └── settings/   # Platform settings
├── store/          # Zustand stores (authStore.ts, wsStore.ts)
└── types/          # Shared TypeScript types (FilterType union, etc.)
```

## Getting Started

### Prerequisites

- Node.js 20+
- npm 10+

### Install dependencies

```bash
cd routify-dashboard
npm install
```

### Development (with backend)

Ensure all Routify backend services are running, then:

```bash
npm run dev
```

Dashboard is available at [http://localhost:5173](http://localhost:5173).

The Vite dev server proxies `/api`, `/ws`, and `/sse` to `localhost:8082` (admin-api). Leave `VITE_API_BASE_URL` unset in dev.

### Development (mock mode — no backend required)

```bash
npm run dev:mock
```

Uses MSW to intercept all API calls with realistic mock data. Mock handlers live in `src/mocks/handlers/`.

### Build for production

```bash
npm run build   # tsc + vite build → dist/
npm run lint    # ESLint
npm run preview # Preview production build locally
```

## Environment Variables

Set variables in `.env.local` (not committed to version control).

| Variable | Default | Description |
|---|---|---|
| `VITE_API_BASE_URL` | `http://localhost:8082` | Admin API base URL. Also used to derive the WebSocket URL. Leave unset in dev (Vite proxy handles routing). |
| `VITE_MOCK` | `false` | Set to `true` to enable MSW mock mode |

> There is no separate `VITE_WS_URL` — the WebSocket URL is derived from `VITE_API_BASE_URL` in `src/hooks/useWebSocket.ts`.

## Auth Architecture

- Access token stored in **Zustand memory only** (`authStore.ts`) — never in `localStorage`
- Refresh token is an **HttpOnly cookie** — JS never reads it
- `apiClient` (`src/api/client.ts`) handles JWT injection, `X-Tenant-Id` header, and the 401 → refresh lock automatically

## WebSocket / STOMP

`WebSocketProvider` (`src/components/WebSocketProvider.tsx`) connects to `/ws/websocket` via raw STOMP and subscribes to:
- `/topic/events` — domain events (routes, filters, certs, tenants, users)
- `/topic/metrics` — live gateway metrics
- `/topic/audit` — live audit entries

`wsStore` (`src/store/wsStore.ts`) exposes connection status, recent events, circuit-breaker state, and live metrics.

## Docker

```bash
docker build -t routify-dashboard .
docker run -p 80:80 routify-dashboard
```

The image uses Nginx to serve the pre-built static assets (see `nginx.conf`).
