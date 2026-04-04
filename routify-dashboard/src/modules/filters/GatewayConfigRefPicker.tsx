/**
 * @deprecated GatewayConfigRefPicker has been removed as part of the filter
 * isolation refactor (April 2026).
 *
 * ARCHITECTURAL RULE:
 *   Standard filters are self-contained and must NOT import configuration from
 *   the API gateway config. The ONLY exception is cert filters, which bind to
 *   a Certificate Group in the Cert Vault.
 *
 * Migration:
 *   - Replace all usages of `GatewayConfigRefPicker` with `CertVaultGroupPicker`
 *     from `./CertVaultGroupPicker`.
 *   - Only render `CertVaultGroupPicker` for filter types in
 *     `FILTER_TYPES_WITH_CERT_VAULT_REF` (AUTH_CERT_VAULT, CERT_ROTATION,
 *     CERT_VAULT_EXPIRY_CHECK).
 *   - For all other filter types, remove the picker entirely — they are
 *     self-contained.
 *
 * This file is kept temporarily so import errors surface clearly during the
 * migration. It will be deleted in the next cleanup pass.
 */

export { default } from './CertVaultGroupPicker'

