package com.workin.legacy.companies;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import com.workin.legacy.uploads.LegacyFileUploads;
import com.workin.legacy.wire.LegacyApiException;

class LegacyCompanyServiceTest {

	private final LegacyCompanyStore store = mock(LegacyCompanyStore.class);
	private final LegacyFileUploads uploads = mock(LegacyFileUploads.class);
	private final LegacyCompanyService service = new LegacyCompanyService(store, uploads);

	@Test
	void emptyBodyIsNothingToUpdate() {
		assertThatThrownBy(() -> service.update(9L, Map.of()))
				.isInstanceOf(LegacyApiException.class)
				.satisfies(ex -> assertThat(((LegacyApiException) ex).getMessageKey()).isEqualTo("nothing_to_update"));
	}

	@Test
	void bodyWithOnlyUnrecognizedKeysIsAlsoNothingToUpdate() {
		assertThatThrownBy(() -> service.update(9L, Map.of("not_a_real_field", "x")))
				.isInstanceOf(LegacyApiException.class)
				.satisfies(ex -> assertThat(((LegacyApiException) ex).getMessageKey()).isEqualTo("nothing_to_update"));
		verify(store, never()).updateColumns(eq(9L), anyMap());
	}

	@Test
	void blankCompanyCodeIsFieldRequiredWithTheFieldName() {
		assertThatThrownBy(() -> service.update(9L, Map.of("company_code", "   ")))
				.isInstanceOf(LegacyApiException.class)
				.satisfies(ex -> {
					LegacyApiException e = (LegacyApiException) ex;
					assertThat(e.getStatus()).isEqualTo(400);
					assertThat(e.getMessageKey()).isEqualTo("field_required");
					assertThat(e.getReplace()).containsEntry("field", "company_code");
				});
	}

	@Test
	void tooShortCompanyCodeIsInvalid() {
		assertThatThrownBy(() -> service.update(9L, Map.of("company_code", "ab1")))
				.isInstanceOf(LegacyApiException.class)
				.satisfies(ex -> assertThat(((LegacyApiException) ex).getMessageKey()).isEqualTo("company_code_invalid"));
	}

	@Test
	void companyCodeIsNormalizedUppercaseAndTrimmedBeforeTheTakenCheck() {
		when(store.companyCodeIsTaken("ABCDE", 9L)).thenReturn(false);
		when(store.findById(9L)).thenReturn(Map.of("id", 9L));

		service.update(9L, Map.of("company_code", "  abcde  "));

		verify(store).companyCodeIsTaken("ABCDE", 9L);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, Object>> columns = ArgumentCaptor.forClass(Map.class);
		verify(store).updateColumns(eq(9L), columns.capture());
		assertThat(columns.getValue()).containsEntry("company_code", "ABCDE");
	}

	@Test
	void takenCompanyCodeFails() {
		when(store.companyCodeIsTaken("TAKEN1", 9L)).thenReturn(true);
		assertThatThrownBy(() -> service.update(9L, Map.of("company_code", "taken1")))
				.isInstanceOf(LegacyApiException.class)
				.satisfies(ex -> assertThat(((LegacyApiException) ex).getMessageKey()).isEqualTo("company_code_taken"));
	}

	@Test
	void presentButBlankEmailClearsItRatherThanSkippingTheColumn() {
		when(store.findById(9L)).thenReturn(Map.of("id", 9L));

		service.update(9L, Map.of("email", "   "));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, Object>> columns = ArgumentCaptor.forClass(Map.class);
		verify(store).updateColumns(eq(9L), columns.capture());
		assertThat(columns.getValue()).containsEntry("email", null);
	}

	@Test
	void takenEmailFailsBeforeAnyWrite() {
		when(store.companyEmailIsTaken("dup@example.com", 9L)).thenReturn(true);
		assertThatThrownBy(() -> service.update(9L, Map.of("email", "dup@example.com")))
				.isInstanceOf(LegacyApiException.class)
				.satisfies(ex -> {
					LegacyApiException e = (LegacyApiException) ex;
					assertThat(e.getStatus()).isEqualTo(409);
					assertThat(e.getMessageKey()).isEqualTo("already_exists");
				});
		verify(store, never()).updateColumns(eq(9L), anyMap());
	}

	@Test
	void blankMainBranchAddressIsFieldRequired() {
		assertThatThrownBy(() -> service.update(9L, Map.of("main_branch_address", "  ")))
				.isInstanceOf(LegacyApiException.class)
				.satisfies(ex -> {
					LegacyApiException e = (LegacyApiException) ex;
					assertThat(e.getMessageKey()).isEqualTo("field_required");
					assertThat(e.getReplace()).containsEntry("field", "main_branch_address");
				});
	}

	/**
	 * The two failure shapes for {@code company_activity_id} differ on
	 * purpose, matching PHP exactly: a non-positive id names the field, a
	 * lookup miss does not.
	 */
	@Test
	void nonPositiveCompanyActivityIdNamesTheField() {
		assertThatThrownBy(() -> service.update(9L, Map.of("company_activity_id", 0)))
				.isInstanceOf(LegacyApiException.class)
				.satisfies(ex -> {
					LegacyApiException e = (LegacyApiException) ex;
					assertThat(e.getMessageKey()).isEqualTo("field_required");
					assertThat(e.getReplace()).containsEntry("field", "company_activity_id");
				});
	}

	@Test
	void unknownCompanyActivityIdFailsWithNoFieldReplacement() {
		when(store.companyActivityExists(404L)).thenReturn(false);
		assertThatThrownBy(() -> service.update(9L, Map.of("company_activity_id", 404)))
				.isInstanceOf(LegacyApiException.class)
				.satisfies(ex -> {
					LegacyApiException e = (LegacyApiException) ex;
					assertThat(e.getMessageKey()).isEqualTo("field_required");
					assertThat(e.getReplace()).isEmpty();
				});
	}

	@Test
	void duplicateEntryOnWriteBecomesAlreadyExistsOnEmail() {
		when(store.findById(9L)).thenReturn(Map.of("id", 9L));
		org.mockito.Mockito.doThrow(new org.springframework.dao.DuplicateKeyException(
						"(conn=1) Duplicate entry 'dup@example.com' for key 'companies.email' (1062)"))
				.when(store).updateColumns(eq(9L), anyMap());

		assertThatThrownBy(() -> service.update(9L, Map.of("company_name", "Acme")))
				.isInstanceOf(LegacyApiException.class)
				.satisfies(ex -> {
					LegacyApiException e = (LegacyApiException) ex;
					assertThat(e.getStatus()).isEqualTo(409);
					assertThat(e.getMessageKey()).isEqualTo("already_exists");
					assertThat(e.getReplace()).containsEntry("field", "email");
				});
	}

	@Test
	void uploadLogoWithNoFileFails() {
		when(uploads.store(null, "logos")).thenReturn(null);
		assertThatThrownBy(() -> service.uploadLogo(9L, null))
				.isInstanceOf(LegacyApiException.class)
				.satisfies(ex -> assertThat(((LegacyApiException) ex).getMessageKey()).isEqualTo("no_file_uploaded"));
	}

	@Test
	void uploadLogoStoresTheReturnedUrlOnTheCompanyRow() {
		MultipartFile file = new MockMultipartFile("logo", "logo.png", "image/png", new byte[] {1, 2, 3});
		when(uploads.store(file, "logos")).thenReturn("/uploads/logos/abc.png");
		when(store.findById(9L)).thenReturn(Map.of("id", 9L, "logo_url", "/uploads/logos/abc.png"));

		Map<String, Object> row = service.uploadLogo(9L, file);

		verify(store).updateColumns(9L, Map.of("logo_url", "/uploads/logos/abc.png"));
		assertThat(row).containsEntry("logo_url", "/uploads/logos/abc.png");
	}

	@Test
	void uploadCommercialRegStoresUnderTheCommercialSubdirectory() {
		MultipartFile file = new MockMultipartFile("file", "reg.pdf", "application/pdf", new byte[] {1});
		when(uploads.store(file, "commercial")).thenReturn("/uploads/commercial/xyz.pdf");
		when(store.findById(9L)).thenReturn(Map.of("id", 9L));

		service.uploadCommercialReg(9L, file);

		verify(store).updateColumns(9L, Map.of("commercial_reg_url", "/uploads/commercial/xyz.pdf"));
	}

	@Test
	void multipleFieldsInOneRequestAllSurviveTheDynamicColumnSet() {
		when(store.findById(9L)).thenReturn(Map.of("id", 9L));
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("first_name", "Karim");
		body.put("last_name", "Taha");
		body.put("country_code", "+20");

		service.update(9L, body);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, Object>> columns = ArgumentCaptor.forClass(Map.class);
		verify(store).updateColumns(eq(9L), columns.capture());
		assertThat(columns.getValue())
				.containsEntry("first_name", "Karim")
				.containsEntry("last_name", "Taha")
				.containsEntry("country_code", "+20");
	}
}
