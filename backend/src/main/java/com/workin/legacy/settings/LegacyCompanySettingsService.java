package com.workin.legacy.settings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.legacy.LegacyValues;
import com.workin.legacy.wire.LegacyApiException;

/**
 * {@code company_settings/*.php} -- the five endpoints over the EAV settings
 * model.
 *
 * <h2>The identifier may arrive three ways, and the precedence matters</h2>
 * <p>Every endpoint accepts a {@code setting_definition_id} or a
 * {@code setting_key}, and {@code update.php} additionally reads either from
 * the <b>body first and the query string second</b>. The id wins whenever it is
 * positive, so a request carrying both a valid id and a contradictory key
 * silently uses the id.
 *
 * <h2>Writes are transactional, and a validation failure is not a 500</h2>
 * <p>PHP wraps its writes in {@code try { beginTransaction(); ... } catch
 * (Throwable) { rollBack(); fail(ERROR_WITH_MESSAGE, 500) }}, but
 * {@code fail()} ends in {@code exit} rather than throwing -- so a rejected
 * value leaves the transaction uncommitted (PDO rolls it back at shutdown) and
 * the client sees the <b>400</b> the validation raised, never the catch block's
 * 500. Reproduced by letting {@link LegacyApiException} propagate out of the
 * transactional method: Spring rolls back on it, and the wire handler renders
 * the status the exception carries.
 */
@Service
public class LegacyCompanySettingsService {

	private final LegacySettingsStore store;

	public LegacyCompanySettingsService(LegacySettingsStore store) {
		this.store = store;
	}

	/** {@code list.php}: every definition, with this company's selections attached. */
	public List<Map<String, Object>> list(long companyId, String locale) {
		List<LegacySettingsStore.Definition> definitions = store.allDefinitions();
		if (definitions.isEmpty()) {
			// PHP answers `ok(OK, [])` and returns before touching the other
			// two tables -- an empty array, not an empty object.
			return List.of();
		}
		List<Long> ids = definitions.stream().map(LegacySettingsStore.Definition::id).toList();
		Map<Long, List<LegacySettingsStore.AllowedValue>> options = store.allowedValuesFor(ids);

		Map<Long, List<LegacySettingsStore.AllowedValue>> selected = new LinkedHashMap<>();
		Map<Long, LegacySettingsStore.Selection> firstSelection = new LinkedHashMap<>();
		for (LegacySettingsStore.Selection row : store.selections(companyId, ids)) {
			selected.computeIfAbsent(row.definitionId(), key -> new ArrayList<>())
					.add(new LegacySettingsStore.AllowedValue(
							row.definitionId(), row.value(), row.labelAr(), row.labelEn()));
			firstSelection.putIfAbsent(row.definitionId(), row);
		}

		List<Map<String, Object>> out = new ArrayList<>();
		for (LegacySettingsStore.Definition definition : definitions) {
			LegacySettingsStore.Selection meta = firstSelection.get(definition.id());
			out.add(LegacyCompanySettingItem.of(locale, definition,
					meta == null ? 0L : meta.companySettingId(),
					meta == null ? null : meta.updatedAt(),
					options.getOrDefault(definition.id(), List.of()),
					selected.getOrDefault(definition.id(), List.of())));
		}
		return out;
	}

	/** {@code one.php}. */
	public Map<String, Object> one(long companyId, Long definitionId, String settingKey, String locale) {
		long resolved = resolveDefinition(definitionId, settingKey);
		if (resolved <= 0) {
			throw new LegacyApiException(404, "not_found");
		}
		LegacySettingsStore.Definition definition = store.definition(resolved);
		if (definition == null) {
			throw new LegacyApiException(404, "not_found");
		}
		return itemFromSelectionsOnly(companyId, definition, locale);
	}

	/**
	 * {@code options.php}.
	 *
	 * <p>Two shapes from one route: with {@code setting_key} it answers
	 * {@code {setting_key, options}} and <b>echoes the requested key even when
	 * no definition matches</b>, returning an empty option list rather than a
	 * 404. Without it, a map of every key to its options, ordered by
	 * {@code setting_key} rather than by {@code sort_order} -- the only place in
	 * this module that orders definitions alphabetically.
	 */
	public Map<String, Object> options(String settingKey, String locale) {
		if (settingKey != null) {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("setting_key", settingKey);
			out.put("options", optionsForKey(settingKey, locale));
			return out;
		}
		Map<String, Object> out = new LinkedHashMap<>();
		for (LegacySettingsStore.Definition definition : store.allDefinitions().stream()
				.sorted(java.util.Comparator.comparing(LegacySettingsStore.Definition::settingKey))
				.toList()) {
			out.put(definition.settingKey(), optionsForKey(definition.settingKey(), locale));
		}
		return out;
	}

	private List<Map<String, Object>> optionsForKey(String settingKey, String locale) {
		long definitionId = store.definitionIdForKey(settingKey);
		if (definitionId <= 0) {
			return List.of();
		}
		List<Map<String, Object>> out = new ArrayList<>();
		for (LegacySettingsStore.AllowedValue row : store.allowedValues(definitionId)) {
			out.add(LegacyCompanySettingItem.option(locale, row.value(), row.labelAr(), row.labelEn()));
		}
		return out;
	}

	/**
	 * {@code create.php} -- 201, and {@code already_exists} rather than an upsert.
	 *
	 * <p>Wrapped by {@link #persisting} so a genuine persistence failure -- two
	 * concurrent creates racing on the unique key, say -- answers legacy's
	 * {@code error_with_message} rather than D-084's generic 500.
	 */
	@Transactional(transactionManager = "legacyTransactionManager")
	public Map<String, Object> create(
			long companyId, Long definitionId, String settingKey, Object rawValues, String locale) {
		long resolved = resolveDefinition(definitionId, settingKey);
		LegacySettingsStore.Definition definition = resolved <= 0 ? null : store.definition(resolved);
		if (definition == null) {
			throw new LegacyApiException(404, "not_found");
		}
		List<String> values = normalizeValues(rawValues, definition);

		if (store.companySettingId(companyId, definition.id()) > 0) {
			throw new LegacyApiException(400, "already_exists");
		}

		return persisting(() -> {
			long companySettingId = store.insertCompanySetting(companyId, definition.id());
			writeValues(definition.id(), companySettingId, values);
			return itemWithParentLookup(companyId, definition, locale);
		});
	}

	/**
	 * {@code update.php} -- an upsert, and <b>an empty value list deletes the
	 * whole setting</b> rather than clearing its values.
	 *
	 * <p>That asymmetry is worth stating: {@code create} with an empty list
	 * inserts a {@code company_settings} row with no values, while
	 * {@code update} with an empty list removes the row entirely. The two
	 * endpoints reach opposite end states from the same input.
	 */
	@Transactional(transactionManager = "legacyTransactionManager")
	public Map<String, Object> update(
			long companyId, Long definitionId, String settingKey, Object rawValues, String locale) {
		long resolved = resolveDefinition(definitionId, settingKey);
		LegacySettingsStore.Definition definition = resolved <= 0 ? null : store.definition(resolved);
		if (definition == null) {
			throw new LegacyApiException(404, "not_found");
		}
		List<String> values = normalizeValues(rawValues, definition);

		if (values.isEmpty()) {
			return persisting(() -> {
				long existing = store.companySettingId(companyId, definition.id());
				if (existing > 0) {
					store.deleteCompanySetting(existing, companyId);
				}
				return itemWithParentLookup(companyId, definition, locale);
			});
		}

		Map<String, Long> allowed = requireAllowed(definition.id(), values);

		return persisting(() -> {
			long target = store.companySettingId(companyId, definition.id());
			if (target <= 0) {
				target = store.insertCompanySetting(companyId, definition.id());
			} else {
				store.touchCompanySetting(target, companyId);
			}
			store.deleteCompanySettingValues(target);
			for (String value : values) {
				store.insertCompanySettingValue(target, allowed.get(value));
			}
			return itemWithParentLookup(companyId, definition, locale);
		});
	}

	/**
	 * {@code delete.php}.
	 *
	 * <p>Three things worth keeping: deleting a setting that is not set is
	 * {@code ok} rather than 404; a <b>required</b> definition cannot be
	 * deleted at all; and the required check runs on the definition resolved
	 * from either input, so passing the {@code id} of a required setting is
	 * rejected just as passing its {@code setting_definition_id} is.
	 */
	@Transactional(transactionManager = "legacyTransactionManager")
	public void delete(long companyId, long definitionId, long companySettingId) {
		if (definitionId <= 0 && companySettingId <= 0) {
			throw new LegacyApiException(400, "field_required", null,
					Map.of("field", "setting_definition_id"));
		}
		long settingId = companySettingId;
		if (settingId <= 0) {
			settingId = store.companySettingId(companyId, definitionId);
		}
		if (settingId <= 0) {
			return;
		}
		long resolvedDefinition = definitionId;
		if (resolvedDefinition <= 0) {
			resolvedDefinition = store.definitionIdForCompanySetting(settingId, companyId);
		}
		if (resolvedDefinition > 0 && store.definitionIsRequired(resolvedDefinition)) {
			throw new LegacyApiException(400, "invalid_setting_key");
		}
		store.deleteCompanySetting(settingId, companyId);
	}

	// ---- shared helpers ----

	/**
	 * The item shape as {@code one.php} builds it: {@code company_setting_id}
	 * and {@code updated_at} come <b>only</b> from the selection join.
	 *
	 * <p>So a company_settings row that exists with <em>no</em> values reports
	 * {@code company_setting_id: 0} here, exactly as {@code list.php} does --
	 * the join returns nothing, and neither endpoint looks the parent row up
	 * separately.
	 */
	private Map<String, Object> itemFromSelectionsOnly(
			long companyId, LegacySettingsStore.Definition definition, String locale) {
		return buildItem(companyId, definition, locale, false);
	}

	/**
	 * The item shape as {@code build_company_setting_item()} builds it, for
	 * {@code create.php} and {@code update.php}.
	 *
	 * <p><b>This is not the same shape {@code one.php} returns, and the
	 * difference is legacy's.</b> Where {@code one.php} takes the parent id from
	 * the join, this looks it up with its own
	 * {@code SELECT id, updated_at FROM company_settings} -- so a setting with
	 * no values reports its <em>real</em> id and timestamp here and a
	 * <em>zero</em> and null there. A client that creates a setting with an
	 * empty value list and then re-reads it through {@code one.php} sees the id
	 * change to 0 without anything having been deleted.
	 *
	 * <p>Preserved rather than unified: the two shapes are separately
	 * observable, and picking either one for both endpoints would change a live
	 * response.
	 */
	private Map<String, Object> itemWithParentLookup(
			long companyId, LegacySettingsStore.Definition definition, String locale) {
		return buildItem(companyId, definition, locale, true);
	}

	private Map<String, Object> buildItem(
			long companyId, LegacySettingsStore.Definition definition, String locale,
			boolean lookUpParentRow) {
		List<LegacySettingsStore.AllowedValue> options = store.allowedValues(definition.id());
		List<LegacySettingsStore.Selection> selections =
				store.selections(companyId, List.of(definition.id()));

		List<LegacySettingsStore.AllowedValue> selected = new ArrayList<>();
		long companySettingId = 0L;
		String updatedAt = null;
		for (LegacySettingsStore.Selection row : selections) {
			selected.add(new LegacySettingsStore.AllowedValue(
					row.definitionId(), row.value(), row.labelAr(), row.labelEn()));
			if (companySettingId == 0L) {
				companySettingId = row.companySettingId();
				updatedAt = row.updatedAt();
			}
		}
		if (lookUpParentRow) {
			companySettingId = store.companySettingId(companyId, definition.id());
			updatedAt = companySettingId > 0 ? store.companySettingUpdatedAt(companySettingId) : null;
		}
		return LegacyCompanySettingItem.of(
				locale, definition, companySettingId, updatedAt, options, selected);
	}

	/**
	 * PHP's {@code catch (Throwable $e) { rollBack(); fail(ERROR_WITH_MESSAGE, 500, ...) }}.
	 *
	 * <p>A {@link LegacyApiException} passes straight through, because
	 * {@code fail()} ends in {@code exit} and so never reaches that catch -- a
	 * rejected value stays the 400 the validation raised. Anything else is a
	 * genuine persistence failure, and legacy answers those with its own keyed
	 * contract carrying the exception text rather than a generic 500.
	 *
	 * <p>The wrapper still throws, so Spring rolls the transaction back exactly
	 * as PDO's shutdown rollback does.
	 *
	 * <p>It does put an exception message on the wire. That is legacy's
	 * behaviour ({@code ['message' => $e->getMessage()]}) and is reproduced
	 * rather than sanitised, because a client parsing that field today would
	 * otherwise watch it vanish. A Phase-1 parity obligation, not a pattern to
	 * copy into new code.
	 */
	private <T> T persisting(java.util.function.Supplier<T> work) {
		try {
			return work.get();
		} catch (LegacyApiException ex) {
			throw ex;
		} catch (RuntimeException ex) {
			throw new LegacyApiException(500, "error_with_message", null,
					Map.of("message", String.valueOf(ex.getMessage())));
		}
	}

	/** The id wins over the key whenever it is positive. */
	private long resolveDefinition(Long definitionId, String settingKey) {
		long id = definitionId == null ? 0L : definitionId;
		if (id > 0) {
			return id;
		}
		String key = settingKey == null ? "" : settingKey.trim();
		if (key.isEmpty()) {
			throw new LegacyApiException(400, "field_required", null,
					Map.of("field", "setting_definition_id"));
		}
		return store.definitionIdForKey(key);
	}

	/**
	 * {@code $body['values']} normalization.
	 *
	 * <p>A missing key is {@code field_required}; a scalar is wrapped into a
	 * one-element list; blank entries are dropped <em>before</em> the
	 * duplicate filter; and the result keeps first-seen order.
	 * {@code array_unique} compares as strings, so {@code ["1", 1]} collapses
	 * to one value.
	 */
	private static List<String> normalizeValues(
			Object rawValues, LegacySettingsStore.Definition definition) {
		if (rawValues == null) {
			throw new LegacyApiException(400, "field_required", null, Map.of("field", "values"));
		}
		LinkedHashSet<String> values = new LinkedHashSet<>();
		// `is_array($raw_values)` is true for a JSON *object* as well as an
		// array, because json_decode(..., true) produces an associative array
		// for both. So {"a":"dark"} iterates to ["dark"], where treating only a
		// List as array-like would stringify the map to "Array" and reject it.
		// LegacyValues.phpArrayValues() is the repository's existing answer to
		// exactly this.
		Collection<?> asArray = LegacyValues.phpArrayValues(rawValues);
		if (asArray != null) {
			for (Object entry : asArray) {
				String text = LegacyValues.phpTrim(LegacyValues.toPhpString(entry));
				if (!text.isEmpty()) {
					values.add(text);
				}
			}
		} else {
			String text = LegacyValues.phpTrim(LegacyValues.toPhpString(rawValues));
			if (!text.isEmpty()) {
				values.add(text);
			}
		}
		List<String> out = new ArrayList<>(values);
		if (definition.isMulti() != 1 && out.size() > 1) {
			throw new LegacyApiException(400, "invalid_setting_key");
		}
		if (definition.isRequired() == 1 && out.isEmpty()) {
			throw new LegacyApiException(400, "invalid_setting_key");
		}
		return out;
	}

	private void writeValues(long definitionId, long companySettingId, List<String> values) {
		if (values.isEmpty()) {
			return;
		}
		Map<String, Long> allowed = requireAllowed(definitionId, values);
		for (String value : values) {
			store.insertCompanySettingValue(companySettingId, allowed.get(value));
		}
	}

	/** Every submitted value must be an allowed value of this definition. */
	private Map<String, Long> requireAllowed(long definitionId, List<String> values) {
		Map<String, Long> allowed = store.allowedValueIds(definitionId, values);
		for (String value : values) {
			if (!allowed.containsKey(value)) {
				throw new LegacyApiException(400, "invalid_setting_key");
			}
		}
		return allowed;
	}
}
