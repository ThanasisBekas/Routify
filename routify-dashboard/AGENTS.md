# routify-dashboard — Agent Guide

Management UI for the Routify platform. React 19 + TypeScript + Vite + TailwindCSS 4.

## Dev Server

- **Port:** 5173 (dev), 80 (Docker/Nginx)
- `npm run dev` — with backend (Vite proxies `/api`, `/ws`, `/sse` to `localhost:8082`)
- `npm run dev:mock` — mock mode via MSW (no backend required)

## Project Structure

```
src/
├── api/            # Axios API modules — one file per domain (routesApi, filtersApi, authApi, etc.)
│   └── client.ts   # ★ Single apiClient instance — ALWAYS use this, never create new Axios instances
├── components/     # Shared UI components
│   ├── AppLayout.tsx            # Main layout shell
│   ├── WebSocketProvider.tsx    # STOMP connection → wsStore
│   ├── ErrorBoundary.tsx
│   └── ui/                     # Design system primitives (Button, Modal, Table, etc.)
├── hooks/          # Custom React hooks (useWebSocket, etc.)
├── lib/
│   └── utils.ts    # ★ cn() helper + extractApiError() — never create separate error utils
├── mocks/          # MSW setup
│   ├── browser.ts  # MSW worker init
│   ├── db.ts       # In-memory mock database
│   ├── handlers/   # One handler file per domain (routes.ts, auth.ts, filters.ts, etc.)
│   └── data/       # Seed data for mock mode
├── modules/        # Feature modules (one folder per feature)
│   ├── auth/       # Login page, token management
│   ├── routes/     # Route CRUD, flow canvas, canary deploy, import/export
│   ├── filters/    # Filter management
│   ├── workflow-builder/ # Visual route topology editor (@xyflow/react)
│   ├── users/      # User management
│   ├── workspaces/ # Tenant/workspace management
│   ├── certificates/ # Certificate vault UI
│   ├── audit/      # Audit log + request replay viewer
│   ├── ai/         # AI filter/modifier stats + test policy
│   ├── gateway/    # Gateway status dashboard
│   ├── alerts/     # Alert rules management
│   ├── api-keys/   # API key management
│   ├── roles/      # Role management
│   ├── webhooks/   # Webhook management
│   ├── gitops/     # GitOps sync status
│   └── settings/   # Platform settings
├── store/          # Zustand stores
│   ├── authStore.ts # ★ Access token in memory only — never localStorage
│   └── wsStore.ts   # WebSocket connection state, recent events, live metrics
└── types/          # Shared TypeScript types (index.ts, ws.ts)
```

## Critical Rules

### API calls
- **Always** use `apiClient` from `src/api/client.ts` — it handles JWT injection, `X-Tenant-Id` header, and 401→refresh lock
- Each domain has its own API module (e.g. `routesApi.ts`) that exports functions using `apiClient`
- Never create new `axios.create()` instances

### State management
- **Server state:** TanStack Query (`@tanstack/react-query`) — cache, invalidation, optimistic updates
- **Client state:** Zustand — `authStore` (auth tokens/user), `wsStore` (WebSocket state)
- Access token stored in Zustand **memory only** — cleared on page refresh, re-acquired via HttpOnly cookie refresh

### Forms
- React Hook Form + Zod for validation
- `@hookform/resolvers` for Zod integration

### Error handling
- Use `extractApiError(error)` from `src/lib/utils.ts` for all error display
- It extracts `detail` field from RFC 9457 ProblemDetail responses
- Never create separate error utility functions

### Styling
- TailwindCSS 4 utility classes
- `cn()` helper from `src/lib/utils.ts` (`clsx` + `tailwind-merge`)
- Icons: `lucide-react`
- Toasts: `sonner`

### Real-time
- `WebSocketProvider` connects to `/ws/websocket` via STOMP
- Subscribes to `/topic/events`, `/topic/metrics`, `/topic/audit`
- State flows into `wsStore` → components re-render reactively

### Mock mode
- `npm run dev:mock` sets `VITE_MOCK=true`
- MSW service worker intercepts all `/api/...` requests
- Mock handlers in `src/mocks/handlers/` — one file per domain
- Mock data in `src/mocks/data/` with in-memory DB in `src/mocks/db.ts`

## Adding a new feature module

1. Create folder `src/modules/<feature>/`
2. Create page components and feature-specific hooks
3. Add API module in `src/api/<feature>Api.ts` using `apiClient`
4. Add route in the router configuration
5. Add MSW handler in `src/mocks/handlers/<feature>.ts` and register in `src/mocks/handlers/index.ts`

## Testing

```bash
npm run test        # Vitest (unit tests, watch mode)
npm run test:ci     # Vitest (single run, verbose)
npm run test:e2e    # Playwright (E2E tests)
npm run lint        # ESLint + Prettier check
npm run typecheck   # TypeScript type checking
```

## Build

```bash
npm install
npm run build       # tsc + vite build → dist/
```

