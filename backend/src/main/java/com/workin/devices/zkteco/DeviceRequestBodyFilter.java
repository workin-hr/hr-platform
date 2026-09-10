package com.workin.devices.zkteco;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Captures a device request's body before anything else can consume it, and
 * enforces the size cap while doing so.
 *
 * <h2>Why a filter and not just a handler read</h2>
 * <p>A servlet container builds the parameter map for a POST whose content
 * type is {@code application/x-www-form-urlencoded} by <b>reading the
 * body</b>, and it does so the first time anything asks for a parameter.
 * Several things ask before a handler runs. Whichever one gets there first,
 * the handler's own read then returns nothing -- and for this receiver that
 * is not a 500 but a silent, permanent data loss: a batch of punches parses
 * as zero records, the device is told {@code OK}, and it drops them.
 *
 * <p>Reading here, ahead of the security chain and the dispatcher, is the
 * only placement that does not depend on knowing every component that might
 * touch a parameter. The bytes are then republished through the wrapper, so
 * anything downstream that does read the stream still sees the real body.
 *
 * <p>Registered only where the receiver itself is (see
 * {@link ZkTecoAdmsSecurityConfig}), so a deployment without devices has no
 * filter on any path.
 */
public class DeviceRequestBodyFilter extends OncePerRequestFilter {

	/** Where the captured body is published for the handler. */
	public static final String BODY_ATTRIBUTE = "com.workin.devices.body";

	private final int maxBodyBytes;

	public DeviceRequestBodyFilter(int maxBodyBytes) {
		this.maxBodyBytes = maxBodyBytes;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		byte[] body;
		try (InputStream in = request.getInputStream()) {
			body = in.readNBytes(maxBodyBytes + 1);
		}
		if (body.length > maxBodyBytes) {
			response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
			response.setContentType(MediaType.TEXT_PLAIN_VALUE);
			response.getWriter().write("ERROR: body too large");
			return;
		}
		request.setAttribute(BODY_ATTRIBUTE, new String(body, StandardCharsets.UTF_8));
		chain.doFilter(new CachedBodyRequest(request, body), response);
	}

	/** Republishes the bytes this filter already took, so a later read still works. */
	private static final class CachedBodyRequest extends HttpServletRequestWrapper {

		private final byte[] body;

		private CachedBodyRequest(HttpServletRequest request, byte[] body) {
			super(request);
			this.body = body;
		}

		@Override
		public ServletInputStream getInputStream() {
			ByteArrayInputStream buffer = new ByteArrayInputStream(body);
			return new ServletInputStream() {

				@Override
				public int read() {
					return buffer.read();
				}

				@Override
				public boolean isFinished() {
					return buffer.available() == 0;
				}

				@Override
				public boolean isReady() {
					return true;
				}

				@Override
				public void setReadListener(ReadListener listener) {
					throw new UnsupportedOperationException("the device receiver reads its body synchronously");
				}
			};
		}

		@Override
		public java.io.BufferedReader getReader() {
			return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
		}
	}
}
