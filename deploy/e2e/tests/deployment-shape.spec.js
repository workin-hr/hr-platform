import { execFileSync } from 'node:child_process';
import { test, expect } from '@playwright/test';

/**
 * What each profile promises, checked against the profile that is running.
 *
 * These are configuration claims -- "the API description is off in production",
 * "only health is exposed", "the port is on loopback" -- and configuration
 * claims are the ones that rot silently, because nothing fails when they stop
 * being true. A property file says what it says; this says what the container
 * does.
 *
 *   E2E_PROFILE=local|integration|prod npx playwright test deployment-shape
 */

const PROFILE = process.env.E2E_PROFILE ?? 'local';
const CONTAINER = process.env.E2E_APP_CONTAINER ?? `workin-${PROFILE}-app-1`;

/** springdoc is a development and integration aid; production is not one. */
const DESCRIPTION_PUBLISHED = PROFILE !== 'prod';

test.describe(`the ${PROFILE} profile`, () => {
	test('reports healthy', async ({ request }) => {
		const response = await request.get('/actuator/health');

		expect(response.status()).toBe(200);
		expect((await response.json()).status).toBe('UP');
	});

	test('runs the profile it was asked to run', async () => {
		// The startup line is the one place the answer is written down. A
		// stack running `local` where `prod` was asked for publishes the API
		// description and logs at debug, and nothing else about it looks wrong.
		const log = execFileSync('docker', ['logs', CONTAINER], { encoding: 'utf8', maxBuffer: 64e6 });
		// Singular AND plural. Spring writes "The following 1 profile is
		// active" for one and "The following 2 profiles are active" for more,
		// and every compose file here sets exactly one -- so matching only
		// 'profiles are active' could never match, and this assertion failed
		// for every profile rather than checking anything. Nothing in CI runs
		// this suite, so it stayed red unnoticed.
		const line = log.split('\n').find((entry) => entry.includes('The following')
			&& /profiles? (is|are) active/.test(entry));

		expect(line, 'the startup log names the active profiles').toBeTruthy();
		expect(line).toContain(PROFILE);
	});

	test('exposes health and nothing else over the management surface', async ({ request }) => {
		// `prometheus` is in this list deliberately. It is exposed ONLY by
		// deploy/compose.observability.yaml, which no deployed stack runs --
		// and it was briefly keyed to the `local` and `integration` profiles
		// instead, which put it behind the public edge on the stack that points
		// at the production database (ADR-0019). Nothing tested that, so
		// nothing would have caught it.
		for (const endpoint of ['env', 'beans', 'configprops', 'loggers', 'mappings',
			'heapdump', 'prometheus']) {
			const response = await request.get(`/actuator/${endpoint}`);
			expect(response.status(), `/actuator/${endpoint} must not be published`)
				.not.toBe(200);
		}
	});

	test(`${DESCRIPTION_PUBLISHED ? 'publishes' : 'does not publish'} the API description`, async ({ request }) => {
		const document = await request.get('/v3/api-docs/client-api');
		const ui = await request.get('/swagger-ui/index.html');

		if (DESCRIPTION_PUBLISHED) {
			expect(document.status()).toBe(200);
			expect(ui.status()).toBe(200);
			const paths = Object.keys((await document.json()).paths);
			expect(paths.length, 'the whole client surface is described').toBe(202);
			expect(paths.filter((path) => path.endsWith('.php')),
				'the published paths are the URLs clients call').toEqual([]);
		}
		else {
			// Off, not access-controlled: "off" has no bypass, and an
			// unauthenticated map of every endpoint on a live system is
			// reconnaissance somebody else can use.
			expect(document.status()).not.toBe(200);
			expect(ui.status()).not.toBe(200);
		}
	});

	test('serves the client API', async ({ request }) => {
		// This route reads `configs`, and the production profile mounts no seed
		// on purpose -- production data arrives by a supervised restore. On a
		// first `./run.sh prod` the table does not exist, and a 500 there would
		// say nothing about the deployment's shape, which is what this project
		// tests. With E2E_SEED_PROD the restore has happened and it runs.
		test.skip(PROFILE === 'prod' && !process.env.E2E_SEED_PROD,
			'the production profile starts with an empty database (E2E_SEED_PROD restores one)');
		const response = await request.get('/apis/api/configs/get');

		expect(response.status()).toBe(200);
		expect((await response.json()).success).toBe(true);
	});

	test('refuses an unauthenticated call to a scoped route', async ({ request }) => {
		expect((await request.get('/apis/api/employees/list')).status()).toBe(401);
	});

	test('answers the wrong verb the way PHP does, not the way Spring would', async ({ request }) => {
		const response = await request.get('/apis/api/auth/login_company');

		expect(response.status()).toBe(405);
		expect(await response.json()).toEqual({ success: false, message: 'Invalid method' });
	});

	test('binds its published ports to loopback where the profile says so', async () => {
		test.skip(PROFILE === 'integration',
			'integration publishes the app port deliberately: clients on other machines point at it');
		const inspected = execFileSync(
			'docker', ['inspect', '-f', '{{json .NetworkSettings.Ports}}', CONTAINER],
			{ encoding: 'utf8' });
		const bindings = Object.values(JSON.parse(inspected)).flat().filter(Boolean);

		expect(bindings.length, 'the app publishes a port').toBeGreaterThan(0);
		if (PROFILE === 'prod') {
			// R-049: prod trusts X-Forwarded-*, which is only safe while a
			// proxy is the sole route to this port.
			for (const binding of bindings) {
				expect(binding.HostIp, 'production publishes on loopback only').toBe('127.0.0.1');
			}
		}
	});

	test('the database is never published to anything but loopback', async () => {
		const inspected = execFileSync(
			'docker', ['inspect', '-f', '{{json .NetworkSettings.Ports}}',
				CONTAINER.replace('-app-', '-db-')],
			{ encoding: 'utf8' });
		const bindings = Object.values(JSON.parse(inspected)).flat().filter(Boolean);

		for (const binding of bindings) {
			expect(binding.HostIp, 'the live database is on a public IP; this stack does not repeat that')
				.toBe('127.0.0.1');
		}
	});

	test('administrative actions are off unless the deployment turned them on', async () => {
		test.skip(PROFILE !== 'prod', 'only production defaults this off (ADR-0015 prerequisite 7)');
		const inspected = execFileSync(
			'docker', ['inspect', '-f', '{{json .Config.Env}}', CONTAINER], { encoding: 'utf8' });
		const environment = JSON.parse(inspected);
		const flag = environment.find((entry) => entry.startsWith('APP_PLATFORM_ADMIN_ACTIONS_ENABLED='));

		expect(flag, 'the flag is set explicitly rather than left to a default nobody read')
			.toBeTruthy();
		expect(flag.split('=')[1]).toBe('false');
	});

	test('no default account is auto-configured, and no password is printed to the log', async () => {
		// Spring Boot supplies a `user` account and PRINTS ITS PASSWORD when
		// spring-security is on the classpath and nothing declares a
		// UserDetailsService. Every chain here authenticates for itself, so the
		// account was unreachable -- measured: HTTP Basic with those credentials
		// changed no status code on any route -- but an unreachable account
		// whose password sits in the production log is one `.httpBasic()` away
		// from a live one. The autoconfiguration is excluded; this is what
		// notices if it comes back.
		const log = execFileSync('docker', ['logs', CONTAINER], { encoding: 'utf8', maxBuffer: 64e6 });

		expect(log).not.toContain('Using generated security password');
	});

	test('the admin session cookie is Secure on every profile', async ({ request }) => {
		const response = await request.get('/admin/login', { maxRedirects: 0 });
		const cookies = response.headersArray()
			.filter((header) => header.name.toLowerCase() === 'set-cookie')
			.map((header) => header.value)
			.filter((value) => value.startsWith('WORKIN_ADMIN_SESSION'));

		// Without this the loop below runs zero times when the header is absent
		// or renamed, and the test passes having proved none of the three flags
		// in its own title.
		expect(cookies, 'the login page set exactly one session cookie').toHaveLength(1);
		for (const cookie of cookies) {
			expect(cookie, 'ADR-0015 prerequisite 6 is unconditional').toContain('Secure');
			expect(cookie).toContain('HttpOnly');
			expect(cookie).toContain('SameSite=Lax');
		}
	});

	test('no WhatsApp credential is configured outside production', async () => {
		test.skip(PROFILE === 'prod', 'production requires them, and must not start without them');
		const environment = JSON.parse(execFileSync(
			'docker', ['inspect', '-f', '{{json .Config.Env}}', CONTAINER], { encoding: 'utf8' }));

		for (const name of ['LEGACY_WHATSAPP_API_TOKEN', 'LEGACY_WHATSAPP_INSTANCE_ID']) {
			const entry = environment.find((value) => value.startsWith(`${name}=`));
			expect(entry === undefined || entry === `${name}=`,
				`${name} is set on a box holding a seed derived from real customer data`).toBe(true);
		}
	});
});
