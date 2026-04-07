import { http, HttpResponse } from 'msw'

const MOCK_STATUS = {
  enabled: true,
  repositoryUrl: 'https://github.com/acme/routify-config.git',
  branch: 'main',
  configPath: 'routify-export.yaml',
  pollIntervalSeconds: 60,
  dryRun: false,
  tenantId: 'mock-tenant-id',
  lastAppliedHash: 'a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2',
  lastCommitHash: 'abc123def456abc123def456abc123def456abc1',
  lastSyncTime: new Date(Date.now() - 45_000).toISOString(),
  lastOutcome: 'APPLIED' as const,
}

const MOCK_HISTORY = [
  {
    timestamp: new Date(Date.now() - 45_000).toISOString(),
    commitHash: 'abc123def456abc123def456abc123def456abc1',
    configHash: 'a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2',
    outcome: 'APPLIED' as const,
    routesCreated: 1,
    routesUpdated: 2,
    filtersCreated: 0,
    filtersUpdated: 1,
    warnings: [],
    errorMessage: null,
  },
  {
    timestamp: new Date(Date.now() - 120_000).toISOString(),
    commitHash: '789def012345789def012345789def012345789d',
    configHash: 'b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3',
    outcome: 'FAILED' as const,
    routesCreated: 0,
    routesUpdated: 0,
    filtersCreated: 0,
    filtersUpdated: 0,
    warnings: [],
    errorMessage: 'Import preview validation failed: invalid apiVersion',
  },
  {
    timestamp: new Date(Date.now() - 300_000).toISOString(),
    commitHash: '456abc789def456abc789def456abc789def456a',
    configHash: 'c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4',
    outcome: 'APPLIED' as const,
    routesCreated: 3,
    routesUpdated: 0,
    filtersCreated: 2,
    filtersUpdated: 0,
    warnings: ['Route "legacy-api" has deprecated filter type PATH_REWRITE'],
    errorMessage: null,
  },
  {
    timestamp: new Date(Date.now() - 600_000).toISOString(),
    commitHash: '012345678901234567890123456789012345678a',
    configHash: 'd4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5',
    outcome: 'DRIFT_DETECTED' as const,
    routesCreated: 1,
    routesUpdated: 1,
    filtersCreated: 0,
    filtersUpdated: 0,
    warnings: [],
    errorMessage: null,
  },
]

export const gitopsHandlers = [
  http.get('/api/v1/admin/gitops/status', () => {
    return HttpResponse.json(MOCK_STATUS)
  }),

  http.get('/api/v1/admin/gitops/history', () => {
    return HttpResponse.json(MOCK_HISTORY)
  }),

  http.post('/api/v1/admin/gitops/sync', () => {
    return HttpResponse.json({ status: 'triggered', outcome: 'APPLIED' })
  }),
]

