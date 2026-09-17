package com.workin.backend.platformadmin.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.junit.jupiter.api.Test;

/**
 * The FAQ and banner selects label their options as legacy does: {@code platform_*} and
 * {@code action_*}, where the port showed the stored value. The template gate reads only literal
 * keys, and a missing key renders as the key itself with nothing failing, so each constant's key
 * is checked against both catalogues here, and the offered lists against every constant.
 */
class ContentOptionLabelsTest {

	@Test
	void everyPlatformAndActionHasALabelInBothLanguagesAndIsOffered() throws IOException {
		for (String catalogue : new String[] {"i18n/admin-messages.properties", "i18n/admin-messages_ar.properties"}) {
			Properties messages = new Properties();
			try (InputStream in = getClass().getClassLoader().getResourceAsStream(catalogue)) {
				assertThat(in).as(catalogue).isNotNull();
				messages.load(new InputStreamReader(in, StandardCharsets.UTF_8));
			}
			for (Faq.Platform platform : Faq.Platform.values()) {
				assertThat(messages.getProperty(platform.labelKey())).as("%s in %s", platform.labelKey(), catalogue).isNotBlank();
			}
			for (Banner.Action action : Banner.Action.values()) {
				assertThat(messages.getProperty(action.labelKey())).as("%s in %s", action.labelKey(), catalogue).isNotBlank();
			}
		}
		assertThat(Faq.Platform.offered()).as("every platform, both first as legacy's select")
				.containsExactlyInAnyOrder(Faq.Platform.values()).first().isEqualTo(Faq.Platform.BOTH);
		assertThat(Banner.Action.offered()).as("every action, in legacy's order")
				.containsExactly(Banner.Action.NONE, Banner.Action.EXTERNAL_URL, Banner.Action.WHATSAPP, Banner.Action.INTERNAL_ROUTE);
	}
}
