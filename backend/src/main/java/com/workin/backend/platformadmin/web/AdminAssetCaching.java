package com.workin.backend.platformadmin.web;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * How the dashboard's own assets under {@code /admin/_assets/} are cached (D-266).
 *
 * <p>An image is cached for seven days: the logo and its icon copies change with the branding,
 * not with a deploy. A stylesheet or script changes with a deploy under the same URL, so it is
 * revalidated on every load against an ETag of its content, and a browser holding the current
 * copy gets a 304.
 *
 * <p>Neither uses the file's timestamp. Gradle 9 builds reproducible archives, so every entry in
 * the production jar carries the same fixed time, and a browser revalidating with
 * {@code If-Modified-Since} would be told a copy from before the deploy is current.
 */
@Configuration
class AdminAssetCaching implements WebMvcConfigurer {

	private static final String LOCATION = "classpath:/static/admin/_assets/";

	/** Content hashes by resource URL; the files cannot change while the application runs. */
	private final Map<String, String> etags = new ConcurrentHashMap<>();

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		registry.addResourceHandler(PlatformAdminWebSecurityConfig.PATH_PREFIX + "/_assets/*.png")
			.addResourceLocations(LOCATION)
			.setCacheControl(CacheControl.maxAge(Duration.ofDays(7)))
			.setUseLastModified(false)
			.setEtagGenerator(this::etag);
		registry.addResourceHandler(PlatformAdminWebSecurityConfig.ASSETS_PATTERN)
			.addResourceLocations(LOCATION)
			.setCacheControl(CacheControl.noCache())
			.setUseLastModified(false)
			.setEtagGenerator(this::etag);
	}

	/**
	 * A strong ETag of the file's content, or none if it cannot be read, in which case the
	 * response is still {@code no-cache} and the browser downloads it again rather than keeping a
	 * stale copy.
	 */
	private @Nullable String etag(Resource resource) {
		String key;
		try {
			key = resource.getURL().toString();
		}
		catch (IOException unreadable) {
			return null;
		}
		String cached = this.etags.get(key);
		if (cached != null) {
			return cached;
		}
		try (InputStream in = resource.getInputStream()) {
			String etag = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
			this.etags.put(key, etag);
			return etag;
		}
		catch (IOException | NoSuchAlgorithmException unreadable) {
			return null;
		}
	}

}
