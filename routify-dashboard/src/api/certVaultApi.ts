import apiClient from './client'
import type {
  CertificateDto,
  CertGroupDto,
  CertVaultStats,
  CertStatus,
  CertLogicalIdEntry,
  CreateCertGroupRequest,
  UpdateCertGroupRequest,
  UploadCertificateRequest,
  Page,
  AcmeAccountDto,
  AcmeOrderDto,
  AcmeOrdersPage,
  RegisterAcmeAccountRequest,
  IssueAcmeCertificateRequest,
} from '../types'

const BASE = '/api/v1/admin/certificates'
const GROUP_BASE = '/api/v1/admin/cert-groups'
const ACME_BASE = '/api/v1/admin/certs/acme'

export const certVaultApi = {
  // ─── Logical ID picker (lightweight) ──────────────────────────────────────────
  /** Fetches cert-group logical IDs for filter config pickers. */
  listLogicalIds: () => apiClient.get<CertLogicalIdEntry[]>(`${BASE}/logical-ids`).then((r) => r.data),

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

  // ─── ACME (Automated Certificate Lifecycle) ──────────────────────────────

  /** Register an ACME account with a CA provider */
  registerAcmeAccount: (tenantId: string, request: RegisterAcmeAccountRequest) =>
    apiClient
      .post<AcmeAccountDto>(`${ACME_BASE}/register`, request, {
        headers: { 'X-Tenant-Id': tenantId },
      })
      .then((r) => r.data),

  /** Request a certificate for a domain via ACME */
  issueAcmeCertificate: (tenantId: string, request: IssueAcmeCertificateRequest) =>
    apiClient
      .post<AcmeOrderDto>(`${ACME_BASE}/issue`, request, {
        headers: { 'X-Tenant-Id': tenantId },
      })
      .then((r) => r.data),

  /** List ACME orders (paginated) */
  listAcmeOrders: (params: { tenantId: string; page?: number; size?: number }) =>
    apiClient
      .get<AcmeOrdersPage>(`${ACME_BASE}/orders`, {
        params: { page: params.page ?? 0, size: params.size ?? 20 },
        headers: { 'X-Tenant-Id': params.tenantId },
      })
      .then((r) => r.data),

  /** Get a single ACME order */
  getAcmeOrder: (id: string, tenantId: string) =>
    apiClient
      .get<AcmeOrderDto>(`${ACME_BASE}/orders/${id}`, {
        headers: { 'X-Tenant-Id': tenantId },
      })
      .then((r) => r.data),

  /** Trigger manual renewal of an ACME order */
  renewAcmeCertificate: (id: string, tenantId: string) =>
    apiClient
      .post<AcmeOrderDto>(
        `${ACME_BASE}/orders/${id}/renew`,
        {},
        {
          headers: { 'X-Tenant-Id': tenantId },
        },
      )
      .then((r) => r.data),
}
