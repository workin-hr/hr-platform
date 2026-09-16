package com.workin.devices.agentapi;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.workin.devices.DeviceDelivery;
import com.workin.devices.QueryParameters;
import com.workin.devices.agent.DeviceAgent;
import com.workin.devices.agentapi.DeviceAgentIngestService.ReportedDevice;
import com.workin.devices.agentapi.DeviceAgentIngestService.Submission;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The HTTP surface an on-premises agent calls. Authentication happens in
 * {@link DeviceAgentSecurityConfig}; by the time a handler runs, the principal
 * is an active agent.
 *
 * <p>Answers are small JSON objects for a program, not the platform's localised
 * {@code {code,message}} body: nobody reads these but the agent, which only
 * needs to know whether to mark a batch delivered, keep it, or stop.
 */
@RestController
@RequestMapping(path = DeviceAgentSecurityConfig.BASE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "app.devices.agents.enabled", havingValue = "true")
public class DeviceAgentController {

	private final DeviceAgentIngestService service;
	private final ObjectMapper json;
	private final int maxBodyBytes;

	public DeviceAgentController(DeviceAgentIngestService service, ObjectMapper json,
			@Value("${app.devices.agents.max-body-bytes}") int maxBodyBytes) {
		this.service = service;
		this.json = json;
		this.maxBodyBytes = maxBodyBytes;
	}

	/**
	 * {@code POST /api/v1/device-agents/punches?serial=..[&delivery=file]}, body:
	 * ATTLOG lines. {@code delivery=file} marks a USB export the operator gave the
	 * agent rather than a read from the terminal. The parameters come from the
	 * query string read by hand, like the receiver's, so no parameter lookup can
	 * consume the body first.
	 */
	@PostMapping("/punches")
	public ResponseEntity<Map<String, Object>> punches(
			@AuthenticationPrincipal DeviceAgent agent, HttpServletRequest request) throws IOException {
		Map<String, String> query = QueryParameters.parse(request.getQueryString());
		String serial = query.get("serial");
		String deliveryParameter = query.get("delivery");
		if (deliveryParameter != null && !"file".equals(deliveryParameter) && !"agent".equals(deliveryParameter)) {
			return error(HttpStatus.BAD_REQUEST, "invalid_delivery");
		}
		DeviceDelivery delivery = "file".equals(deliveryParameter) ? DeviceDelivery.FILE : DeviceDelivery.AGENT;
		String body = readBody(request);
		if (body == null) {
			return error(HttpStatus.PAYLOAD_TOO_LARGE, "body_too_large");
		}
		Submission submission = service.submit(agent, serial, body, request.getRemoteAddr(), delivery);
		return switch (submission.status()) {
			case OK -> {
				Map<String, Object> view = new LinkedHashMap<>();
				view.put("accepted", submission.accepted());
				view.put("stored", submission.stored());
				view.put("duplicates", submission.duplicates());
				view.put("unmatched", submission.unmatched());
				view.put("malformed", submission.malformed());
				yield ResponseEntity.ok(view);
			}
			case INVALID_SERIAL -> error(HttpStatus.BAD_REQUEST, "invalid_serial");
			case NOT_REGISTERED -> error(HttpStatus.NOT_FOUND, "device_not_registered");
			case TOO_MANY_RECORDS -> error(HttpStatus.PAYLOAD_TOO_LARGE, "too_many_records");
		};
	}

	/** {@code POST /api/v1/device-agents/heartbeat}, body: {@code {agent_version, devices: [...]}}. */
	@PostMapping("/heartbeat")
	public ResponseEntity<Map<String, Object>> heartbeat(
			@AuthenticationPrincipal DeviceAgent agent, HttpServletRequest request) throws IOException {
		String body = readBody(request);
		if (body == null) {
			return error(HttpStatus.PAYLOAD_TOO_LARGE, "body_too_large");
		}
		Map<String, Object> report;
		try {
			report = json.readValue(body, new TypeReference<Map<String, Object>>() { });
		} catch (JacksonException ex) {
			return error(HttpStatus.BAD_REQUEST, "unreadable_body");
		}
		List<ReportedDevice> devices = service.heartbeat(agent, report, request.getRemoteAddr());
		return ResponseEntity.ok(Map.of("devices", devices.stream()
				.map(device -> Map.of("serial", device.serialNumber(), "registered", device.registered()))
				.toList()));
	}

	/** Bounded, and read only here -- after the chain has authenticated the agent. */
	private String readBody(HttpServletRequest request) throws IOException {
		if (request.getContentLengthLong() > maxBodyBytes) {
			return null;
		}
		try (InputStream in = request.getInputStream()) {
			byte[] bytes = in.readNBytes(maxBodyBytes + 1);
			return bytes.length > maxBodyBytes ? null : new String(bytes, StandardCharsets.UTF_8);
		}
	}

	static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code) {
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Map.of("error", code));
	}
}
