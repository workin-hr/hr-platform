package com.workin.legacy.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.legacy.LegacyJsonBody;
import com.workin.legacy.LegacyPagination;
import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.auth.LegacyRequestGuard;
import com.workin.legacy.authorization.LegacyHrPermissionEnforcer;
import com.workin.legacy.authorization.LegacyHrPermissionKey;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyApiResponse;
import com.workin.legacy.wire.LegacyMessages;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Wave 13.3: {@code company_settings/*.php} plus the two catalogue endpoints
 * {@code setting_definitions/list.php} and
 * {@code setting_allowed_values/list.php}.
 *
 * <h2>Three authority levels across eight endpoints</h2>
 * <ul>
 * <li>the five {@code company_settings} routes -- COMPANY_ADMIN or HR,
 *     <b>plus</b> the {@code can_company_settings} permission and an active
 *     company;</li>
 * <li>{@code setting_definitions/list.php} -- COMPANY_ADMIN or HR, with
 *     <b>no</b> permission gate and no company-active check, even though it is
 *     the definitions side of the very settings the permission guards;</li>
 * <li>{@code setting_allowed_values/list.php} -- <b>unauthenticated</b>.
 *     No {@code requireAuth()} at all.</li>
 * </ul>
 *
 * <p>The last one is the surprise, and it is legacy's: the allowed-value
 * catalogue is world-readable while the definitions that name those values need
 * an administrative role. Both tables are platform configuration with no
 * {@code company_id} and no company data in them, so nothing tenant-scoped
 * leaks -- but the asymmetry is real and is preserved rather than harmonised.
 */
@RestController
public class LegacySettingsController {

	private final LegacyCompanySettingsService settingsService;
	private final LegacySettingsStore store;
	private final LegacyRequestGuard requestGuard;
	private final LegacyHrPermissionEnforcer permissionEnforcer;
	private final LegacyMessages messages;

	public LegacySettingsController(
			LegacyCompanySettingsService settingsService, LegacySettingsStore store,
			LegacyRequestGuard requestGuard, LegacyHrPermissionEnforcer permissionEnforcer,
			LegacyMessages messages) {
		this.settingsService = settingsService;
		this.store = store;
		this.requestGuard = requestGuard;
		this.permissionEnforcer = permissionEnforcer;
		this.messages = messages;
	}

	// ---------------- company_settings ----------------

	@RequestMapping("/apis/api/company_settings/list.php")
	public LegacyApiResponse list(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = settingsActor();
		return LegacyApiResponse.ok(message(request, "ok"),
				settingsService.list(context.companyId(), locale(request)));
	}

	@RequestMapping("/apis/api/company_settings/one.php")
	public LegacyApiResponse one(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyRequestContext context = settingsActor();
		LegacyQueryParameters query = LegacyQueryParameters.parse(request.getQueryString());
		return LegacyApiResponse.ok(message(request, "ok"), settingsService.one(
				context.companyId(), longOrNull(query.value("setting_definition_id")),
				text(query.value("setting_key")), locale(request)));
	}

	@RequestMapping("/apis/api/company_settings/options.php")
	public LegacyApiResponse options(HttpServletRequest request) {
		requireMethod(request, "GET");
		settingsActor();
		LegacyQueryParameters query = LegacyQueryParameters.parse(request.getQueryString());
		Object raw = query.value("setting_key");
		// `!empty($_GET['setting_key'])` -- so "0" is treated as absent and the
		// endpoint answers the whole map instead of that one key.
		String settingKey = LegacyValues.isPhpEmpty(raw)
				? null : LegacyValues.phpTrim(LegacyValues.toPhpString(raw));
		return LegacyApiResponse.ok(message(request, "ok"),
				settingsService.options(settingKey, locale(request)));
	}

	@RequestMapping("/apis/api/company_settings/create.php")
	public ResponseEntity<LegacyApiResponse> create(HttpServletRequest request) {
		requireMethod(request, "POST");
		LegacyRequestContext context = settingsActor();
		Map<String, Object> body = LegacyJsonBody.read(request);
		Map<String, Object> row = settingsService.create(
				context.companyId(), longOrNull(body.get("setting_definition_id")),
				text(body.get("setting_key")), body.get("values"), locale(request));
		return ResponseEntity.status(201).body(LegacyApiResponse.ok(message(request, "ok"), row));
	}

	/**
	 * {@code update.php} reads its identifier from the <b>body first, query
	 * string second</b> -- the only endpoint in this module that falls back to
	 * the query string for it.
	 */
	@RequestMapping("/apis/api/company_settings/update.php")
	public LegacyApiResponse update(HttpServletRequest request) {
		requireMethod(request, "PUT");
		LegacyRequestContext context = settingsActor();
		Map<String, Object> body = LegacyJsonBody.read(request);
		LegacyQueryParameters query = LegacyQueryParameters.parse(request.getQueryString());

		Long definitionId = longOrNull(body.get("setting_definition_id"));
		if (definitionId == null || definitionId <= 0) {
			definitionId = longOrNull(query.value("setting_definition_id"));
		}
		String settingKey = text(body.get("setting_key"));
		if (settingKey == null || settingKey.isEmpty()) {
			settingKey = text(query.value("setting_key"));
		}

		return LegacyApiResponse.ok(message(request, "ok"), settingsService.update(
				context.companyId(), definitionId, settingKey, body.get("values"), locale(request)));
	}

	/** {@code delete.php}: {@code ok(OK)} with no {@code data} key. */
	@RequestMapping("/apis/api/company_settings/delete.php")
	public LegacyApiResponse delete(HttpServletRequest request) {
		requireMethod(request, "DELETE");
		LegacyRequestContext context = settingsActor();
		LegacyQueryParameters query = LegacyQueryParameters.parse(request.getQueryString());
		settingsService.delete(context.companyId(),
				LegacyValues.toPhpLong(query.value("setting_definition_id")),
				LegacyValues.toPhpLong(query.value("id")));
		return LegacyApiResponse.ok(message(request, "ok"), null);
	}

	// ---------------- setting_definitions ----------------

	/**
	 * {@code setting_definitions/list.php}: paginated {@code SELECT *} with a
	 * {@code label} and the three description keys appended to each row.
	 *
	 * <p>No {@code can_company_settings} gate and no {@code requireCompanyActive}
	 * -- a suspended company's admin can still read the catalogue.
	 */
	@RequestMapping("/apis/api/setting_definitions/list.php")
	public LegacyApiResponse definitions(HttpServletRequest request) {
		requireMethod(request, "GET");
		requestGuard.requireAuth(LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR);

		LegacyQueryParameters query = LegacyQueryParameters.parse(request.getQueryString());
		LegacyPagination.Params pagination = LegacyPagination.params(query);
		// `!empty($_GET['search'])` then a second `!== ''` check after trimming,
		// so a whitespace-only search filters nothing at all.
		String search = null;
		Object rawSearch = query.value("search");
		if (!LegacyValues.isPhpEmpty(rawSearch)) {
			String trimmed = LegacyValues.phpTrim(LegacyValues.toPhpString(rawSearch));
			search = trimmed.isEmpty() ? null : trimmed;
		}

		long total = store.countDefinitions(search);
		String locale = locale(request);
		List<Map<String, Object>> rows = new ArrayList<>();
		for (Map<String, Object> row : store.definitionRows(
				search, (int) pagination.limit(), (int) pagination.offset())) {
			Map<String, Object> shaped = new LinkedHashMap<>(row);
			shaped.put("label", LegacySettingLabels.pick(locale,
					nullableText(row.get("label_ar")), nullableText(row.get("label_en")),
					LegacyValues.toPhpString(row.get("setting_key"))));
			shaped.putAll(LegacySettingLabels.descriptionFields(locale,
					nullableText(row.get("description_ar")), nullableText(row.get("description_en"))));
			rows.add(shaped);
		}
		return LegacyApiResponse.ok(message(request, "ok"), rows,
				LegacyPagination.meta(total, pagination));
	}

	/**
	 * {@code setting_allowed_values/list.php} -- <b>unauthenticated</b>, and it
	 * 404s on an unknown definition before paginating.
	 *
	 * <p>{@code required($_GET, ['setting_definition_id'])} rejects a missing or
	 * empty parameter with {@code field_required}, but {@code "abc"} passes the
	 * guard and casts to 0, which then matches no definition and 404s.
	 */
	@RequestMapping("/apis/api/setting_allowed_values/list.php")
	public LegacyApiResponse allowedValues(HttpServletRequest request) {
		requireMethod(request, "GET");
		LegacyQueryParameters query = LegacyQueryParameters.parse(request.getQueryString());
		Object raw = query.value("setting_definition_id");
		if (raw == null || "".equals(raw)) {
			throw new LegacyApiException(400, "field_required", null,
					Map.of("field", "setting_definition_id"));
		}
		long definitionId = LegacyValues.toPhpLong(raw);
		if (!store.definitionExists(definitionId)) {
			throw new LegacyApiException(404, "not_found");
		}

		LegacyPagination.Params pagination = LegacyPagination.params(query);
		long total = store.countAllowedValues(definitionId);
		String locale = locale(request);
		List<Map<String, Object>> rows = new ArrayList<>();
		for (Map<String, Object> row : store.allowedValueRows(
				definitionId, (int) pagination.limit(), (int) pagination.offset())) {
			Map<String, Object> shaped = new LinkedHashMap<>(row);
			shaped.put("label", LegacySettingLabels.pick(locale,
					nullableText(row.get("label_ar")), nullableText(row.get("label_en")),
					LegacyValues.toPhpString(row.get("value"))));
			rows.add(shaped);
		}
		return LegacyApiResponse.ok(message(request, "ok"), rows,
				LegacyPagination.meta(total, pagination));
	}

	// ---------------- shared ----------------

	/** {@code requireAuth([ADMIN, HR]); require_company_settings_access(); requireCompanyActive();} */
	private LegacyRequestContext settingsActor() {
		LegacyRequestContext context = requestGuard.requireAuth(
				LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR);
		permissionEnforcer.require(LegacyHrPermissionKey.CAN_COMPANY_SETTINGS);
		requestGuard.requireCompanyActive(context.companyId());
		return context;
	}

	private static void requireMethod(HttpServletRequest request, String expected) {
		if (!expected.equals(request.getMethod())) {
			throw new LegacyApiException(405, "invalid_method");
		}
	}

	private static Long longOrNull(Object raw) {
		return raw == null ? null : LegacyValues.toPhpLong(raw);
	}

	/**
	 * A null column stays null rather than becoming {@code ""}: the difference
	 * decides whether {@code pick_label} skips to the other language or trims
	 * to empty and takes the fallback.
	 */
	private static String nullableText(Object raw) {
		return raw == null ? null : LegacyValues.toPhpString(raw);
	}

	private static String text(Object raw) {
		return raw == null ? null : LegacyValues.phpTrim(LegacyValues.toPhpString(raw));
	}

	private String locale(HttpServletRequest request) {
		return messages.resolveLocale(request);
	}

	private String message(HttpServletRequest request, String key) {
		return messages.translate(locale(request), key, null);
	}
}
