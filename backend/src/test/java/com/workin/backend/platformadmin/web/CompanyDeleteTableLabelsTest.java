package com.workin.backend.platformadmin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.workin.legacy.profile.LegacyCompanyDelete;

/**
 * Every table the company delete page counts has a label in both of this
 * surface's catalogues, and no label outlives its table.
 *
 * <p>{@code company-delete.jte} builds the key from the table name, which
 * {@code AdminTemplateMessageKeyTest} does not match, and a missing key renders
 * as the key itself with nothing failing.
 */
class CompanyDeleteTableLabelsTest {

	private static final String PREFIX = "company_delete_table_";

	@ParameterizedTest
	@ValueSource(strings = {"i18n/admin-own.properties", "i18n/admin-own_ar.properties"})
	void everyCountedTableHasALabel(String catalogue) throws IOException {
		Properties labels = new Properties();
		try (InputStream in = CompanyDeleteTableLabelsTest.class.getClassLoader().getResourceAsStream(catalogue)) {
			assertThat(in).as(catalogue).isNotNull();
			labels.load(new InputStreamReader(in, StandardCharsets.UTF_8));
		}
		Set<String> labelled = labels.stringPropertyNames().stream()
				.filter(key -> key.startsWith(PREFIX) && !labels.getProperty(key).isBlank())
				.map(key -> key.substring(PREFIX.length()))
				.collect(Collectors.toSet());

		assertThat(labelled).as(catalogue)
				.containsExactlyInAnyOrderElementsOf(LegacyCompanyDelete.countedTables());
	}
}
