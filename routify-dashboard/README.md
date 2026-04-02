# routify-dashboard

Management UI for the Routify API Gateway Platform, built with **React 19**, **TypeScript**, **Vite 6**, and **TailwindCSS 4**.

## Features

- **Route management** — create, update, activate/deactivate, and delete API routes with a visual flow editor (React Flow / `@xyflow/react`)
- **Filter management** — configure pre/post filters per route
- **User & tenant management** — invite users, manage tenants and roles
- **Certificate vault** — upload and manage TLS certificates (PEM / PKCS12) for mTLS
- **Audit log** — searchable, paged view of all platform events
- **Gateway status** — real-time gateway health dashboard
- **Real-time updates** — live route status changes via SSE / WebSocket (`WebSocketProvider`)
- **Mock mode** — full offline development using Mock Service Worker (MSW)

## Tech Stack

| Library | Purpose |
|---|---|
| React 19 | UI framework |
| TypeScript 5.8 | Type safety |
| Vite 6 | Build tool & dev server |
| TailwindCSS 4 | Utility-first styling |
| TanStack Query 5 | Server state & caching |
| Zustand 5 | Client state management |
| React Router 7 | Client-side routing |
| React Hook Form + Zod | Form handling & validation |
| Axios | HTTP client |
| Recharts | Analytics charts |
| `@xyflow/react` | Route topology flow editor |
| Lucide React | Icon set |
| MSW 2 | Mock Service Worker for offline dev |

## Project Structure

```
src/
├── api/            # Axios API clients (authApi, routesApi, filtersApi, …)
├── assets/         # Static assets
├── components/     # Shared UI components (AppLayout, WebSocketProvider, …)
│   └── ui/         # Design system primitives (Button, Modal, Table, …)
├── hooks/          # Custom React hooks
├── lib/            # Utility functions
├── mocks/          # MSW handlers for mock mode
├── modules/        # Feature modules
│   ├── auth/       # Login, token management
│   ├── routes/     # Route CRUD + flow editor
│   ├── filters/    # Filter management
│   ├── users/      # User & tenant management
│   ├── certificates/ # Certificate vault UI
│   ├── audit/      # Audit log viewer
│   ├── gateway/    # Gateway status dashboard
│   └── settings/   # Platform settings
├── store/          # Zustand stores
└── types/          # Shared TypeScript types
```

## Getting Started

### Prerequisites

- Node.js 20+
- npm 10+

### Install dependencies

```bash
npm install
```

### Development (with backend)

Ensure all Routify backend services are running, then:

```bash
npm run dev
```

Dashboard is available at [http://localhost:5173](http://localhost:5173).

### Development (mock mode — no backend required)

```bash
npm run dev:mock
```

Uses MSW to intercept all API calls with realistic mock data. No backend services needed.

### Build for production

```bash
npm run build
```

Output is placed in `dist/`. The included `nginx.conf` can be used to serve the built assets.

### Lint

```bash
npm run lint
```

## Docker

```bash
docker build -t routify-dashboard .
docker run -p 80:80 routify-dashboard
```

The image uses Nginx to serve the pre-built static assets (see `nginx.conf`).

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `VITE_API_BASE_URL` | `/api` | Base URL for the Admin API (`routify-admin-api`) |
| `VITE_WS_URL` | `ws://localhost:8085/ws` | WebSocket endpoint for real-time events |

Set variables in a `.env.local` file at the project root (not committed to version control).
