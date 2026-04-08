import apiClient from './client'
import type { AsyncAcknowledgement, ImportPreviewResponse } from '../types'

// All dashboard requests go through routify-admin-api (the BFF).
// Export/import endpoints are extensions of the routes controller.
const BASE = '/api/v1/admin/routes'

export const exportImportApi = {
  /**
   * Export the full gateway configuration as YAML or JSON.
   * Returns a Blob for download.
   */
  exportConfig: async (params?: { format?: 'yaml' | 'json'; environment?: string }) => {
    const format = params?.format ?? 'yaml'
    const contentType = format === 'json' ? 'application/json' : 'application/x-yaml'
    const res = await apiClient.get(`${BASE}/export`, {
      params: { format, environment: params?.environment },
      headers: { Accept: contentType },
      responseType: 'blob',
    })
    // Extract filename from Content-Disposition header if present
    const disposition = res.headers['content-disposition']
    let filename = `routify-export.${format === 'json' ? 'json' : 'yaml'}`
    if (disposition) {
      const match = disposition.match(/filename="?([^";\n]+)"?/)
      if (match) filename = match[1]
    }
    return { blob: res.data as Blob, filename }
  },

  /**
   * Preview what an import would change without applying it.
   */
  previewImport: (yamlContent: string) =>
    apiClient
      .post<ImportPreviewResponse>(`${BASE}/import/preview`, yamlContent, {
        headers: { 'Content-Type': 'application/x-yaml' },
      })
      .then((r) => r.data),

  /**
   * Apply the import — creates/updates resources via Kafka commands.
   * Returns HTTP 202.
   */
  applyImport: (yamlContent: string) =>
    apiClient
      .post<AsyncAcknowledgement>(`${BASE}/import`, yamlContent, {
        headers: { 'Content-Type': 'application/x-yaml' },
      })
      .then((r) => r.data),
}
