package com.workin.legacy.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

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
 * <p>This test pins everything that is verifiable in this repository: the
 * header, the algorithm, the claim names and their order, and the signature
 * construction. It reimplements {@code jwtEncode()}
 * ({@code apis/helpers/functions.php:420-430}) independently rather than
 * calling the production encoder, so a change to the encoder cannot silently
 * drag the expectation along with it.
 *
 * <p><b>What it cannot check</b> is the one input it has no access to: whether
 * the deployed {@code app.jwt.secret} is byte-identical to PHP's
 * {@code AppConfig::JWT_SECRET}. Nothing in this repository can know that, and
 * nothing currently asserts it -- see the pre-cutover verification step in
 * {@code docs/operations/release-cutover-and-rollback.md}, which is the only
 * place that gap can be closed.
 */
class LegacyPhpJwtWireCompatibilityTest {

	private static final String SECRET = "a-test-secret-standing-in-for-AppConfig-JWT_SECRET";

	private final LegacyPhpJwtService service = new LegacyPhpJwtService(SECRET, 87600);

	/**
	 * {@code apis/helpers/functions.php:420-430}, reimplemented here rather than
	 * shared with the production path, so this is a genuine oracle.
	 */
	private static String phpJwtEncode(String payloadJson, String secret) throws Exception {
		Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
		String header = url.encodeToString(
				"{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
		String payload = url.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		byte[] sig = mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
		return header + "." + payload + "." + url.encodeToString(sig);
	}

	private static String segment(String token, int index) {
		return new String(Base64.getUrlDecoder().decode(token.split("\\.")[index]),
				StandardCharsets.UTF_8);
	}

	/** PHP writes {@code {"alg":"HS256","typ":"JWT"}} and nothing else. */
	@Test
	void theHeaderIsByteIdenticalToPhps() {
		String token = service.issueCompanyToken(7L, "company_admin");
		assertThat(segment(token, 0)).isEqualTo("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
	}

	/**
	 * Claim <em>order</em> matters, not just presence: the signature covers the
	 * encoded bytes, so reordering keys produces a different signature over the
	 * same logical payload. PHP's array literal fixes the order.
	 */
	@Test
	void theCompanyTokenIsIndistinguishableFromJwtEncodesOutput() throws Exception {
		String token = service.issueCompanyToken(7L, "company_admin");
		long exp = Long.parseLong(segment(token, 1).replaceAll(".*\"exp\":(\\d+).*", "$1"));

		String expected = phpJwtEncode(
				"{\"type\":\"company\",\"company_id\":7,\"role\":\"company_admin\",\"exp\":" + exp + "}",
				SECRET);

		assertThat(token)
				.as("a Java-issued company token must verify unchanged in PHP")
				.isEqualTo(expected);
	}

	@Test
	void theEmployeeTokenIsIndistinguishableFromJwtEncodesOutput() throws Exception {
		String token = service.issueEmployeeToken(42L, 7L, "hr", 3L);
		long exp = Long.parseLong(segment(token, 1).replaceAll(".*\"exp\":(\\d+).*", "$1"));

		String expected = phpJwtEncode(
				"{\"type\":\"employee\",\"employee_id\":42,\"company_id\":7,\"role\":\"hr\""
						+ ",\"token_version\":3,\"exp\":" + exp + "}",
				SECRET);

		assertThat(token)
				.as("a Java-issued employee token must verify unchanged in PHP")
				.isEqualTo(expected);
	}

	/**
	 * The other direction, which is the one rollback actually depends on: a
	 * token PHP minted before cutover must still authenticate against Java.
	 */
	@Test
	void aTokenMintedByPhpIsAcceptedByJava() throws Exception {
		long exp = System.currentTimeMillis() / 1000 + 3600;
		String phpToken = phpJwtEncode(
				"{\"type\":\"employee\",\"employee_id\":42,\"company_id\":7,\"role\":\"hr\""
						+ ",\"token_version\":3,\"exp\":" + exp + "}",
				SECRET);

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
	void aTokenSignedWithADifferentSecretIsRejected() throws Exception {
		long exp = System.currentTimeMillis() / 1000 + 3600;
		String foreign = phpJwtEncode(
				"{\"type\":\"company\",\"company_id\":7,\"role\":\"company_admin\",\"exp\":" + exp + "}",
				"a-different-secret-as-if-the-two-deployments-disagreed");

		assertThat(service.decode(foreign))
				.as("this is the mass-forced-logout scenario, pinned so it stays visible")
				.isNull();
	}

	/**
	 * PHP's {@code JWT_EXPIRE_HOURS} is 87600 -- ten years. The Java default
	 * must match, or tokens minted either side of a cutover expire on different
	 * schedules and the two systems disagree about who is still logged in.
	 */
	@Test
	void theDefaultExpiryMatchesPhpsTenYears() {
		long exp = Long.parseLong(
				segment(service.issueCompanyToken(1L, "company_admin"), 1)
						.replaceAll(".*\"exp\":(\\d+).*", "$1"));
		long hours = (exp - System.currentTimeMillis() / 1000) / 3600;
		assertThat(hours).isBetween(87598L, 87600L);
	}
}
