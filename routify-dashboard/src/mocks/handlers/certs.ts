import { http, HttpResponse, delay } from 'msw'
import { certs, certGroups, buildPage, MOCK_TENANT_ID } from '../db'
import type {
  CertificateDto,
  CertGroupDto,
  UploadCertificateRequest,
  CreateCertGroupRequest,
  UpdateCertGroupRequest,
} from '../../types'

const CERT_BASE = '/api/v1/admin/certificates'
const GROUP_BASE = '/api/v1/admin/cert-groups'

function genCertId() {
  return `ffffffff-mock-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`
}
function genGroupId() {
  return `eeeeeeee-mock-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`
}

export const certHandlers = [
  // ─── List certificates ────────────────────────────────────────────────────────
  http.get(CERT_BASE, async ({ request }) => {
    await delay(200)
    const url = new URL(request.url)
    const page = parseInt(url.searchParams.get('page') ?? '0', 10)
    const size = parseInt(url.searchParams.get('size') ?? '20', 10)
    const status = url.searchParams.get('status')
    let items = Array.from(certs.values())
    if (status) items = items.filter((c) => c.status === status)
    items = items.sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    return HttpResponse.json(buildPage(items, page, size))
  }),

  // ─── Get single certificate ───────────────────────────────────────────────────
  http.get(`${CERT_BASE}/:id`, async ({ params }) => {
    await delay(150)
    const cert = certs.get(params.id as string)
    if (!cert) return HttpResponse.json({ status: 404, detail: 'Certificate not found' }, { status: 404 })
    return HttpResponse.json(cert)
  }),

  // ─── Vault stats ──────────────────────────────────────────────────────────────
  http.get(`${CERT_BASE}/stats`, async () => {
    await delay(150)
    const all = Array.from(certs.values())
    return HttpResponse.json({
      total: all.length,
      active: all.filter((c) => c.status === 'ACTIVE').length,
      expiringSoon: all.filter((c) => c.expiryStatus === 'EXPIRING_SOON').length,
      counts: {
        ACTIVE: all.filter((c) => c.status === 'ACTIVE').length,
        REVOKED: all.filter((c) => c.status === 'REVOKED').length,
        EXPIRED: all.filter((c) => c.status === 'EXPIRED').length,
        DELETED: all.filter((c) => c.status === 'DELETED').length,
      },
    })
  }),

  // ─── Upload certificate ───────────────────────────────────────────────────────
  http.post(CERT_BASE, async ({ request }) => {
    await delay(600)
    const tenantId = request.headers.get('X-Tenant-Id') || MOCK_TENANT_ID
    const body = (await request.json()) as UploadCertificateRequest
    const now = new Date().toISOString()
    const cert: CertificateDto = {
      id: genCertId(),
      tenantId,
      logicalId: `cert-${Date.now()}`,
      alias: body.alias,
      description: body.description,
      format: body.format,
      status: 'ACTIVE',
      expiryStatus: 'VALID',
      subjectDn: 'CN=mock.cert.demo',
      issuerDn: 'CN=Mock CA',
      serialNumber: Date.now().toString(16),
      notBefore: now,
      notAfter: '2028-01-01T00:00:00Z',
      signatureAlg: 'SHA256withRSA',
      keyAlgorithm: 'RSA',
      keySize: 2048,
      isCa: false,
      hasPrivateKey: !!body.privateKey,
      groupId: body.groupId,
      memberAlias: body.memberAlias,
      uploadedBy: 'admin',
      createdAt: now,
      updatedAt: now,
      expiresAt: '2028-01-01T00:00:00Z',
    }
    certs.set(cert.id, cert)
    // Update group memberCount
    if (body.groupId) {
      const grp = certGroups.get(body.groupId)
      if (grp) certGroups.set(grp.id, { ...grp, memberCount: grp.memberCount + 1 })
    }
    return HttpResponse.json({ status: 'ACCEPTED', message: 'Certificate upload accepted.' })
  }),

  // ─── Revoke certificate ───────────────────────────────────────────────────────
  http.post(`${CERT_BASE}/:id/revoke`, async ({ params }) => {
    await delay(400)
    const cert = certs.get(params.id as string)
    if (!cert) return HttpResponse.json({ status: 404, detail: 'Certificate not found' }, { status: 404 })
    certs.set(cert.id, { ...cert, status: 'REVOKED', updatedAt: new Date().toISOString() })
    return HttpResponse.json({ status: 'ACCEPTED', message: 'Certificate revoke accepted.' })
  }),

  // ─── Delete certificate ───────────────────────────────────────────────────────
  http.delete(`${CERT_BASE}/:id`, async ({ params }) => {
    await delay(350)
    const cert = certs.get(params.id as string)
    if (!cert) return HttpResponse.json({ status: 404, detail: 'Certificate not found' }, { status: 404 })
    certs.delete(cert.id)
    return HttpResponse.json({ status: 'ACCEPTED', message: 'Certificate deleted.' })
  }),

  // ─── List cert groups ─────────────────────────────────────────────────────────
  http.get(GROUP_BASE, async ({ request }) => {
    await delay(200)
    const url = new URL(request.url)
    const page = parseInt(url.searchParams.get('page') ?? '0', 10)
    const size = parseInt(url.searchParams.get('size') ?? '20', 10)
    const status = url.searchParams.get('status')
    let items = Array.from(certGroups.values())
    if (status) items = items.filter((g) => g.status === status)
    items = items.sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    return HttpResponse.json(buildPage(items, page, size))
  }),

  // ─── Get single group ─────────────────────────────────────────────────────────
  http.get(`${GROUP_BASE}/:id`, async ({ params }) => {
    await delay(150)
    const grp = certGroups.get(params.id as string)
    if (!grp) return HttpResponse.json({ status: 404, detail: 'Cert group not found' }, { status: 404 })
    const members = Array.from(certs.values()).filter((c) => c.groupId === grp.id)
    return HttpResponse.json({ ...grp, members })
  }),

  // ─── Create group ─────────────────────────────────────────────────────────────
  http.post(GROUP_BASE, async ({ request }) => {
    await delay(400)
    const tenantId = request.headers.get('X-Tenant-Id') || MOCK_TENANT_ID
    const body = (await request.json()) as CreateCertGroupRequest
    const now = new Date().toISOString()
    const grp: CertGroupDto = {
      id: genGroupId(),
      tenantId,
      logicalId: body.logicalId,
      alias: body.alias,
      description: body.description,
      status: 'ACTIVE',
      memberCount: 0,
      expiryHealthStatus: 'VALID',
      createdBy: 'admin',
      createdAt: now,
      updatedAt: now,
    }
    certGroups.set(grp.id, grp)
    return HttpResponse.json({ status: 'ACCEPTED', message: 'Certificate group created.' })
  }),

  // ─── Update group ─────────────────────────────────────────────────────────────
  http.put(`${GROUP_BASE}/:id`, async ({ params, request }) => {
    await delay(350)
    const grp = certGroups.get(params.id as string)
    if (!grp) return HttpResponse.json({ status: 404, detail: 'Cert group not found' }, { status: 404 })
    const body = (await request.json()) as UpdateCertGroupRequest
    const updated = { ...grp, ...body, updatedAt: new Date().toISOString() }
    certGroups.set(grp.id, updated)
    return HttpResponse.json({ status: 'ACCEPTED', message: 'Certificate group updated.' })
  }),

  // ─── Archive group ────────────────────────────────────────────────────────────
  http.post(`${GROUP_BASE}/:id/archive`, async ({ params }) => {
    await delay(350)
    const grp = certGroups.get(params.id as string)
    if (!grp) return HttpResponse.json({ status: 404, detail: 'Cert group not found' }, { status: 404 })
    certGroups.set(grp.id, { ...grp, status: 'ARCHIVED', updatedAt: new Date().toISOString() })
    return HttpResponse.json({ status: 'ACCEPTED', message: 'Certificate group archived.' })
  }),

  // ─── Delete group ─────────────────────────────────────────────────────────────
  http.delete(`${GROUP_BASE}/:id`, async ({ params }) => {
    await delay(350)
    if (!certGroups.has(params.id as string))
      return HttpResponse.json({ status: 404, detail: 'Cert group not found' }, { status: 404 })
    certGroups.delete(params.id as string)
    return new HttpResponse(null, { status: 204 })
  }),

  // ─── List group members ───────────────────────────────────────────────────────
  http.get(`${GROUP_BASE}/:groupId/members`, async ({ params }) => {
    await delay(150)
    const members = Array.from(certs.values()).filter((c) => c.groupId === params.groupId)
    return HttpResponse.json(members)
  }),
]
