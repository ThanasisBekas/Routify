import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Shield, Plus, Pencil, Trash2, Lock } from 'lucide-react'
import { toast } from 'sonner'
import { rolesApi } from '../../api/rolesApi'
import { useDocumentTitle } from '../../hooks/useDocumentTitle'
import { useAuthStore } from '../../store/authStore'
import { extractApiError } from '../../lib/utils'
import RoleFormModal from './RoleFormModal'
import type { RoleDefinitionDto } from '../../types'

export default function RolesPage() {
  useDocumentTitle('Roles')
  const user = useAuthStore((s) => s.user)
  const queryClient = useQueryClient()
  const [editingRole, setEditingRole] = useState<RoleDefinitionDto | null>(null)
  const [showCreate, setShowCreate] = useState(false)

  const canWrite = user?.role === 'SUPER_ADMIN' || user?.role === 'TENANT_ADMIN'

  const { data, isLoading } = useQuery({
    queryKey: ['roles'],
    queryFn: () => rolesApi.list({ page: 0, size: 50 }),
  })

  const deleteMutation = useMutation({
    mutationFn: (id: string) => rolesApi.delete(id),
    onSuccess: () => {
      toast.success('Role deleted')
      queryClient.invalidateQueries({ queryKey: ['roles'] })
    },
    onError: (err) => toast.error(extractApiError(err)),
  })

  const roles = data?.content ?? []

  return (
    <div className="flex-1 overflow-y-auto p-6">
      {/* Header */}
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-xl font-bold text-white flex items-center gap-2">
            <Shield className="w-5 h-5 text-indigo-400" />
            Roles & Permissions
          </h1>
          <p className="text-sm text-gray-500 mt-1">
            Manage role definitions and their permission sets
          </p>
        </div>
        {canWrite && (
          <button
            onClick={() => setShowCreate(true)}
            className="flex items-center gap-2 px-4 py-2 bg-indigo-500/20 text-indigo-300 rounded-lg text-sm font-medium hover:bg-indigo-500/30 transition-colors border border-indigo-500/20"
          >
            <Plus className="w-4 h-4" />
            Create Role
          </button>
        )}
      </div>

      {/* Table */}
      {isLoading ? (
        <div className="flex justify-center py-12">
          <div className="w-6 h-6 border-2 border-indigo-500/30 border-t-indigo-500 rounded-full animate-spin" />
        </div>
      ) : (
        <div className="bg-white/[0.02] border border-white/[0.06] rounded-xl overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-white/[0.06]">
                <th className="text-left px-4 py-3 text-gray-500 font-medium">Name</th>
                <th className="text-left px-4 py-3 text-gray-500 font-medium">Description</th>
                <th className="text-left px-4 py-3 text-gray-500 font-medium">Type</th>
                <th className="text-center px-4 py-3 text-gray-500 font-medium">Permissions</th>
                <th className="text-right px-4 py-3 text-gray-500 font-medium">Actions</th>
              </tr>
            </thead>
            <tbody>
              {roles.map((role) => (
                <tr
                  key={role.id}
                  className="border-b border-white/[0.04] hover:bg-white/[0.02] transition-colors"
                >
                  <td className="px-4 py-3">
                    <span className="text-white font-medium">{role.name}</span>
                  </td>
                  <td className="px-4 py-3 text-gray-400 max-w-xs truncate">
                    {role.description || '—'}
                  </td>
                  <td className="px-4 py-3">
                    {role.builtIn ? (
                      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-amber-500/10 text-amber-400 text-xs font-medium border border-amber-500/20">
                        <Lock className="w-3 h-3" />
                        Built-in
                      </span>
                    ) : (
                      <span className="px-2 py-0.5 rounded-full bg-indigo-500/10 text-indigo-300 text-xs font-medium border border-indigo-500/20">
                        Custom
                      </span>
                    )}
                  </td>
                  <td className="px-4 py-3 text-center">
                    <span className="text-gray-300 font-mono text-xs bg-white/[0.04] px-2 py-0.5 rounded">
                      {role.permissions.length}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-right">
                    <div className="flex items-center justify-end gap-1">
                      {canWrite && (
                        <button
                          onClick={() => setEditingRole(role)}
                          className="p-1.5 text-gray-500 hover:text-indigo-400 hover:bg-indigo-500/10 rounded-md transition-colors"
                          title="Edit permissions"
                        >
                          <Pencil className="w-3.5 h-3.5" />
                        </button>
                      )}
                      {canWrite && !role.builtIn && (
                        <button
                          onClick={() => {
                            if (confirm(`Delete role "${role.name}"?`)) {
                              deleteMutation.mutate(role.id)
                            }
                          }}
                          className="p-1.5 text-gray-500 hover:text-red-400 hover:bg-red-500/10 rounded-md transition-colors"
                          title="Delete role"
                        >
                          <Trash2 className="w-3.5 h-3.5" />
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
              {roles.length === 0 && (
                <tr>
                  <td colSpan={5} className="px-4 py-8 text-center text-gray-500">
                    No roles found
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}

      {/* Create modal */}
      {showCreate && (
        <RoleFormModal onClose={() => setShowCreate(false)} />
      )}

      {/* Edit modal */}
      {editingRole && (
        <RoleFormModal role={editingRole} onClose={() => setEditingRole(null)} />
      )}
    </div>
  )
}

