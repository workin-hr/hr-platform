package com.workin.legacy.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The tokens this service emits must be indistinguishable from
 * {@code jwtEncode()}'s, because Phase 1's rollback story depends on it.
 *
 * <h2>Why this test exists</h2>
 * <p>G11 of the completion plan rests on "Phase 1 has a genuinely cheap
 * rollback -- the database is unchanged and PHP still runs", and requires that
 * property to be <em>verified rather than assumed</em>. For the session layer
 * that property reduces to one question: <b>does a token minted by Java still
 * authenticate against PHP, and vice versa?</b>
 *
 * <p>If yes, cutover and rollback are both transparent -- nobody is logged out
 * in either direction. If no, cutover forces every live session to
 * re-authenticate, and a rollback forces every session issued since cutover to
 * re-authenticate <em>again</em>.
 *
 * <p>This test pins what is decidable at the codec level: the header, the
 * algorithm, the claim names and their order, the signature construction, and
 * the configured default lifetime. It builds every expectation from
 * {@link PhpJwtOracle}, an independent reimplementation of {@code jwtEncode()},
 * so a change to the production encoder cannot drag the expectation with it.
 *
 * <p><b>The codec is not the whole path.</b> A real Phase 1 request also
 * traverses {@code LegacyPhpJwtAuthenticationFilter}, tenant re-derivation and
 * {@code LegacyRequestGuard}'s token-version and role checks -- any of which
 * could reject a structurally valid PHP token and silently break the rollback
 * property this file claims to protect. That half is covered by
 * {@code LegacyLoginEndToEndTest}, which presents oracle-encoded tokens over
 * real HTTP against real MariaDB.
 *
 * <p><b>What neither test can check</b> is the one input they have no access
 * to: whether the deployed {@code app.jwt.secret} is byte-identical to PHP's
 * {@code AppConfig::JWT_SECRET}. See the pre-cutover verification step in
 * {@code docs/operations/release-cutover-and-rollback.md}, which is the only
 * place that gap can be closed.
 */
class LegacyPhpJwtWireCompatibilityTest {

	private static final String SECRET = "a-test-secret-standing-in-for-AppConfig-JWT_SECRET";

	private final LegacyPhpJwtService service = new LegacyPhpJwtService(SECRET, 87600);

	/** PHP writes {@code {"alg":"HS256","typ":"JWT"}} and nothing else. */
	@Test
	void theHeaderIsByteIdenticalToPhps() {
		String token = service.issueCompanyToken(7L, "company_admin");
		assertThat(PhpJwtOracle.decodeSegment(token, 0)).isEqualTo("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
	}

	/**
	 * Claim <em>order</em> matters, not just presence: the signature covers the
	 * encoded bytes, so reordering keys produces a different signature over the
	 * same logical payload. PHP's array literal fixes the order.
	 */
	@Test
	void theCompanyTokenIsIndistinguishableFromJwtEncodesOutput() {
		String token = service.issueCompanyToken(7L, "company_admin");
		long exp = expiryOf(token);

		assertThat(token)
				.as("a Java-issued company token must verify unchanged in PHP")
				.isEqualTo(PhpJwtOracle.encode(
						PhpJwtOracle.companyPayload(7L, "company_admin", exp), SECRET));
	}

	@Test
	void theEmployeeTokenIsIndistinguishableFromJwtEncodesOutput() {
		String token = service.issueEmployeeToken(42L, 7L, "hr", 3L);
		long exp = expiryOf(token);

		assertThat(token)
				.as("a Java-issued employee token must verify unchanged in PHP")
				.isEqualTo(PhpJwtOracle.encode(
						PhpJwtOracle.employeePayload(42L, 7L, "hr", 3L, exp), SECRET));
	}

	/**
	 * The other direction, which is the one rollback actually depends on: a
	 * token PHP minted before cutover must still authenticate against Java.
	 */
	@Test
	void aTokenMintedByPhpIsAcceptedByJava() {
		long exp = System.currentTimeMillis() / 1000 + 3600;
		String phpToken = PhpJwtOracle.encode(
				PhpJwtOracle.employeePayload(42L, 7L, "hr", 3L, exp), SECRET);

		assertThat(service.decode(phpToken))
				.as("a PHP-issued session must survive cutover")
				.isNotNull();
	}

	/**
	 * And the failure mode this all turns on, stated as a test: a different
	 * secret rejects the token outright. This is what a mismatched
	 * {@code app.jwt.secret} looks like in production -- every existing session
	 * invalid at the instant of cutover.
	 */
	@Test
	void aTokenSignedWithADifferentSecretIsRejected() {
		long exp = System.currentTimeMillis() / 1000 + 3600;
		String foreign = PhpJwtOracle.encode(
				PhpJwtOracle.companyPayload(7L, "company_admin", exp),
				"a-different-secret-as-if-the-two-deployments-disagreed");

		assertThat(service.decode(foreign))
				.as("this is the mass-forced-logout scenario, pinned so it stays visible")
				.isNull();
	}

	/**
	 * PHP's {@code JWT_EXPIRE_HOURS} is 87600 -- ten years. The Java default
	 * must match, or tokens minted either side of a cutover expire on different
	 * schedules and the two systems disagree about who is still logged in.
	 *
	 * <p>Bound through the container with no {@code app.legacy-jwt.expiry-hours}
	 * property set, so this exercises the {@code @Value} fallback that
	 * production actually runs on. Constructing the service directly with a
	 * literal {@code 87600} would assert only that the fixture passes what the
	 * fixture passes, and would stay green if the fallback were edited.
	 */
	@Test
	void theConfiguredDefaultExpiryMatchesPhpsTenYears() {
		new ApplicationContextRunner()
				.withBean(LegacyPhpJwtService.class)
				.withPropertyValues("app.jwt.secret=" + SECRET)
				.run(context -> {
					long exp = expiryOf(context.getBean(LegacyPhpJwtService.class)
							.issueCompanyToken(1L, "company_admin"));
					long hours = (exp - System.currentTimeMillis() / 1000) / 3600;
					assertThat(hours).isBetween(87598L, 87600L);
				});
	}

	private static long expiryOf(String token) {
		return Long.parseLong(
				PhpJwtOracle.decodeSegment(token, 1).replaceAll(".*\"exp\":(\\d+).*", "$1"));
	}
}
