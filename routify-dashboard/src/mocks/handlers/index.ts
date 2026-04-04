/**
 * handlers/index.ts — aggregates all MSW request handlers.
 * Order matters: more-specific paths should come before wildcards.
 */
import { authHandlers }    from './auth'
import { routeHandlers }   from './routes'
import { filterHandlers }  from './filters'
import { tenantHandlers }  from './tenants'
import { userHandlers }    from './users'
import { gatewayHandlers } from './gateway'
import { certHandlers }    from './certs'
import { auditHandlers }   from './audit'
import { aiHandlers }      from './ai'

export const handlers = [
  ...authHandlers,
  ...tenantHandlers,   // includes /workspaces — must come before generic :id
  ...routeHandlers,
  ...filterHandlers,
  ...userHandlers,
  ...gatewayHandlers,
  ...certHandlers,
  ...auditHandlers,
  ...aiHandlers,
]

