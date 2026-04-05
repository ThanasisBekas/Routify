/**
 * CertVaultPage — thin shell that renders CertGroupsPage.
 *
 * The Certificate Vault is now entirely group-centric:
 * - Users create a group with a logical ID (the stable gateway TLS key)
 * - They upload certificates directly into the group
 * - The gateway always loads certs via the group's logical ID
 *
 * All UI logic lives in CertGroupsPage.
 */
import CertGroupsPage from './CertGroupsPage'
import { useDocumentTitle } from '../../hooks/useDocumentTitle'

export default function CertVaultPage() {
  useDocumentTitle('Certificate Vault')
  return <CertGroupsPage />
}
