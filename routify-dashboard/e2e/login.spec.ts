import { test, expect } from '@playwright/test'

/**
 * Login page E2E tests — run without the shared auth setup so we can
 * exercise the login form from a clean (unauthenticated) state.
 *
 * Uses `storageState: { cookies: [], origins: [] }` to override the
 * default authenticated storageState from auth.setup.ts.
 */
test.use({ storageState: { cookies: [], origins: [] } })

test.describe('Login Page', () => {
  test('shows the login form', async ({ page }) => {
    await page.goto('/login')
    await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible()
    await expect(page.getByText('Sign in to your workspace to continue.')).toBeVisible()
  })

  test('requires workspace selection before credentials', async ({ page }) => {
    await page.goto('/login')

    // Username and password fields should be disabled before selecting a workspace
    const usernameInput = page.getByPlaceholder('admin')
    await expect(usernameInput).toBeDisabled()
  })

  test('shows workspace options in dropdown', async ({ page }) => {
    await page.goto('/login')

    // Open the workspace dropdown
    await page.getByRole('button', { name: /Select workspace/ }).click()

    // Should see active workspaces from mock data (Routify Demo and Acme Corp)
    await expect(page.getByRole('button', { name: 'Routify Demo' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Acme Corp' })).toBeVisible()
  })

  test('rejects invalid credentials', async ({ page }) => {
    await page.goto('/login')

    // Select workspace
    await page.getByRole('button', { name: /Select workspace/ }).click()
    await page.getByRole('button', { name: 'Routify Demo' }).click()

    // Fill wrong credentials
    await page.getByPlaceholder('admin').fill('admin')
    await page.getByPlaceholder('••••••••').fill('wrong-password')

    // Submit
    await page.getByRole('button', { name: /Sign in/ }).click()

    // Should show error message
    await expect(page.getByText('Invalid username or password.')).toBeVisible()
  })

  test('successful login redirects to /routes', async ({ page }) => {
    await page.goto('/login')

    // Select workspace
    await page.getByRole('button', { name: /Select workspace/ }).click()
    await page.getByRole('button', { name: 'Routify Demo' }).click()

    // Fill valid credentials
    await page.getByPlaceholder('admin').fill('admin')
    await page.getByPlaceholder('••••••••').fill('routify_admin_2025')

    // Submit
    await page.getByRole('button', { name: /Sign in/ }).click()

    // Wait for redirect to /routes
    await expect(page.getByRole('heading', { name: 'Routes', level: 1 })).toBeVisible({ timeout: 10_000 })
    expect(page.url()).toContain('/routes')
  })

  test('rejects unknown workspace', async ({ page }) => {
    await page.goto('/login')

    // Select workspace — use the Routify Demo workspace but we'll test wrong tenant
    // by directly filling credentials for a non-existent workspace
    // (In the mock, tenantSlug !== 'routify' returns 401)
    await page.getByRole('button', { name: /Select workspace/ }).click()
    await page.getByRole('button', { name: 'Acme Corp' }).click()

    // Fill any credentials — mock rejects non-'routify' slugs
    await page.getByPlaceholder('admin').fill('admin')
    await page.getByPlaceholder('••••••••').fill('routify_admin_2025')

    // Submit
    await page.getByRole('button', { name: /Sign in/ }).click()

    // Should show error message
    await expect(page.getByText('Workspace not found.')).toBeVisible()
  })
})
