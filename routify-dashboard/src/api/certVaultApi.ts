import apiClient from './client'
import type {
  CertificateDto,
  CertGroupDto,
  CertVaultStats,
  CertStatus,
  CreateCertGroupRequest,
  UpdateCertGroupRequest,
  UploadCertificateRequest,
  Page,
} from '../types'

const BASE = '/api/v1/admin/certificates'
const GROUP_BASE = '/api/v1/admin/cert-groups'

export const certVaultApi = {
  // ─── List certificates (paginated) ──────────────────────────────────────────
  listCertificates: (params: {
    tenantId: string
    status?: CertStatus
    page?: number
    size?: number
    sortBy?: string
    sortDir?: 'ASC' | 'DESC'
  }) =>
    apiClient
      .get<Page<CertificateDto>>(BASE, {
        params: {
          status: params.status,
          page: params.page ?? 0,
          size: params.size ?? 20,
          sortBy: params.sortBy ?? 'createdAt',
          sortDir: params.sortDir ?? 'DESC',
        },
        headers: { 'X-Tenant-Id': params.tenantId },
      })
      .then((r) => r.data),

  // ─── Get single certificate ──────────────────────────────────────────────────
  getCertificate: (id: string, tenantId: string) =>
    apiClient
      .get<CertificateDto>(`${BASE}/${id}`, {
        headers: { 'X-Tenant-Id': tenantId },
      })
      .then((r) => r.data),

  // ─── Vault statistics ────────────────────────────────────────────────────────
  getStats: (tenantId?: string) =>
    apiClient
      .get<CertVaultStats>(`${BASE}/stats`, {
        headers: tenantId ? { 'X-Tenant-Id': tenantId } : {},
      })
      .then((r) => r.data),

  // ─── Upload certificate ──────────────────────────────────────────────────────
  uploadCertificate: (tenantId: string, request: UploadCertificateRequest) =>
    apiClient
      .post<{ status: string; message: string }>(BASE, request, {
        headers: { 'X-Tenant-Id': tenantId },
      })
      .then((r) => r.data),

  // ─── Revoke certificate ──────────────────────────────────────────────────────
  revokeCertificate: (id: string, tenantId: string) =>
    apiClient
      .post<{ status: string; message: string }>(
        `${BASE}/${id}/revoke`,
        {},
        {
          headers: { 'X-Tenant-Id': tenantId },
        },
      )
      .then((r) => r.data),

  // ─── Delete certificate ──────────────────────────────────────────────────────
  deleteCertificate: (id: string, tenantId: string) =>
    apiClient
      .delete<{ status: string; message: string }>(`${BASE}/${id}`, {
        headers: { 'X-Tenant-Id': tenantId },
      })
      .then((r) => r.data),

  // ─── Certificate Groups ──────────────────────────────────────────────────────

  /** Paginated list of certificate groups */
  listGroups: (params: {
    tenantId: string
    status?: string
    page?: number
    size?: number
    sortBy?: string
    sortDir?: 'ASC' | 'DESC'
  }) =>
    apiClient
      .get<Page<CertGroupDto>>(GROUP_BASE, {
        params: {
          status: params.status,
          page: params.page ?? 0,
          size: params.size ?? 20,
          sortBy: params.sortBy ?? 'createdAt',
          sortDir: params.sortDir ?? 'DESC',
        },
        headers: { 'X-Tenant-Id': params.tenantId },
      })
      .then((r) => r.data),

  /** Get a single group with its member list */
  getGroup: (id: string, tenantId: string) =>
    apiClient
      .get<CertGroupDto>(`${GROUP_BASE}/${id}`, {
        headers: { 'X-Tenant-Id': tenantId },
      })
      .then((r) => r.data),

  /** Create a new certificate group */
  createGroup: (tenantId: string, request: CreateCertGroupRequest) =>
    apiClient
      .post<{ status: string; message: string }>(GROUP_BASE, request, {
        headers: { 'X-Tenant-Id': tenantId },
      })
      .then((r) => r.data),

  /** Update an existing group's alias / description */
  updateGroup: (id: string, tenantId: string, request: UpdateCertGroupRequest) =>
    apiClient
      .put<{ status: string; message: string }>(`${GROUP_BASE}/${id}`, request, {
        headers: { 'X-Tenant-Id': tenantId },
      })
      .then((r) => r.data),

  /** Archive a group (ACTIVE → ARCHIVED) */
  archiveGroup: (id: string, tenantId: string) =>
    apiClient
      .post<{ status: string; message: string }>(
        `${GROUP_BASE}/${id}/archive`,
        {},
        {
          headers: { 'X-Tenant-Id': tenantId },
        },
      )
      .then((r) => r.data),

  /** Delete a group (detaches all member certs) */
  deleteGroup: (id: string, tenantId: string) =>
    apiClient
      .delete<{ status: string; message: string }>(`${GROUP_BASE}/${id}`, {
        headers: { 'X-Tenant-Id': tenantId },
      })
      .then((r) => r.data),

  /** List all member certificates of a group */
  listGroupMembers: (groupId: string, tenantId: string) =>
    apiClient
      .get<CertificateDto[]>(`${GROUP_BASE}/${groupId}/members`, {
        headers: { 'X-Tenant-Id': tenantId },
      })
      .then((r) => r.data),
}
