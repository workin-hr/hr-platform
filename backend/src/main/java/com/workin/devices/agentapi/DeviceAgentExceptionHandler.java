package com.workin.devices.agentapi;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Keeps the agent's answers in the agent's shape. A 5xx tells it to hold the
 * batch and retry; a 4xx it cannot fix by retrying is named so its log says why.
 */
@RestControllerAdvice(assignableTypes = DeviceAgentController.class)
@ConditionalOnProperty(name = "app.devices.agents.enabled", havingValue = "true")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DeviceAgentExceptionHandler {

	private static final Logger LOG = LoggerFactory.getLogger(DeviceAgentExceptionHandler.class);

	@ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class})
	public ResponseEntity<Map<String, Object>> unreadable(Exception ex) {
		return DeviceAgentController.error(HttpStatus.BAD_REQUEST, "unreadable_body");
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, Object>> unexpected(Exception ex) {
		LOG.error("device agent request failed", ex);
		return DeviceAgentController.error(HttpStatus.INTERNAL_SERVER_ERROR, "internal");
	}
}
