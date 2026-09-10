package com.workin.devices.zkteco;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The device is not a JSON client: it gets a plain-text status line, never
 * the platform {@code {code,message}} body or the PHP envelope. Scoped to
 * the receiver controller so neither of those renderers sees its requests.
 */
@RestControllerAdvice(assignableTypes = ZkTecoAdmsController.class)
@ConditionalOnProperty(name = "app.devices.ingest.enabled", havingValue = "true")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ZkTecoAdmsExceptionHandler {

	private static final Logger LOG = LoggerFactory.getLogger(ZkTecoAdmsExceptionHandler.class);

	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<String> missingParameter(MissingServletRequestParameterException ex) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.TEXT_PLAIN)
				.body("ERROR: missing " + ex.getParameterName());
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<String> unexpected(Exception ex) {
		// The device retries after ErrorDelay; the operator needs the cause.
		LOG.error("device receiver failed", ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.TEXT_PLAIN).body("ERROR");
	}
}
