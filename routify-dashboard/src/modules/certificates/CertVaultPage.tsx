/**
 * CertVaultPage — tabbed certificate vault with groups + ACME auto-renew.
 *
 * Tab 1: Certificate Groups (existing functionality)
 * Tab 2: ACME / Auto-Renew (new — automated certificate lifecycle)
 */
import { useState } from 'react'
import CertGroupsPage from './CertGroupsPage'
import AcmeTab from './AcmeTab'
import { useDocumentTitle } from '../../hooks/useDocumentTitle'
import { cn } from '../../lib/utils'
import { Layers, RefreshCw } from 'lucide-react'

type Tab = 'groups' | 'acme'

export default function CertVaultPage() {
  useDocumentTitle('Certificate Vault')
  const [activeTab, setActiveTab] = useState<Tab>('groups')

  const tabs: { key: Tab; label: string; icon: React.ReactNode }[] = [
    { key: 'groups', label: 'Certificate Groups', icon: <Layers className="w-3.5 h-3.5" /> },
    { key: 'acme', label: 'ACME / Auto-Renew', icon: <RefreshCw className="w-3.5 h-3.5" /> },
  ]

  return (
    <div className="flex flex-col h-full">
      {/* Tab bar */}
      <div className="flex items-center gap-1 px-6 pt-2 border-b border-white/6 bg-[#0c0e14]">
        {tabs.map((tab) => (
          <button
            key={tab.key}
            onClick={() => setActiveTab(tab.key)}
            className={cn(
              'flex items-center gap-1.5 px-3 py-2 text-xs font-medium rounded-t-lg border-b-2 transition-all -mb-px',
              activeTab === tab.key
                ? 'text-white border-indigo-500 bg-white/3'
                : 'text-gray-500 border-transparent hover:text-gray-300 hover:bg-white/2',
            )}
          >
            {tab.icon}
            {tab.label}
          </button>
        ))}
      </div>

      {/* Tab content */}
      <div className="flex-1 overflow-hidden">
        {activeTab === 'groups' ? <CertGroupsPage /> : <AcmeTab />}
      </div>
    </div>
  )
}
