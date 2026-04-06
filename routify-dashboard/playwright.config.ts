import { defineConfig, devices } from '@playwright/test'

/**
 * Playwright E2E configuration for the Routify dashboard.
 *
 * Tests run against mock mode (VITE_MOCK=true) — no backend needed.
 * MSW intercepts all /api calls in the browser; the Vite dev server
 * starts automatically before the first test.
 */
export default defineConfig({
  testDir: 'e2e',
  testMatch: '**/*.spec.ts',

  /* Fail the build on CI if you accidentally left test.only in the source code */
  forbidOnly: !!process.env.CI,

  /* Retry on CI only */
  retries: process.env.CI ? 2 : 0,

  /* Reporter */
  reporter: process.env.CI ? 'github' : 'html',

  /* Shared settings for all projects */
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },

  /* Single Chromium project with an auth setup dependency */
  projects: [
    {
      name: 'setup',
      testMatch: /auth\.setup\.ts/,
    },
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        storageState: 'e2e/.auth/user.json',
      },
      dependencies: ['setup'],
    },
  ],

  /* Start the Vite dev server in mock mode before running tests */
  webServer: {
    command: 'VITE_MOCK=true npx vite --port 5173',
    port: 5173,
    reuseExistingServer: !process.env.CI,
    timeout: 30_000,
  },
})
