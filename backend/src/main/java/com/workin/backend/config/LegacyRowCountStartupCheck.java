package com.workin.backend.config;

import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when the legacy connection is configured to report *changed* rows instead of
 * rows *matched* by the {@code WHERE} clause.
 *
 * <p>Several frozen-PHP endpoints resolve a lost write race from a JDBC affected-row count of
 * zero: {@code LegacyAdvanceStore.updateEmployee} folds {@code AND status='pending'} into its
 * write, and {@code LegacyPenaltyStore.updateFields}/{@code deleteById} fold
 * {@code AND applied_to_payroll=0} into theirs. Reading zero as "the row stopped qualifying" is
 * only sound while the connection reports matched rows -- i.e. while {@code CLIENT_FOUND_ROWS} is
 * in effect, which MariaDB Connector/J controls with {@code useAffectedRows}.
 *
 * <p>With {@code useAffectedRows} enabled, an edit that resubmits the values already stored
 * changes no columns. The guard would misread that legal no-op as a lost race and reject it with
 * {@code 400 cannot_edit_non_pending_advance} or {@code 403 forbidden}. That is a silent data
 * -correctness regression driven purely by a connection string, so this fails closed at startup
 * rather than letting it surface as sporadic user-visible errors.
 *
 * <p>This guards the deployment-configuration vector only. A change in the driver's own default
 * is caught by {@code LegacyAdvancePayEndToEndTest} and
 * {@code LegacyPayrollBatchCalculateEndToEndTest}, which exercise the semantics against real
 * MariaDB on every build. See {@code docs/legacy/PR120_REVIEW_REMEDIATION.md}.
 */
@Component
@Profile("phase1-mysql")
public class LegacyRowCountStartupCheck implements ApplicationRunner {

	static final String OPTION = "useAffectedRows";

	private final String jdbcUrl;

	public LegacyRowCountStartupCheck(@Value("${app.legacy-db.jdbc-url:}") String jdbcUrl) {
		this.jdbcUrl = jdbcUrl;
	}

	@Override
	public void run(ApplicationArguments args) {
		verify(jdbcUrl);
	}

	/**
	 * @throws IllegalStateException when the URL enables {@code useAffectedRows}
	 */
	static void verify(String jdbcUrl) {
		if (!enablesAffectedRows(jdbcUrl)) {
			return;
		}
		throw new IllegalStateException(
				"Legacy datasource URL enables " + OPTION + ", which makes UPDATE report changed rows "
						+ "instead of matched rows. The advance and penalty race guards read a zero row "
						+ "count as a lost race, so an edit resubmitting already-stored values would be "
						+ "wrongly rejected. Remove " + OPTION + " from app.legacy-db.jdbc-url.");
	}

	private static boolean enablesAffectedRows(String jdbcUrl) {
		if (jdbcUrl == null) {
			return false;
		}
		int query = jdbcUrl.indexOf('?');
		if (query < 0) {
			return false;
		}
		for (String parameter : jdbcUrl.substring(query + 1).split("&")) {
			int equals = parameter.indexOf('=');
			String key = (equals < 0 ? parameter : parameter.substring(0, equals)).trim();
			if (!OPTION.equalsIgnoreCase(key)) {
				continue;
			}
			// A bare `useAffectedRows` with no value enables it, matching the driver's own
			// treatment of valueless boolean options.
			if (isEnabled(equals < 0 ? "true" : parameter.substring(equals + 1).trim())) {
				return true;
			}
		}
		return false;
	}

	private static boolean isEnabled(String value) {
		String normalized = value.toLowerCase(Locale.ROOT);
		return !("false".equals(normalized) || "0".equals(normalized) || normalized.isEmpty());
	}

}
