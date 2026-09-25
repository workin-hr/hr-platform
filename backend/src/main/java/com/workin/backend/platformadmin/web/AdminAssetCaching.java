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
 * <p>A font is cached for a year and marked immutable. The twelve {@code .woff2} files are 312 KB
 * of third-party binary that changes when the typeface changes, which is not a deploy -- and they
 * shipped in the revalidated bucket, so every page load spent a round trip per font asking whether
 * a file that had not changed since it was vendored had changed. <b>The price of the year is that a
 * font must be replaced under a new filename</b>, never overwritten in place; the names already
 * carry the weight and the subset, so a different cut is a different name. An operator who
 * overwrites one anyway will see the old glyphs until the max-age expires, with no error anywhere.
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
		// LOCATION + "fonts/", not LOCATION. A handler resolves the part of the path its
		// pattern matched with `*` against the location, so `/_assets/fonts/*.woff2`
		// against `_assets/` looks for `_assets/<name>.woff2` and every font 404s --
		// which is what this returned before the test below was written, and the page
		// would have fallen back to the system font in silence. The catch-all it
		// shadows is `/_assets/**`, where the matched part is `fonts/<name>.woff2`, so
		// the same location works there and hid the difference.
		registry.addResourceHandler(
				PlatformAdminWebSecurityConfig.PATH_PREFIX + "/_assets/fonts/*.woff2")
			.addResourceLocations(LOCATION + "fonts/")
			.setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
			.setUseLastModified(false)
			.setEtagGenerator(this::etag);
		registry.addResourceHandler(PlatformAdminWebSecurityConfig.ASSETS_PATTERN)
			.addResourceLocations(LOCATION)
			.setCacheControl(CacheControl.noCache())
			.setUseLastModified(false)
			.setEtagGenerator(this::etag);
	}

	/**
	 * A strong ETag of the file's content, or none if it cannot be read. Without one, a stylesheet
	 * or script is downloaded again on every load rather than kept stale, and an image is kept for
	 * its seven days with nothing to revalidate against.
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
