package com.workin.legacy.auth.otp;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.workin.legacy.auth.whatsapp.LegacyWhatsAppSender;

/**
 * Replaces the outbound WhatsApp gateway with {@link RecordingWhatsAppSender}
 * for the tests that exercise an OTP flow.
 *
 * <p>One shared class rather than a nested {@code @TestConfiguration} per test:
 * nested ones are still picked up by the component scan, so two tests each
 * declaring a {@code recordingWhatsAppSender} bean collide with
 * {@code BeanDefinitionOverrideException} the moment both are on the classpath.
 * Imported explicitly by every test that needs it.
 */
@TestConfiguration
public class RecordingWhatsAppConfiguration {

	@Bean
	@Primary
	public LegacyWhatsAppSender recordingWhatsAppSender() {
		return new RecordingWhatsAppSender();
	}
}
