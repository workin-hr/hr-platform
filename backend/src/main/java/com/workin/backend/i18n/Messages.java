package com.workin.backend.i18n;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * The single seam services use for localized text. Key constants live
 * only in MessageKeys — never introduce a message string anywhere
 * else (the PermissionKeys rule).
 */
@Component
public class Messages {

	private final MessageSource messageSource;

	public Messages(MessageSource messageSource) {
		this.messageSource = messageSource;
	}

	public String get(String key, Object... args) {
		return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
	}

}
