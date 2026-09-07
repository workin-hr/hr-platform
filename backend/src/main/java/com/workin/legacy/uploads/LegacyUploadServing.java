package com.workin.legacy.uploads;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the files {@link LegacyFileUploads} stores.
 *
 * <p><b>Nothing served them.</b> {@code store()} returns
 * {@code /uploads/<area>/<name>} to the caller, the clients render that URL
 * directly, and the frozen PHP stack answered it from the webroot -- but this
 * application registered no handler for the prefix, so every logo, photo,
 * banner and document it wrote came back <b>404</b>. Measured: a file placed in
 * the container's upload volume answered 404 over the deployed stack. That is a
 * break of **D-111**'s zero-client-change invariant, not a cosmetic one: a
 * client showing an employee's photo shows a broken image.
 *
 * <p>Public, as {@code /uploads} is on the frozen stack. The clients fetch
 * these URLs with no session -- the API hands them out in ordinary response
 * bodies -- so requiring one would break every client, which is the change
 * D-111 forbids. The exposure is legacy's and is recorded rather than silently
 * altered here.
 */
@Configuration
public class LegacyUploadServing implements WebMvcConfigurer {

	/**
	 * The extensions {@link LegacyFileUploads} itself can write, derived from
	 * the sniffed content type.
	 *
	 * <p>Anything else is refused rather than served. The frozen PHP names a
	 * stored file from the <em>client-supplied</em> filename, so its
	 * {@code /uploads} tree can hold a file whose extension has nothing to do
	 * with its bytes -- the upload-naming risk the register carries, and the
	 * reason this port derives the extension from the type instead. Serving
	 * such a file inline from this application's own origin would turn a
	 * planted {@code .html} into script on the admin's origin. Refusing it
	 * costs nothing legitimate: every file this system writes has one of these
	 * five extensions.
	 */
	private static final List<String> SERVED = List.of("jpg", "jpeg", "png", "webp", "pdf");

	private final Path uploadPath;

	private final String uploadUrl;

	public LegacyUploadServing(
			@Value("${app.legacy-uploads.path:uploads}") String uploadPath,
			@Value("${app.legacy-uploads.url:/uploads/}") String uploadUrl) {
		this.uploadPath = Path.of(uploadPath).toAbsolutePath().normalize();
		this.uploadUrl = uploadUrl;
	}

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		// An operator may point the URL at a CDN or another host, in which case
		// the files are served from there and this application must not claim
		// the path. Only a site-relative prefix is ours to answer.
		if (!this.uploadUrl.startsWith("/")) {
			return;
		}
		String prefix = this.uploadUrl.endsWith("/") ? this.uploadUrl : this.uploadUrl + "/";
		FileSystemResource location = new FileSystemResource(this.uploadPath.toString() + "/");
		registry.addResourceHandler(prefix + "**")
			.addResourceLocations(location)
			.setCacheControl(org.springframework.http.CacheControl
					.maxAge(java.time.Duration.ofHours(1)).cachePublic())
			.resourceChain(true)
			.addResolver(new ExtensionAllowlistResolver(location));
	}

	/**
	 * {@link PathResourceResolver} confined to {@link #SERVED}.
	 *
	 * <p>The superclass already refuses a path that escapes the location, which
	 * is what stops {@code ../} from reading the application's own files; this
	 * adds the extension check on top of it rather than in place of it.
	 */
	private static final class ExtensionAllowlistResolver extends PathResourceResolver {

		ExtensionAllowlistResolver(Resource location) {
			setAllowedLocations(location);
		}

		@Override
		protected Resource getResource(String resourcePath, Resource location) throws IOException {
			int dot = resourcePath.lastIndexOf('.');
			if (dot < 0 || !SERVED.contains(resourcePath.substring(dot + 1).toLowerCase(Locale.ROOT))) {
				return null;
			}
			return super.getResource(resourcePath, location);
		}
	}

}
