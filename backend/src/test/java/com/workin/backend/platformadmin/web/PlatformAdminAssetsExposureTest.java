package com.workin.backend.platformadmin.web;

import java.net.http.HttpClient;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import com.workin.backend.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /admin/_assets/**} is the one prefix on this chain that answers
 * without a session. This holds both halves of that bargain: the assets are
 * actually reachable, and the exception reaches nothing else.
 *
 * <p>Redirects are not followed. {@code TestRestTemplate} follows them by
 * default, which would turn "this route is protected" and "this route does not
 * exist" into the same 200 on the login page -- the failure mode
 * {@code SecurityPolicyAgreementTest} was written about.
 */
class PlatformAdminAssetsExposureTest extends AbstractIntegrationTest {

	@LocalServerPort
	private int port;

	@Autowired
	private TestRestTemplate restTemplate;

	@BeforeEach
	void doNotFollowRedirects() {
		this.restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
	}

	private String url(String path) {
		return "http://localhost:" + this.port + path;
	}

	@Test
	void aStylesheetIsServedWithoutASession() {
		ResponseEntity<String> response =
				this.restTemplate.getForEntity(url("/admin/_assets/style.css"), String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotBlank();
	}

	@Test
	void theSidebarScriptIsServedWithoutASession() {
		assertThat(this.restTemplate.getForEntity(url("/admin/_assets/sidebar.js"), String.class).getStatusCode())
				.isEqualTo(HttpStatus.OK);
	}

	/** The sign-in page and its favicon load the logo before there is a session (D-254). */
	@Test
	void theLogoIsServedWithoutASessionAsAPng() {
		ResponseEntity<byte[]> response =
				this.restTemplate.getForEntity(url("/admin/_assets/logo.png"), byte[].class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getHeaders().getContentType()).isEqualTo(org.springframework.http.MediaType.IMAGE_PNG);
		assertThat(response.getBody()).hasSize(1_364_971);
	}

	/** The favicon and touch icon are small copies of the logo, not the 1.3 MB file (D-266). */
	@Test
	void theIconsAreSmallCopiesOfTheLogo() {
		for (int size : new int[] {32, 180}) {
			String path = size == 32 ? "/admin/_assets/favicon-32.png" : "/admin/_assets/apple-touch-icon.png";
			ResponseEntity<byte[]> response = this.restTemplate.getForEntity(url(path), byte[].class);
			assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
			assertThat(response.getHeaders().getContentType()).isEqualTo(org.springframework.http.MediaType.IMAGE_PNG);
			byte[] png = response.getBody();
			// A PNG's IHDR holds the width and height as big-endian ints at bytes 16 and 20.
			assertThat(java.nio.ByteBuffer.wrap(png, 16, 8).getInt()).as(path).isEqualTo(size);
			assertThat(java.nio.ByteBuffer.wrap(png, 20, 4).getInt()).as(path).isEqualTo(size);
			assertThat(png.length).as(path).isLessThan(64 * 1024);
		}
	}

	/**
	 * An image is cached for seven days and carries a content ETag (D-266). Its timestamp is not
	 * sent: in the reproducible production jar every entry has the same one.
	 */
	@Test
	void anImageIsCachedForSevenDaysAgainstAContentEtag() {
		ResponseEntity<byte[]> response =
				this.restTemplate.getForEntity(url("/admin/_assets/favicon-32.png"), byte[].class);

		assertThat(response.getHeaders().getCacheControl()).isEqualTo("max-age=604800");
		assertThat(response.getHeaders().getETag()).isEqualTo(contentEtag(response.getBody()));
		assertThat(response.getHeaders().getLastModified()).isEqualTo(-1);
	}

	/**
	 * A stylesheet or script changes with a deploy under the same URL, so the browser revalidates it
	 * on every load against its content, and gets a 304 while it is current. A matching
	 * {@code If-Modified-Since} alone does not earn a 304: the jar's timestamps cannot tell two
	 * deploys apart (D-266).
	 */
	@Test
	void aStylesheetIsRevalidatedAgainstItsContentRatherThanItsTimestamp() {
		ResponseEntity<byte[]> first = this.restTemplate.getForEntity(url("/admin/_assets/style.css"), byte[].class);
		assertThat(first.getHeaders().getCacheControl()).isEqualTo("no-cache");
		assertThat(first.getHeaders().getLastModified()).isEqualTo(-1);
		String etag = first.getHeaders().getETag();
		assertThat(etag).as("the ETag names the content, so a deploy that changes the file changes it")
				.isEqualTo(contentEtag(first.getBody()));

		org.springframework.http.HttpHeaders current = new org.springframework.http.HttpHeaders();
		current.setIfNoneMatch(etag);
		assertThat(this.restTemplate.exchange(url("/admin/_assets/style.css"), org.springframework.http.HttpMethod.GET,
				new org.springframework.http.HttpEntity<>(current), String.class).getStatusCode())
				.isEqualTo(HttpStatus.NOT_MODIFIED);

		org.springframework.http.HttpHeaders stale = new org.springframework.http.HttpHeaders();
		stale.setIfNoneMatch("\"" + "0".repeat(64) + "\"");
		stale.setIfModifiedSince(java.time.ZonedDateTime.now().plusYears(1));
		ResponseEntity<byte[]> after = this.restTemplate.exchange(url("/admin/_assets/style.css"),
				org.springframework.http.HttpMethod.GET, new org.springframework.http.HttpEntity<>(stale), byte[].class);
		assertThat(after.getStatusCode()).as("an old ETag gets the file, whatever the date says").isEqualTo(HttpStatus.OK);
		assertThat(after.getBody()).containsExactly(first.getBody());

		assertThat(this.restTemplate.getForEntity(url("/admin/_assets/sidebar.js"), String.class)
				.getHeaders().getCacheControl()).as("a script too").isEqualTo("no-cache");
	}

	private static String contentEtag(byte[] body) {
		try {
			return "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)) + "\"";
		}
		catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	/** The negative control the permitAll exists to be bounded by. */
	@Test
	void anAdminPageIsStillProtected() {
		assertThat(this.restTemplate.getForEntity(url("/admin/companies"), String.class).getStatusCode())
				.isEqualTo(HttpStatus.FOUND);
	}

	@Test
	void aMissingAssetIsNotFoundRatherThanRedirected() {
		assertThat(this.restTemplate.getForEntity(url("/admin/_assets/nothing-here.css"), String.class).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
	}

	/**
	 * Traversal out of the asset prefix must not reach a protected page. A 200
	 * carrying the companies page would mean the pattern had been used as a
	 * bypass; anything else -- 400 from the firewall, 404, a redirect -- is the
	 * request not resolving to that page, which is what matters.
	 */
	@Test
	void traversalOutOfTheAssetPrefixDoesNotReachAProtectedPage() {
		ResponseEntity<String> response =
				this.restTemplate.getForEntity(url("/admin/_assets/../companies"), String.class);

		assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.OK);
	}

}
