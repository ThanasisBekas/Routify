/**
 * handlers/index.ts — aggregates all MSW request handlers.
 * Order matters: more-specific paths should come before wildcards.
 */
import { authHandlers } from './auth'
import { routeHandlers } from './routes'
import { filterHandlers } from './filters'
import { tenantHandlers } from './tenants'
import { userHandlers } from './users'
import { gatewayHandlers } from './gateway'
import { certHandlers } from './certs'
import { auditHandlers } from './audit'
import { aiHandlers } from './ai'
import { apiKeyHandlers } from './apiKeys'
import { webhookHandlers } from './webhooks'
import { roleHandlers } from './roles'
import { exportImportHandlers } from './exportImport'

export const handlers = [
  ...authHandlers,
  ...tenantHandlers, // includes /workspaces — must come before generic :id
  ...exportImportHandlers, // export/import routes must come before generic route handlers
  ...routeHandlers,
  ...filterHandlers,
  ...userHandlers,
  ...apiKeyHandlers,
  ...webhookHandlers,
  ...roleHandlers,
  ...gatewayHandlers,
  ...certHandlers,
  ...auditHandlers,
  ...aiHandlers,
]
