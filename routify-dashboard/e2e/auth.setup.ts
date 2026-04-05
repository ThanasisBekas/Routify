import { test as setup, expect } from '@playwright/test'

const authFile = 'e2e/.auth/user.json'

/**
 * Auth setup — runs once before all tests.
 *
 * In mock mode, main.tsx pre-seeds the Zustand auth store on boot, so the app
 * auto-redirects to /routes. We simply navigate, wait for the authenticated
 * page to load, and save the browser storageState (localStorage + cookies).
 *
 * If the pre-seed is ever removed, this setup will fall back to an explicit
 * login flow via the form.
 */
setup('authenticate via mock mode', async ({ page }) => {
  // Navigate to the app — mock mode pre-seeds auth, so we should land on /routes
  await page.goto('/')

  // Wait for the app to bootstrap and redirect to the routes page.
  // The RouteListHeader renders an <h1>Routes</h1> heading.
  const heading = page.getByRole('heading', { name: 'Routes', level: 1 })

  // If the pre-seed works, we'll see the Routes heading directly.
  // If not (e.g. future change), fall back to explicit login.
  try {
    await heading.waitFor({ timeout: 5_000 })
  } catch {
    // Pre-seed didn't work — do an explicit login
    await page.goto('/login')
    await page.getByRole('heading', { name: 'Welcome back' }).waitFor()

    // Select workspace from dropdown
    await page.getByRole('button', { name: /Select workspace/ }).click()
    await page.getByRole('button', { name: 'Routify Demo' }).click()

    // Fill credentials
    await page.getByPlaceholder('admin').fill('admin')
    await page.getByPlaceholder('••••••••').fill('routify_admin_2025')

    // Submit
    await page.getByRole('button', { name: /Sign in/ }).click()

    // Wait for redirect to routes
    await heading.waitFor({ timeout: 10_000 })
  }

  // Verify we are authenticated and on the routes page
  await expect(heading).toBeVisible()

  // Save the authenticated browser state for other tests
  await page.context().storageState({ path: authFile })
})
