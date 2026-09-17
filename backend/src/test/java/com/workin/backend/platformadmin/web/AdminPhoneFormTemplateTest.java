package com.workin.backend.platformadmin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Legacy's number checks on the forms that take a phone (<b>#214 item 3</b>, D-261).
 *
 * <p>{@code phone-form-bind.js} binds any form with a {@code country_code} and a {@code phone}.
 * A page's own test sees the markup, not a script that never loaded or loaded without the
 * rules it judges by -- and with no rules the script refuses every number. So this reads the
 * templates: such a form draws legacy's country select and carries its message, and its page
 * hands the layout the rules, which is what loads the scripts. It fails closed: the forms it
 * finds are named.
 */
class AdminPhoneFormTemplateTest {

	private static final Path TEMPLATES = Path.of("src/main/jte/admin");

	/** A start tag's attributes may hold a quoted {@code >}. */
	private static final Pattern FORM = Pattern.compile("<form\\b((?:[^>\"]|\"[^\"]*\")*)>(.*?)</form>", Pattern.DOTALL);

	@Test
	void everyFormTakingAPhoneDrawsLegacysCountrySelectAndItsPageHandsTheLayoutTheRules() throws IOException {
		Map<String, List<String>> offenders = new TreeMap<>();
		List<String> phoneForms = new ArrayList<>();
		try (var files = Files.list(TEMPLATES)) {
			for (Path template : files.filter(file -> file.toString().endsWith(".jte")).sorted().toList()) {
				String name = template.getFileName().toString();
				String source = Files.readString(template, StandardCharsets.UTF_8);
				Matcher form = FORM.matcher(source);
				while (form.find()) {
					String body = form.group(2);
					if (!Pattern.compile("<input\\b[^>]*\\bname=\"phone\"").matcher(body).find()
							|| !body.contains("name=\"country_code\"")) {
						continue;
					}
					phoneForms.add(name);
					List<String> missing = new ArrayList<>();
					if (!Pattern.compile("<select\\b[^>]*\\bname=\"country_code\"").matcher(body).find()) {
						missing.add("country_code is not a select of the active countries");
					}
					if (!form.group(1).contains("data-invalid-phone-msg=\"${t.apply(\"error_invalid_phone\")}\"")) {
						missing.add("form without data-invalid-phone-msg");
					}
					if (!source.contains("phoneCountryRules = phone.rules()")) {
						missing.add("the layout is not handed the rules, so no phone script loads");
					}
					String unguarded = readUnlessGuardedLikeTheForm(source, form.start());
					if (unguarded != null) {
						missing.add(unguarded);
					}
					if (!missing.isEmpty()) {
						offenders.put(name, missing);
					}
				}
			}
		}
		assertThat(phoneForms).as("legacy's _company_form.php and _employee_form.php")
				.containsExactly("companies.jte", "employees.jte");
		assertThat(offenders).isEmpty();
	}

	/**
	 * The page reads the countries under the condition its form renders under, so a render
	 * without the form reads none and loads no phone script. Null when it does; otherwise why not.
	 */
	private static String readUnlessGuardedLikeTheForm(String source, int formStart) {
		Matcher read = Pattern.compile("!\\{var phone = (.+?) \\? phoneCountries\\.get\\(\\) : PhoneCountryChoices\\.NONE;}")
				.matcher(source);
		if (!read.find()) {
			return "the countries are not read under the form's condition";
		}
		String opening = "@if(" + read.group(1) + ")";
		for (int open = source.indexOf(opening); open >= 0 && open < formStart; open = source.indexOf(opening, open + 1)) {
			Matcher directive = Pattern.compile("@if\\(|@endif").matcher(source);
			directive.region(open, source.length());
			int depth = 0;
			while (directive.find()) {
				depth += directive.group().equals("@endif") ? -1 : 1;
				if (depth == 0) {
					break;
				}
			}
			if (depth == 0 && directive.start() > formStart) {
				return null;
			}
		}
		return "the countries are read under " + read.group(1) + ", and no @if on it wraps the form";
	}

	@Test
	void theLayoutLoadsThePhoneScriptsOnlyWithRulesAndTheRulesFirst() throws IOException {
		String layout = Files.readString(TEMPLATES.resolve("layout.jte"), StandardCharsets.UTF_8);
		Matcher block = Pattern.compile("@if\\(phoneCountryRules != null\\)(.*?)@endif", Pattern.DOTALL).matcher(layout);

		assertThat(block.find()).as("the scripts are conditional on the rules").isTrue();
		assertThat(block.group(1).strip().lines().map(String::strip).toList()).containsExactly(
				"<script src=\"/admin/_assets/phone-countries-rules.js\" data-rules=\"${phoneCountryRules}\"></script>",
				"<script src=\"/admin/_assets/phone-validator.js\"></script>",
				"<script src=\"/admin/_assets/phone-form-bind.js\"></script>");
		assertThat(layout.replace(block.group(), ""))
				.as("no phone script loads outside the block, where it could run without rules")
				.doesNotContainPattern("<script\\b[^>]*phone-(form-bind|countries-rules|validator)\\.js");
	}
}
