package com.workin.backend.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The extensibility contract's enforcement (spec §Extensibility): every
 * MessageKeys constant resolves in the English base, and every
 * translation catalog has exactly the base's key set — a new language
 * cannot silently ship holes. Pure unit test, no Spring context.
 */
class MessageCatalogSyncTest {

	private static Properties load(Resource resource) throws Exception {
		Properties props = new Properties();
		try (InputStreamReader reader = new InputStreamReader(
				resource.getInputStream(), StandardCharsets.UTF_8)) {
			props.load(reader);
		}
		return props;
	}

	@Test
	void everyMessageKeyConstantResolvesInTheEnglishBase() throws Exception {
		Properties base = load(new PathMatchingResourcePatternResolver()
				.getResource("classpath:i18n/messages.properties"));
		for (Field field : MessageKeys.class.getDeclaredFields()) {
			String key = (String) field.get(null);
			assertThat(base.containsKey(key))
					.as("MessageKeys.%s = '%s' missing from messages.properties", field.getName(), key)
					.isTrue();
		}
		// The one enumerable dynamic-key family (schedule day names).
		for (int dow = 0; dow <= 6; dow++) {
			assertThat(base.containsKey("day." + dow))
					.as("day.%d missing from messages.properties", dow)
					.isTrue();
		}
	}

	@Test
	void everyTranslationCatalogHasExactKeyParityWithTheBase() throws Exception {
		PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
		Properties base = load(resolver.getResource("classpath:i18n/messages.properties"));
		Resource[] translations = resolver.getResources("classpath*:i18n/messages_*.properties");
		assertThat(translations).as("at least the Arabic catalog exists").isNotEmpty();
		for (Resource translation : translations) {
			Properties props = load(translation);
			assertThat(props.keySet())
					.as("%s must have exactly the base catalog's keys", translation.getFilename())
					.containsExactlyInAnyOrderElementsOf(
							base.keySet().stream().map(Object::toString).toList());
		}
	}

}
