package com.workin.legacy.companies;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.workin.legacy.LegacyValues;
import com.workin.legacy.uploads.LegacyFileUploads;
import com.workin.legacy.wire.LegacyApiException;

/**
 * {@code company/{update,upload_logo,upload_commercial_reg}.php} (Wave 12.10).
 */
@Service
public class LegacyCompanyService {

	private final LegacyCompanyStore store;
	private final LegacyFileUploads uploads;

	public LegacyCompanyService(LegacyCompanyStore store, LegacyFileUploads uploads) {
		this.store = store;
		this.uploads = uploads;
	}

	/**
	 * {@code update.php}. The body is read once and its surviving keys are
	 * written in the exact order PHP checks them -- two fields share a
	 * "not found" failure that carries no {@code field} replacement
	 * ({@code company_activity_id}/{@code company_title_id}/{@code company_size_id}'s
	 * lookup miss), unlike every other validation failure in this endpoint,
	 * which does. That asymmetry is PHP's own and is reproduced rather than
	 * smoothed over.
	 */
	public Map<String, Object> update(long companyId, Map<String, Object> body) {
		if (LegacyValues.isPhpEmpty(body)) {
			throw new LegacyApiException(400, "nothing_to_update");
		}

		Map<String, Object> columns = new LinkedHashMap<>();

		Object companyName = body.get("company_name");
		if (companyName != null) {
			columns.put("company_name", companyName);
		}

		if (body.containsKey("company_code")) {
			// company_code_normalize(): strtoupper(trim($code)).
			String normalized = LegacyValues.phpTrim(LegacyValues.toPhpString(body.get("company_code")))
					.toUpperCase(java.util.Locale.ROOT);
			if (normalized.isEmpty()) {
				throw new LegacyApiException(400, "field_required", null, Map.of("field", "company_code"));
			}
			if (!normalized.matches("[A-Za-z0-9]{5,32}")) {
				throw new LegacyApiException(400, "company_code_invalid");
			}
			if (store.companyCodeIsTaken(normalized, companyId)) {
				throw new LegacyApiException(400, "company_code_taken");
			}
			columns.put("company_code", normalized);
		}

		if (body.get("first_name") != null) {
			columns.put("first_name", body.get("first_name"));
		}
		if (body.get("last_name") != null) {
			columns.put("last_name", body.get("last_name"));
		}
		if (body.get("country_code") != null) {
			columns.put("country_code", body.get("country_code"));
		}

		if (body.containsKey("email")) {
			String trimmed = LegacyValues.phpTrim(LegacyValues.toPhpString(body.get("email")));
			String email = trimmed.isEmpty() ? null : trimmed;
			if (email != null && store.companyEmailIsTaken(email, companyId)) {
				throw new LegacyApiException(409, "already_exists", null, Map.of("field", "email"));
			}
			columns.put("email", email);
		}

		if (body.containsKey("main_branch_address")) {
			String address = LegacyValues.phpTrim(LegacyValues.toPhpString(body.get("main_branch_address")));
			if (address.isEmpty()) {
				throw new LegacyApiException(400, "field_required", null, Map.of("field", "main_branch_address"));
			}
			columns.put("main_branch_address", address);
		}

		if (body.containsKey("company_activity_id")) {
			long id = LegacyValues.toPhpLong(body.get("company_activity_id"));
			if (id <= 0) {
				throw new LegacyApiException(400, "field_required", null, Map.of("field", "company_activity_id"));
			}
			if (!store.companyActivityExists(id)) {
				throw new LegacyApiException(400, "field_required");
			}
			columns.put("company_activity_id", id);
		}

		if (body.containsKey("company_title_id")) {
			long id = LegacyValues.toPhpLong(body.get("company_title_id"));
			if (id <= 0) {
				throw new LegacyApiException(400, "field_required", null, Map.of("field", "company_title_id"));
			}
			if (!store.companyTitleExists(id)) {
				throw new LegacyApiException(400, "field_required");
			}
			columns.put("company_title_id", id);
		}

		if (body.containsKey("company_size_id")) {
			long id = LegacyValues.toPhpLong(body.get("company_size_id"));
			if (id <= 0) {
				throw new LegacyApiException(400, "field_required", null, Map.of("field", "company_size_id"));
			}
			if (!store.companySizeExists(id)) {
				throw new LegacyApiException(400, "field_required");
			}
			columns.put("company_size_id", id);
		}

		if (columns.isEmpty()) {
			throw new LegacyApiException(400, "nothing_to_update");
		}

		try {
			store.updateColumns(companyId, columns);
		} catch (RuntimeException ex) {
			if (isDuplicateEntry(ex)) {
				throw new LegacyApiException(409, "already_exists", null, Map.of("field", "email"));
			}
			throw ex;
		}
		return store.findById(companyId);
	}

	/**
	 * {@code db_is_duplicate_entry()}: MySQL 1062 only, never every SQLSTATE
	 * 23000 -- which would also catch NOT NULL and foreign-key violations.
	 * Spring wraps the driver's exception, so the whole cause chain is searched.
	 */
	private static boolean isDuplicateEntry(Throwable ex) {
		StringBuilder text = new StringBuilder();
		for (Throwable current = ex; current != null; current = current.getCause()) {
			if (current.getMessage() != null) {
				text.append(current.getMessage()).append('\n');
			}
			if (current.getCause() == current) {
				break;
			}
		}
		String message = text.toString();
		return message.contains("1062")
				|| message.toLowerCase(java.util.Locale.ROOT).contains("duplicate entry");
	}

	/** {@code upload_logo.php}. */
	public Map<String, Object> uploadLogo(long companyId, MultipartFile file) {
		String url = uploads.store(file, "logos");
		if (url == null) {
			throw new LegacyApiException(400, "no_file_uploaded");
		}
		store.updateColumns(companyId, Map.of("logo_url", url));
		return store.findById(companyId);
	}

	/** {@code upload_commercial_reg.php}. */
	public Map<String, Object> uploadCommercialReg(long companyId, MultipartFile file) {
		String url = uploads.store(file, "commercial");
		if (url == null) {
			throw new LegacyApiException(400, "no_file_uploaded");
		}
		store.updateColumns(companyId, Map.of("commercial_reg_url", url));
		return store.findById(companyId);
	}
}
