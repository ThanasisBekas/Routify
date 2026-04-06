import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { X } from 'lucide-react'
import { apiKeysApi } from '../../api/apiKeysApi'
import { extractApiError } from '../../lib/utils'
import type { CreateApiKeyRequest, ApiKeyCreatedResponse } from '../../types'

interface Props {
  onClose: () => void
  onCreated: (result: ApiKeyCreatedResponse) => void
}

export default function CreateApiKeyModal({ onClose, onCreated }: Props) {
  const qc = useQueryClient()
  const { register, handleSubmit, formState: { errors } } = useForm<CreateApiKeyRequest>()

  const mutation = useMutation({
    mutationFn: (data: CreateApiKeyRequest) => apiKeysApi.create(data),
    onSuccess: (result) => {
      toast.success('API key created — copy it now!')
      qc.invalidateQueries({ queryKey: ['api-keys'] })
      onCreated(result)
    },
    onError: (err) => toast.error(extractApiError(err)),
  })

  const onSubmit = (data: CreateApiKeyRequest) => mutation.mutate(data)

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm">
      <div className="bg-[#0d0f14] border border-white/10 rounded-2xl w-full max-w-md p-6 shadow-2xl">
        <div className="flex items-center justify-between mb-5">
          <h2 className="text-lg font-bold text-white">Create API Key</h2>
          <button onClick={onClose} className="p-1 text-gray-500 hover:text-white transition-colors">
            <X className="w-5 h-5" />
          </button>
        </div>

        <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
          <div>
            <label className="block text-sm font-medium text-gray-300 mb-1">Name *</label>
            <input
              {...register('name', { required: 'Name is required' })}
              className="w-full px-3 py-2 bg-white/5 border border-white/10 rounded-lg text-white text-sm placeholder:text-gray-600 focus:outline-none focus:border-indigo-500/50"
              placeholder="e.g. CI Pipeline Key"
            />
            {errors.name && <p className="text-xs text-red-400 mt-1">{errors.name.message}</p>}
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-300 mb-1">Role</label>
            <select
              {...register('role')}
              className="w-full px-3 py-2 bg-white/5 border border-white/10 rounded-lg text-white text-sm focus:outline-none focus:border-indigo-500/50"
            >
              <option value="OPERATOR">Operator</option>
              <option value="VIEWER">Viewer</option>
              <option value="TENANT_ADMIN">Tenant Admin</option>
            </select>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-300 mb-1">Email (optional)</label>
            <input
              {...register('email')}
              type="email"
              className="w-full px-3 py-2 bg-white/5 border border-white/10 rounded-lg text-white text-sm placeholder:text-gray-600 focus:outline-none focus:border-indigo-500/50"
              placeholder="owner@example.com"
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-300 mb-1">Expires At (optional)</label>
            <input
              {...register('expiresAt')}
              type="datetime-local"
              className="w-full px-3 py-2 bg-white/5 border border-white/10 rounded-lg text-white text-sm focus:outline-none focus:border-indigo-500/50"
            />
          </div>

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
              disabled={mutation.isPending}
              className="px-4 py-2 bg-indigo-600 hover:bg-indigo-500 text-white text-sm font-medium rounded-lg transition-colors disabled:opacity-50"
            >
              {mutation.isPending ? 'Creating…' : 'Create Key'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

