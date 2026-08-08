package com.workin.backend.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.workin.backend.AbstractIntegrationTest;
import com.workin.backend.identity.AuthResponse;
import com.workin.backend.identity.RegisterCompanyRequest;

/**
 * Locale selection (?lang -> Accept-Language -> English) and the
 * {code, message} error contract, exercised through real endpoints.
 */
class I18nFlowTest extends AbstractIntegrationTest {

	private static final AtomicLong PHONE = new AtomicLong(8_000_000_000L);

	@Autowired
	private TestRestTemplate restTemplate;

	private static String uniquePhone() {
		return "+2" + PHONE.incrementAndGet();
	}

	private AuthResponse registerCompanyAdmin() {
		return restTemplate.postForEntity(
				"/api/auth/register",
				new RegisterCompanyRequest("I18n Co", uniquePhone(), "correct horse battery staple"),
				AuthResponse.class).getBody();
	}

	private HttpHeaders bearer(String accessToken, String acceptLanguage) {
		HttpHeaders headers = new HttpHeaders();
		if (accessToken != null) {
			headers.setBearerAuth(accessToken);
		}
		if (acceptLanguage != null) {
			headers.set(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage);
		}
		headers.setContentType(MediaType.APPLICATION_JSON);
		return headers;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> getError(String token, String url, String acceptLanguage) {
		return restTemplate.exchange(
				url, HttpMethod.GET, new HttpEntity<>(bearer(token, acceptLanguage)), Map.class).getBody();
	}

	@Test
	void bareNotFoundRendersGenericCodeInBothLanguages() {
		AuthResponse admin = registerCompanyAdmin();

		Map<String, Object> en = getError(admin.accessToken(), "/api/tenant/shifts/999999", null);
		assertThat(en.get("code")).isEqualTo("error.not_found");
		assertThat(en.get("message")).isEqualTo("Not found");

		Map<String, Object> ar = getError(admin.accessToken(), "/api/tenant/shifts/999999?lang=ar", null);
		assertThat(ar.get("code")).isEqualTo("error.not_found");
		assertThat(ar.get("message")).isEqualTo("غير موجود");
	}

	@Test
	void localePrecedenceParamBeatsHeaderAndUnknownFallsBackToEnglish() {
		AuthResponse admin = registerCompanyAdmin();

		// Header alone selects Arabic.
		assertThat(getError(admin.accessToken(), "/api/tenant/shifts/999999", "ar").get("message"))
				.isEqualTo("غير موجود");
		// Param beats a contradicting header.
		assertThat(getError(admin.accessToken(), "/api/tenant/shifts/999999?lang=en", "ar").get("message"))
				.isEqualTo("Not found");
		// Regioned tag matches its language.
		assertThat(getError(admin.accessToken(), "/api/tenant/shifts/999999?lang=ar-EG", null).get("message"))
				.isEqualTo("غير موجود");
		// Unsupported language falls back to English.
		assertThat(getError(admin.accessToken(), "/api/tenant/shifts/999999?lang=fr", null).get("message"))
				.isEqualTo("Not found");
	}

	@Test
	void validationFailureListsLocalizedFieldMessages() {
		AuthResponse admin = registerCompanyAdmin();
		// shifts create requires name/startTime/endTime; an empty body trips @NotBlank/@NotNull.
		ResponseEntity<Map> response = restTemplate.exchange(
				"/api/tenant/shifts?lang=ar", HttpMethod.POST,
				new HttpEntity<>("{}", bearer(admin.accessToken(), null)), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		Map<String, Object> body = response.getBody();
		assertThat(body.get("code")).isEqualTo("error.validation");
		assertThat(body.get("message")).isEqualTo("فشل التحقق من البيانات");
		assertThat((Iterable<Map<String, Object>>) body.get("fields"))
				.anySatisfy(field -> {
					assertThat(field.get("field")).isEqualTo("name");
					assertThat(field.get("message")).isEqualTo("يجب ألا يكون فارغاً");
				});
	}

}
