import React, { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation } from '@tanstack/react-query'
import { Zap, KeyRound, Eye, EyeOff, AlertCircle, CheckCircle2, Loader2, ShieldAlert } from 'lucide-react'
import { authApi } from '../../api/authApi'
import { useAuthStore } from '../../store/authStore'
import { useDocumentTitle } from '../../hooks/useDocumentTitle'

interface Props {
  /** When true the user is forced here and cannot navigate away until password is changed. */
  forced?: boolean
}

export default function ChangePasswordPage({ forced = false }: Props) {
  useDocumentTitle('Change Password')
  const navigate = useNavigate()
  const { user, setUser, logout } = useAuthStore()

  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [showCurrent, setShowCurrent] = useState(false)
  const [showNew, setShowNew] = useState(false)
  const [showConfirm, setShowConfirm] = useState(false)
  const [success, setSuccess] = useState(false)

  const mutation = useMutation({
    mutationFn: () => {
      if (!user?.id) throw new Error('Not authenticated')
      return authApi.changePassword(user.id, currentPassword, newPassword)
    },
    onSuccess: () => {
      // Clear the mustChangePassword flag in the local store
      if (user) setUser({ ...user, mustChangePassword: false })
      setSuccess(true)
      setTimeout(() => navigate('/routes', { replace: true }), 1500)
    },
  })

  const passwordsMatch = newPassword === confirmPassword
  const newPasswordLong = newPassword.length >= 8

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!passwordsMatch || !newPasswordLong) return
    mutation.mutate()
  }

  const handleCancelOrLogout = () => {
    if (forced) {
      logout()
      navigate('/login', { replace: true })
    } else {
      navigate(-1)
    }
  }

  const inputCls =
    'w-full bg-white/[0.05] border border-white/[0.09] rounded-xl px-4 py-3 text-sm text-white ' +
    'placeholder-gray-600 focus:outline-none focus:border-indigo-500 focus:ring-2 focus:ring-indigo-500/20 transition-all pr-12'

  return (
    <div className="min-h-screen flex items-center justify-center bg-[#080a0f] p-4">
      <div className="w-full max-w-[420px]">
        {/* Logo */}
        <div className="flex items-center gap-3 justify-center mb-8">
          <div className="relative w-9 h-9">
            <div className="absolute inset-0 rounded-xl bg-gradient-to-br from-indigo-500 to-purple-600" />
            <div className="absolute inset-0 rounded-xl flex items-center justify-center">
              <Zap className="w-4 h-4 text-white" strokeWidth={2.5} />
            </div>
          </div>
          <span className="text-lg font-bold text-white">Routify</span>
        </div>

        <div className="bg-[#0c0e14] border border-white/[0.08] rounded-2xl shadow-2xl overflow-hidden">
          {/* Header */}
          <div className="px-6 pt-6 pb-4 border-b border-white/[0.06]">
            <div className="flex items-center gap-3 mb-3">
              <div
                className={`w-9 h-9 rounded-xl flex items-center justify-center border ${
                  forced ? 'bg-amber-500/15 border-amber-500/30' : 'bg-indigo-500/15 border-indigo-500/30'
                }`}
              >
                {forced ? (
                  <ShieldAlert className="w-4.5 h-4.5 text-amber-400" />
                ) : (
                  <KeyRound className="w-4.5 h-4.5 text-indigo-400" />
                )}
              </div>
              <div>
                <h1 className="text-sm font-bold text-white">
                  {forced ? 'Password Change Required' : 'Change Password'}
                </h1>
                <p className="text-xs text-gray-500 mt-0.5">
                  {forced ? 'You must set a new password before continuing.' : 'Update your account password.'}
                </p>
              </div>
            </div>

            {forced && (
              <div className="flex items-start gap-2.5 p-3 bg-amber-500/[0.08] border border-amber-500/20 rounded-xl mt-2">
                <AlertCircle className="w-4 h-4 text-amber-400 mt-0.5 shrink-0" />
                <p className="text-xs text-amber-300 leading-relaxed">
                  Your administrator has required you to change your password. You cannot access the dashboard until
                  this is complete.
                </p>
              </div>
            )}
          </div>

          {/* Form */}
          <form onSubmit={handleSubmit} className="p-6 space-y-4">
            {/* Current password */}
            <div className="space-y-1.5">
              {/* eslint-disable-next-line jsx-a11y/label-has-associated-control */}
              <label className="block text-xs font-semibold text-gray-400 uppercase tracking-wider">
                Current Password
              </label>
              <div className="relative">
                <input
                  type={showCurrent ? 'text' : 'password'}
                  required
                  // eslint-disable-next-line jsx-a11y/no-autofocus
                  autoFocus
                  value={currentPassword}
                  onChange={(e) => setCurrentPassword(e.target.value)}
                  className={inputCls}
                  placeholder="••••••••"
                />
                <button
                  type="button"
                  onClick={() => setShowCurrent((v) => !v)}
                  className="absolute right-3 top-1/2 -translate-y-1/2 p-1 text-gray-500 hover:text-gray-300 transition-colors"
                >
                  {showCurrent ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                </button>
              </div>
            </div>

            {/* New password */}
            <div className="space-y-1.5">
              {/* eslint-disable-next-line jsx-a11y/label-has-associated-control */}
              <label className="block text-xs font-semibold text-gray-400 uppercase tracking-wider">New Password</label>
              <div className="relative">
                <input
                  type={showNew ? 'text' : 'password'}
                  required
                  value={newPassword}
                  onChange={(e) => setNewPassword(e.target.value)}
                  className={inputCls}
                  placeholder="Min 8 characters"
                />
                <button
                  type="button"
                  onClick={() => setShowNew((v) => !v)}
                  className="absolute right-3 top-1/2 -translate-y-1/2 p-1 text-gray-500 hover:text-gray-300 transition-colors"
                >
                  {showNew ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                </button>
              </div>
              {newPassword && !newPasswordLong && (
                <p className="text-xs text-amber-400">Password must be at least 8 characters</p>
              )}
            </div>

            {/* Confirm password */}
            <div className="space-y-1.5">
              {/* eslint-disable-next-line jsx-a11y/label-has-associated-control */}
              <label className="block text-xs font-semibold text-gray-400 uppercase tracking-wider">
                Confirm New Password
              </label>
              <div className="relative">
                <input
                  type={showConfirm ? 'text' : 'password'}
                  required
                  value={confirmPassword}
                  onChange={(e) => setConfirmPassword(e.target.value)}
                  className={inputCls}
                  placeholder="••••••••"
                />
                <button
                  type="button"
                  onClick={() => setShowConfirm((v) => !v)}
                  className="absolute right-3 top-1/2 -translate-y-1/2 p-1 text-gray-500 hover:text-gray-300 transition-colors"
                >
                  {showConfirm ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                </button>
              </div>
              {confirmPassword && !passwordsMatch && <p className="text-xs text-red-400">Passwords do not match</p>}
            </div>

            {/* API error */}
            {mutation.isError && (
              <div className="flex items-start gap-2.5 p-3.5 bg-red-500/[0.08] border border-red-500/20 rounded-xl">
                <AlertCircle className="w-4 h-4 text-red-400 mt-0.5 shrink-0" />
                <p className="text-sm text-red-300">
                  {(mutation.error as { response?: { data?: { error?: string; detail?: string } } })?.response?.data
                    ?.error ??
                    (mutation.error as { response?: { data?: { detail?: string } } })?.response?.data?.detail ??
                    'Failed to change password. Please try again.'}
                </p>
              </div>
            )}

            {/* Success */}
            {success && (
              <div className="flex items-center gap-2.5 p-3.5 bg-emerald-500/[0.08] border border-emerald-500/20 rounded-xl">
                <CheckCircle2 className="w-4 h-4 text-emerald-400 shrink-0" />
                <p className="text-sm text-emerald-300">Password changed! Redirecting…</p>
              </div>
            )}

            {/* Actions */}
            <div className="flex gap-2 pt-1">
              <button
                type="button"
                onClick={handleCancelOrLogout}
                className="flex-1 py-2.5 text-sm text-gray-400 hover:text-white border border-white/[0.07] hover:border-white/[0.15] rounded-xl transition-all"
              >
                {forced ? 'Logout' : 'Cancel'}
              </button>
              <button
                type="submit"
                disabled={mutation.isPending || success || !passwordsMatch || !newPasswordLong}
                className="flex-1 flex items-center justify-center gap-2 py-2.5 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white text-sm font-bold rounded-xl transition-all shadow-lg shadow-indigo-500/25"
              >
                {mutation.isPending ? (
                  <>
                    <Loader2 className="w-4 h-4 animate-spin" /> Saving…
                  </>
                ) : (
                  'Change Password'
                )}
              </button>
            </div>
          </form>
        </div>
      </div>
    </div>
  )
}
