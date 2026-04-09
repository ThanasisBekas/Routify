import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useMutation } from '@tanstack/react-query'
import { X, Loader2, ShieldCheck } from 'lucide-react'
import { certVaultApi } from '../../api/certVaultApi'
import { extractApiError } from '../../lib/utils'
import type { AcmeProvider } from '../../types'
import { toast } from 'sonner'

const schema = z.object({
  email: z.string().email('Valid email is required'),
  provider: z.enum(['LETSENCRYPT', 'LETSENCRYPT_STAGING', 'ZEROSSSL']),
})

type FormValues = z.infer<typeof schema>

export default function AcmeSetupModal({
  tenantId,
  onClose,
  onSuccess,
}: {
  tenantId: string
  onClose: () => void
  onSuccess: () => void
}) {
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { email: '', provider: 'LETSENCRYPT' },
  })

  const mutation = useMutation({
    mutationFn: (values: FormValues) =>
      certVaultApi.registerAcmeAccount(tenantId, {
        email: values.email,
        provider: values.provider as AcmeProvider,
      }),
    onSuccess: () => {
      toast.success('ACME account registered successfully')
      onSuccess()
    },
    onError: (err) => {
      toast.error(extractApiError(err))
    },
  })

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm animate-fade-in">
      <div className="relative w-full max-w-md rounded-2xl border border-white/10 bg-[#0e1017] p-6 shadow-2xl">
        <button
          onClick={onClose}
          className="absolute top-4 right-4 p-1.5 rounded-md text-gray-500 hover:text-white hover:bg-white/6 transition-colors"
        >
          <X className="w-4 h-4" />
        </button>

        <div className="flex items-center gap-3 mb-6">
          <div className="w-10 h-10 rounded-xl bg-emerald-500/10 border border-emerald-500/20 flex items-center justify-center">
            <ShieldCheck className="w-5 h-5 text-emerald-400" />
          </div>
          <div>
            <h2 className="text-base font-bold text-white">Register ACME Account</h2>
            <p className="text-xs text-gray-500">Set up automated certificate management</p>
          </div>
        </div>

        <form onSubmit={handleSubmit((v) => mutation.mutate(v))} className="space-y-4">
          <div>
            <label className="block text-xs font-medium text-gray-400 mb-1.5" htmlFor="field-contact-email-0">
              Contact Email
            </label>
            <input
              id="field-contact-email-0"
              {...register('email')}
              type="email"
              placeholder="admin@example.com"
              className="w-full px-3 py-2 rounded-lg bg-white/5 border border-white/10 text-sm text-white placeholder-gray-600 focus:border-emerald-500/50 focus:ring-1 focus:ring-emerald-500/30 outline-none transition-all"
            />
            {errors.email && <p className="text-xs text-red-400 mt-1">{errors.email.message}</p>}
          </div>

          <div>
            <label className="block text-xs font-medium text-gray-400 mb-1.5" htmlFor="field-ca-provider-1">
              CA Provider
            </label>
            <select
              id="field-ca-provider-1"
              {...register('provider')}
              className="w-full px-3 py-2 rounded-lg bg-white/5 border border-white/10 text-sm text-white focus:border-emerald-500/50 focus:ring-1 focus:ring-emerald-500/30 outline-none transition-all"
            >
              <option value="LETSENCRYPT">Let&apos;s Encrypt</option>
              <option value="LETSENCRYPT_STAGING">Let&apos;s Encrypt (Staging — test only)</option>
              <option value="ZEROSSSL">ZeroSSL</option>
            </select>
          </div>

          <div className="flex justify-end gap-2 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 rounded-lg text-sm font-medium text-gray-400 bg-white/5 hover:bg-white/8 border border-white/10 transition-all"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={mutation.isPending}
              className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-semibold text-white bg-emerald-600 hover:bg-emerald-500 disabled:opacity-50 transition-all shadow-lg shadow-emerald-500/20"
            >
              {mutation.isPending && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
              Register Account
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
