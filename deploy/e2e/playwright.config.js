import { defineConfig, devices } from '@playwright/test';

// Two base URLs on purpose. The client API is what the Flutter apps call over
// plain HTTP in development, and the admin dashboard only works over TLS
// because its session cookie is `secure` unconditionally -- so the run puts a
// proxy in front rather than relaxing the cookie. Both point at the same
// application.
const HTTP = process.env.E2E_HTTP_BASE ?? 'http://127.0.0.1:8080';
const HTTPS = process.env.E2E_HTTPS_BASE ?? 'https://127.0.0.1:8443';

export default defineConfig({
	testDir: './tests',
	// Serial. Several specs write to the shared seeded database and one of them
	// suspends a company; parallel workers would read each other's writes and
	// the failure would look like a product defect.
	workers: 1,
	fullyParallel: false,
	forbidOnly: !!process.env.CI,
	retries: 0,
	timeout: 60_000,
	expect: { timeout: 10_000 },
	reporter: [
		['list'],
		['html', { outputFolder: 'report', open: 'never' }],
		['json', { outputFile: 'report/results.json' }],
	],
	outputDir: 'test-results',
	use: {
		baseURL: HTTP,
		// The proxy's certificate is self-signed and generated per run. This
		// ignores THAT certificate, not TLS: the connection is still TLS, which
		// is the whole point -- it is what makes the browser keep the cookie.
		ignoreHTTPSErrors: true,
		screenshot: 'only-on-failure',
		trace: 'retain-on-failure',
		video: 'off',
	},
	projects: [
		{
			name: 'api',
			testMatch: /client-api\.spec\.js/,
			use: { baseURL: HTTP },
		},
		{
			name: 'surface',
			testMatch: /flutter-client-surface\.spec\.js/,
			use: { baseURL: HTTP },
		},
		{
			name: 'deployment',
			testMatch: /deployment-shape\.spec\.js/,
			use: { baseURL: HTTP },
		},
		{
			name: 'admin',
			testMatch: /admin-dashboard\.spec\.js/,
			use: { ...devices['Desktop Chrome'], baseURL: HTTPS, viewport: { width: 1440, height: 900 } },
		},
	],
});
