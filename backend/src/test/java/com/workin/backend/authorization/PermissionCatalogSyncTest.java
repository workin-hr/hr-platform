package com.workin.backend.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import com.workin.backend.AbstractIntegrationTest;

/**
 * Keeps {@link PermissionKeys} in exact bidirectional sync with V4's
 * permissions catalog -- docs/architecture/authorization-model.md §3:
 * "no permission string may be introduced anywhere else in the
 * codebase without a corresponding row" in the permissions table. A
 * constant without a row is a phantom capability; a row without a
 * constant cannot be referenced type-safely and invites string
 * literals.
 */
class PermissionCatalogSyncTest extends AbstractIntegrationTest {

	@Autowired
	@Qualifier("flywayDataSource")
	private DataSource flywayDataSource;

	private static Set<String> constantValues() throws IllegalAccessException {
		Set<String> values = new HashSet<>();
		for (Field field : PermissionKeys.class.getDeclaredFields()) {
			if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
				values.add((String) field.get(null));
			}
		}
		return values;
	}

	@Test
	void permissionKeysConstantsMatchTheCatalogExactly() throws IllegalAccessException {
		Set<String> catalog = new HashSet<>(new JdbcTemplate(flywayDataSource)
				.queryForList("SELECT permission_key FROM permissions", String.class));
		Set<String> constants = constantValues();

		Set<String> constantsWithoutRows = new HashSet<>(constants);
		constantsWithoutRows.removeAll(catalog);
		Set<String> rowsWithoutConstants = new HashSet<>(catalog);
		rowsWithoutConstants.removeAll(constants);

		assertThat(constantsWithoutRows)
				.as("PermissionKeys constants with no permissions-table row (phantom capabilities)")
				.isEmpty();
		assertThat(rowsWithoutConstants)
				.as("permissions-table rows with no PermissionKeys constant (unreferenceable capabilities)")
				.isEmpty();
	}

}
