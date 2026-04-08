import { test, expect } from '@playwright/test'

/**
 * Route lifecycle E2E test — the critical path through the Routify dashboard.
 *
 * Tests run against mock mode (VITE_MOCK=true) with MSW intercepting all
 * /api calls in the browser. The auth setup project has already saved an
 * authenticated storageState, so these tests start on the routes page
 * without needing to log in.
 *
 * The full lifecycle (create → activate → verify → deactivate → delete) is
 * a single test because MSW's in-memory database resets on every page load.
 * Each step depends on the previous one — they must share the same page.
 */
test.describe('Route Lifecycle', () => {
  test('create → activate → verify in list → deactivate → delete', async ({ page }) => {
    const ROUTE_NAME = 'E2E Lifecycle Route'
    const PATH_PATTERN = '/api/v1/e2e-test/**'
    const UPSTREAM_URI = 'http://test-service:9090'

    await page.goto('/routes')
    await expect(page.getByRole('heading', { name: 'Routes', level: 1 })).toBeVisible()

    // ─── Step 1: Create a new route ─────────────────────────────────────────
    await page.getByRole('button', { name: 'New Route' }).click()
    await expect(page.getByText('Create New Route')).toBeVisible()

    // Fill the form
    await page.getByPlaceholder('e.g. orders-api-v1').fill(ROUTE_NAME)
    await page.getByPlaceholder('/api/v1/orders/**').fill(PATH_PATTERN)

    // Toggle POST method (GET is selected by default)
    await page.getByRole('button', { name: 'POST' }).click()

    // Fill upstream URI
    await page.getByPlaceholder(/http:\/\/orders-service/).fill(UPSTREAM_URI)

    // Submit the form
    await page.getByRole('button', { name: 'Create Route' }).click()

    // Wait for the toast confirmation
    await expect(page.getByText('Route created')).toBeVisible({ timeout: 5_000 })

    // The modal should close and the route should appear in the list
    const routeCard = page.locator('[role="button"]').filter({ hasText: ROUTE_NAME })
    await expect(routeCard).toBeVisible({ timeout: 5_000 })

    // Verify it's in DRAFT status
    await expect(routeCard.getByText('Draft')).toBeVisible()

    // ─── Step 2: Activate the route ─────────────────────────────────────────
    await routeCard.getByRole('button', { name: 'Activate' }).click()

    // Wait for the status badge to change to Active.
    // Use the badge span (inside the header's action area) to avoid matching "Active since ..."
    const statusBadge = routeCard.locator('span.rounded-full >> text=Active').first()
    await expect(statusBadge).toBeVisible({ timeout: 5_000 })

    // Toast should confirm activation
    await expect(page.getByText('Route activated')).toBeVisible({ timeout: 5_000 })

    // ─── Step 3: Verify route in the Active filter tab ──────────────────────
    // The status filter tabs are at the top of the list header.
    // Click the "Active" tab (first button matching /Active/ in the tab bar)
    const tabBar = page.locator('.flex.items-center.gap-6')
    await tabBar.getByRole('button', { name: /Active/ }).click()

    // Route should still be visible in the Active-only view
    await expect(page.getByText(ROUTE_NAME)).toBeVisible({ timeout: 5_000 })

    // Go back to "All" tab for next steps
    await tabBar.getByRole('button', { name: /All/ }).click()
    await expect(routeCard).toBeVisible({ timeout: 5_000 })

    // ─── Step 4: Deactivate (Pause) the route ───────────────────────────────
    await routeCard.getByRole('button', { name: 'Pause' }).click()

    // Wait for status badge to change to Disabled
    const disabledBadge = routeCard.locator('span.rounded-full >> text=Disabled').first()
    await expect(disabledBadge).toBeVisible({ timeout: 5_000 })

    // Toast should confirm deactivation
    await expect(page.getByText('Route deactivated')).toBeVisible({ timeout: 5_000 })

    // ─── Step 5: Delete the deactivated route ───────────────────────────────
    // The delete (Trash2) icon appears only for non-ACTIVE routes
    await routeCard.getByTitle('Delete').click()

    // The ConfirmDeletePopover should appear within the route card
    await expect(routeCard.getByText('Delete route?')).toBeVisible()

    // Confirm deletion — click the red "Delete" button in the confirm popover.
    // The popover is rendered inside the route card. It contains two buttons:
    // "Cancel" and "Delete" — we want the latter.
    const confirmBtn = routeCard.locator('button', { hasText: 'Delete' }).last()

    // Wait for the delete API call to complete
    const [deleteResponse] = await Promise.all([
      page.waitForResponse(
        (resp) => resp.url().includes('/api/v1/admin/routes/') && resp.request().method() === 'DELETE',
      ),
      confirmBtn.click(),
    ])

    // Verify the mock returned 204
    expect(deleteResponse.status()).toBe(204)

    // Wait for TanStack Query to refetch the list after invalidation
    await page.waitForResponse(
      (resp) => resp.url().includes('/api/v1/admin/routes') && resp.request().method() === 'GET',
    )

    // The route card should disappear from the list after query refetch
    await expect(routeCard).toHaveCount(0, { timeout: 10_000 })
  })
})

test.describe('Route List', () => {
  test('shows seed routes on page load', async ({ page }) => {
    await page.goto('/routes')
    await expect(page.getByRole('heading', { name: 'Routes', level: 1 })).toBeVisible()

    // The mock db comes pre-seeded with routes — route cards should appear
    const routeCards = page.locator('[role="button"]').filter({ has: page.locator('h3') })
    await expect(routeCards.first()).toBeVisible({ timeout: 5_000 })
  })

  test('status filter tabs work', async ({ page }) => {
    await page.goto('/routes')
    await expect(page.getByRole('heading', { name: 'Routes', level: 1 })).toBeVisible()

    // Wait for routes to load
    const routeCards = page.locator('[role="button"]').filter({ has: page.locator('h3') })
    await expect(routeCards.first()).toBeVisible({ timeout: 5_000 })

    // Click the "Draft" tab
    const tabBar = page.locator('.flex.items-center.gap-6')
    await tabBar.getByRole('button', { name: /Draft/ }).click()

    // Wait a moment for the filter to apply
    await page.waitForTimeout(500)

    // Click back to "All" tab
    await tabBar.getByRole('button', { name: /All/ }).click()

    // Should show all routes again
    await expect(routeCards.first()).toBeVisible({ timeout: 5_000 })
  })

  test('environment filter toggles work', async ({ page }) => {
    await page.goto('/routes')
    await expect(page.getByRole('heading', { name: 'Routes', level: 1 })).toBeVisible()

    // Wait for routes to load
    const routeCards = page.locator('[role="button"]').filter({ has: page.locator('h3') })
    await expect(routeCards.first()).toBeVisible({ timeout: 5_000 })

    // Click "Staging" environment toggle
    await page.getByRole('button', { name: /Staging/ }).click()
    await page.waitForTimeout(500)

    // Click "All Envs" to reset
    await page.getByRole('button', { name: /All Envs/ }).click()
    await expect(routeCards.first()).toBeVisible({ timeout: 5_000 })
  })

  test('New Route button opens the create modal', async ({ page }) => {
    await page.goto('/routes')
    await expect(page.getByRole('heading', { name: 'Routes', level: 1 })).toBeVisible()

    await page.getByRole('button', { name: 'New Route' }).click()
    await expect(page.getByText('Create New Route')).toBeVisible()

    // Close the modal via Cancel button
    await page.getByRole('button', { name: 'Cancel' }).click()
    await expect(page.getByText('Create New Route')).toBeHidden()
  })

  test('route card shows pipeline visualization', async ({ page }) => {
    await page.goto('/routes')
    await expect(page.getByRole('heading', { name: 'Routes', level: 1 })).toBeVisible()

    // Wait for route cards to appear
    const routeCards = page.locator('[role="button"]').filter({ has: page.locator('h3') })
    await expect(routeCards.first()).toBeVisible({ timeout: 5_000 })

    // Pipeline visualization elements should be present
    await expect(routeCards.first().getByText('Client')).toBeVisible()
    await expect(routeCards.first().getByText('Upstream')).toBeVisible()
    await expect(routeCards.first().getByText('Route Config')).toBeVisible()
  })
})
