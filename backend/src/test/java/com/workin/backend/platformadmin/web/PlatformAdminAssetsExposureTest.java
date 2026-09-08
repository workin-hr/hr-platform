package com.workin.backend.platformadmin.web;

import java.net.http.HttpClient;

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
