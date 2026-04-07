import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { X } from 'lucide-react'
import { toast } from 'sonner'
import { rolesApi } from '../../api/rolesApi'
import { extractApiError, cn } from '../../lib/utils'
import { PERMISSION_GROUPS } from '../../types'
import type { RoleDefinitionDto, Permission } from '../../types'

const roleSchema = z.object({
  name: z.string().min(1, 'Name is required').max(100),
  description: z.string().max(500).optional(),
  permissions: z.array(z.string()).min(1, 'At least one permission is required'),
})

type RoleFormValues = z.infer<typeof roleSchema>

interface Props {
  role?: RoleDefinitionDto
  onClose: () => void
}

export default function RoleFormModal({ role, onClose }: Props) {
  const queryClient = useQueryClient()
  const isEdit = !!role

  const {
    register,
    handleSubmit,
    watch,
    setValue,
    formState: { errors },
  } = useForm<RoleFormValues>({
    resolver: zodResolver(roleSchema),
    defaultValues: {
      name: role?.name ?? '',
      description: role?.description ?? '',
      permissions: role?.permissions ?? [],
    },
  })

  const selectedPermissions = watch('permissions') as Permission[]

  const createMutation = useMutation({
    mutationFn: (data: RoleFormValues) => rolesApi.create(data as { name: string; permissions: Permission[] }),
    onSuccess: () => {
      toast.success('Role created')
      queryClient.invalidateQueries({ queryKey: ['roles'] })
      onClose()
    },
    onError: (err) => toast.error(extractApiError(err)),
  })

  const updateMutation = useMutation({
    mutationFn: (data: RoleFormValues) => rolesApi.update(role!.id, data as { permissions: Permission[] }),
    onSuccess: () => {
      toast.success('Role updated')
      queryClient.invalidateQueries({ queryKey: ['roles'] })
      onClose()
    },
    onError: (err) => toast.error(extractApiError(err)),
  })

  const onSubmit = (data: RoleFormValues) => {
    if (isEdit) {
      updateMutation.mutate(data)
    } else {
      createMutation.mutate(data)
    }
  }

  const togglePermission = (perm: Permission) => {
    const current = selectedPermissions
    if (current.includes(perm)) {
      setValue(
        'permissions',
        current.filter((p) => p !== perm),
        { shouldValidate: true },
      )
    } else {
      setValue('permissions', [...current, perm], { shouldValidate: true })
    }
  }

  const toggleGroup = (perms: Permission[]) => {
    const allSelected = perms.every((p) => selectedPermissions.includes(p))
    if (allSelected) {
      setValue(
        'permissions',
        selectedPermissions.filter((p) => !perms.includes(p as Permission)),
        { shouldValidate: true },
      )
    } else {
      const merged = [...new Set([...selectedPermissions, ...perms])]
      setValue('permissions', merged, { shouldValidate: true })
    }
  }

  const isSubmitting = createMutation.isPending || updateMutation.isPending

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm">
      <div className="w-full max-w-2xl bg-[#0d0f14] border border-white/10 rounded-2xl shadow-2xl overflow-hidden">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-white/[0.06]">
          <h2 className="text-lg font-semibold text-white">
            {isEdit ? `Edit Role: ${role.name}` : 'Create Custom Role'}
          </h2>
          <button onClick={onClose} className="p-1 text-gray-500 hover:text-white transition-colors">
            <X className="w-5 h-5" />
          </button>
        </div>

        <form onSubmit={handleSubmit(onSubmit)} className="p-6 space-y-5 max-h-[70vh] overflow-y-auto">
          {/* Name */}
          {!role?.builtIn && (
            <div>
              <label className="block text-sm font-medium text-gray-300 mb-1">Name</label>
              <input
                {...register('name')}
                disabled={role?.builtIn}
                className="w-full px-3 py-2 bg-white/[0.04] border border-white/10 rounded-lg text-white text-sm placeholder-gray-600 focus:outline-none focus:border-indigo-500/50"
                placeholder="e.g. CertManager"
              />
              {errors.name && <p className="text-red-400 text-xs mt-1">{errors.name.message}</p>}
            </div>
          )}

          {/* Description */}
          <div>
            <label className="block text-sm font-medium text-gray-300 mb-1">Description</label>
            <input
              {...register('description')}
              className="w-full px-3 py-2 bg-white/[0.04] border border-white/10 rounded-lg text-white text-sm placeholder-gray-600 focus:outline-none focus:border-indigo-500/50"
              placeholder="Short description of this role"
            />
          </div>

          {/* Permissions */}
          <div>
            <label className="block text-sm font-medium text-gray-300 mb-3">
              Permissions ({selectedPermissions.length} selected)
            </label>
            {errors.permissions && <p className="text-red-400 text-xs mb-2">{errors.permissions.message}</p>}

            <div className="space-y-4">
              {Object.entries(PERMISSION_GROUPS).map(([group, perms]) => {
                const allSelected = perms.every((p) => selectedPermissions.includes(p))
                const someSelected = perms.some((p) => selectedPermissions.includes(p))
                return (
                  <div key={group} className="bg-white/[0.02] border border-white/[0.06] rounded-lg p-3">
                    <label className="flex items-center gap-2 cursor-pointer mb-2">
                      <input
                        type="checkbox"
                        checked={allSelected}
                        ref={undefined}
                        onChange={() => toggleGroup(perms)}
                        className="w-3.5 h-3.5 rounded border-white/20 bg-white/[0.04] text-indigo-500 focus:ring-0 focus:ring-offset-0"
                      />
                      <span
                        className={cn(
                          'text-sm font-semibold',
                          allSelected ? 'text-indigo-300' : someSelected ? 'text-gray-300' : 'text-gray-500',
                        )}
                      >
                        {group}
                      </span>
                    </label>
                    <div className="grid grid-cols-2 gap-1 ml-5">
                      {perms.map((perm) => (
                        <label key={perm} className="flex items-center gap-2 cursor-pointer py-0.5">
                          <input
                            type="checkbox"
                            checked={selectedPermissions.includes(perm)}
                            onChange={() => togglePermission(perm)}
                            className="w-3 h-3 rounded border-white/20 bg-white/[0.04] text-indigo-500 focus:ring-0 focus:ring-offset-0"
                          />
                          <span className="text-xs text-gray-400 font-mono">{perm}</span>
                        </label>
                      ))}
                    </div>
                  </div>
                )
              })}
            </div>
          </div>

          {/* Actions */}
          <div className="flex justify-end gap-3 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 text-sm text-gray-400 hover:text-white transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={isSubmitting}
              className="px-4 py-2 bg-indigo-500 text-white rounded-lg text-sm font-medium hover:bg-indigo-600 disabled:opacity-50 transition-colors"
            >
              {isSubmitting ? 'Saving…' : isEdit ? 'Update Role' : 'Create Role'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
