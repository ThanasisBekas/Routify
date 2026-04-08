import { describe, it, expect, beforeEach } from 'vitest'
import { useAuthStore } from '../../store/authStore'

describe('authStore', () => {
  beforeEach(() => {
    useAuthStore.getState().logout()
  })

  it('starts with unauthenticated state', () => {
    const state = useAuthStore.getState()
    expect(state.accessToken).toBeNull()
    expect(state.user).toBeNull()
    expect(state.isAuthenticated).toBe(false)
  })

  it('setTokens sets accessToken and isAuthenticated', () => {
    useAuthStore.getState().setTokens('my-token')
    const state = useAuthStore.getState()
    expect(state.accessToken).toBe('my-token')
    expect(state.isAuthenticated).toBe(true)
  })

  it('setUser sets the user object', () => {
    const user = {
      id: 'u1',
      tenantId: 't1',
      username: 'admin',
      email: 'admin@test.com',
      role: 'SUPER_ADMIN' as const,
    }
    useAuthStore.getState().setUser(user)
    expect(useAuthStore.getState().user).toEqual(user)
  })

  it('logout clears everything', () => {
    useAuthStore.getState().setTokens('token')
    useAuthStore.getState().setUser({
      id: 'u1',
      tenantId: 't1',
      username: 'admin',
      email: 'a@b.com',
      role: 'SUPER_ADMIN',
    })

    useAuthStore.getState().logout()

    const state = useAuthStore.getState()
    expect(state.accessToken).toBeNull()
    expect(state.user).toBeNull()
    expect(state.isAuthenticated).toBe(false)
  })

  it('setTokens does not affect user', () => {
    useAuthStore.getState().setUser({
      id: 'u1',
      tenantId: 't1',
      username: 'admin',
      email: 'a@b.com',
      role: 'VIEWER',
    })
    useAuthStore.getState().setTokens('new-token')
    expect(useAuthStore.getState().user).not.toBeNull()
    expect(useAuthStore.getState().accessToken).toBe('new-token')
  })

  it('partialize returns empty object (nothing persisted)', () => {
    useAuthStore.getState().setTokens('secret-token')
    // The persist middleware is configured with partialize: () => ({})
    // Verify no localStorage writes by inspecting the store contract:
    // the partialize function should produce an empty object
    expect(useAuthStore.persist.getOptions().partialize?.(useAuthStore.getState())).toEqual({})
  })
})

