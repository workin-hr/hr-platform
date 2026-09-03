package com.workin.devices.zkteco;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.workin.devices.QueryParameters;
import com.workin.devices.zkteco.ZkTecoAdmsService.Handshake;
import com.workin.devices.zkteco.ZkTecoAdmsService.Status;
import com.workin.devices.zkteco.ZkTecoAdmsService.Upload;

/**
 * The ZKTeco ADMS / PUSH SDK receiver's HTTP surface (D-164) -- four
 * device-facing routes, all device-initiated, all plain text.
 *
 * <p>This class owns only what is HTTP: where a parameter comes from, how the
 * body is obtained, and which status code a {@link Status} becomes. Every
 * decision behind those answers belongs to {@link ZkTecoAdmsService}, which is
 * also where the trust model is documented.
 *
 * <p>Exists only when {@code app.devices.ingest.enabled=true}: default closed,
 * together with its service and its security chain.
 */
@RestController
@RequestMapping(path = "/iclock", produces = MediaType.TEXT_PLAIN_VALUE)
@ConditionalOnProperty(name = "app.devices.ingest.enabled", havingValue = "true")
public class ZkTecoAdmsController {

	static final String OK = "OK";
	static final String UNREGISTERED = "ERROR: device is not registered";
	static final String INVALID_SERIAL = "ERROR: invalid SN";
	static final String MISSING_SERIAL = "ERROR: missing SN";
	static final String TOO_MANY_RECORDS = "ERROR: too many records";
	static final String BODY_TOO_LARGE = "ERROR: body too large";

	private final ZkTecoAdmsService receiver;
	private final int maxBodyBytes;

	public ZkTecoAdmsController(
			ZkTecoAdmsService receiver, @Value("${app.devices.ingest.max-body-bytes}") int maxBodyBytes) {
		this.receiver = receiver;
		this.maxBodyBytes = maxBodyBytes;
	}

	/** {@code GET /iclock/cdata?SN=..&options=all&pushver=..&DeviceType=..} */
	@GetMapping("/cdata")
	public ResponseEntity<String> handshake(
			@RequestParam("SN") String serialNumber,
			@RequestParam(name = "pushver", required = false) String pushVersion,
			@RequestParam(name = "DeviceType", required = false) String deviceType,
			HttpServletRequest request) {
		Handshake handshake = receiver.handshake(serialNumber, pushVersion, deviceType, request.getRemoteAddr());
		if (handshake.status() != Status.OK) {
			return refuse(handshake.status());
		}
		return ResponseEntity.ok(ZkTecoHandshake.response(serialNumber, handshake.configured(), Instant.now()));
	}

	/**
	 * {@code POST /iclock/cdata?SN=..&table=ATTLOG|OPERLOG|options|...&Stamp=..}
	 *
	 * <p>Parameters come from {@link QueryParameters}, not {@code @RequestParam}:
	 * on a POST the latter reads the servlet parameter map, which for a
	 * form-urlencoded content type is built by consuming the body -- and this
	 * handler's body is the punch batch.
	 */
	@PostMapping("/cdata")
	public ResponseEntity<String> upload(HttpServletRequest request) throws IOException {
		Map<String, String> query = QueryParameters.parse(request.getQueryString());
		String serialNumber = query.get("SN");
		if (serialNumber == null) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(MISSING_SERIAL);
		}
		String body = readBody(request);
		if (body == null) {
			return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(BODY_TOO_LARGE);
		}
		Upload upload = receiver.upload(
				serialNumber, query.get("table"), query.get("Stamp"), body, request.getRemoteAddr());
		if (upload.status() != Status.OK) {
			return refuse(upload.status());
		}
		return ResponseEntity.ok(
				upload.acknowledged() == null ? OK : OK + ": " + upload.acknowledged());
	}

	/** {@code GET /iclock/getrequest?SN=..} -- the command poll, and therefore the heartbeat. */
	@GetMapping("/getrequest")
	public ResponseEntity<String> getRequest(@RequestParam("SN") String serialNumber, HttpServletRequest request) {
		Status status = receiver.poll(serialNumber, request.getRemoteAddr());
		return status == Status.OK ? ResponseEntity.ok(OK) : refuse(status);
	}

	/**
	 * {@code POST /iclock/devicecmd} -- {@code ID=..&Return=..&CMD=..};
	 * acknowledged and logged. Same body-preserving parameter read as
	 * {@link #upload}.
	 */
	@PostMapping("/devicecmd")
	public ResponseEntity<String> deviceCommandResult(HttpServletRequest request) throws IOException {
		String serialNumber = QueryParameters.parse(request.getQueryString()).get("SN");
		if (serialNumber == null) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(MISSING_SERIAL);
		}
		String body = readBody(request);
		if (body == null) {
			return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(BODY_TOO_LARGE);
		}
		Status status = receiver.commandResult(serialNumber, body);
		return status == Status.OK ? ResponseEntity.ok(OK) : refuse(status);
	}

	/** The one place a receiver outcome becomes a status code. */
	private static ResponseEntity<String> refuse(Status status) {
		return switch (status) {
			case INVALID_SERIAL -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(INVALID_SERIAL);
			case UNREGISTERED -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(UNREGISTERED);
			case TOO_MANY_RECORDS -> ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(TOO_MANY_RECORDS);
			case OK -> throw new IllegalArgumentException("OK is not a refusal");
		};
	}

	/**
	 * The body {@link DeviceRequestBodyFilter} captured before anything could
	 * consume it. The stream is only a fallback for a request that somehow
	 * arrived without passing that filter; null still means the cap was
	 * exceeded.
	 */
	private String readBody(HttpServletRequest request) throws IOException {
		Object captured = request.getAttribute(DeviceRequestBodyFilter.BODY_ATTRIBUTE);
		if (captured instanceof String body) {
			return body;
		}
		try (InputStream in = request.getInputStream()) {
			byte[] bytes = in.readNBytes(maxBodyBytes + 1);
			if (bytes.length > maxBodyBytes) {
				return null;
			}
			return new String(bytes, StandardCharsets.UTF_8);
		}
	}
}
