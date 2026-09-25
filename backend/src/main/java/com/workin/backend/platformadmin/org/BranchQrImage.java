package com.workin.backend.platformadmin.org;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import io.nayuki.qrcodegen.QrCode;

/**
 * A branch's check-in QR, drawn on this host.
 *
 * <p>Legacy's {@code org_branch_qr_image_url()} points the page's {@code <img>} at
 * {@code api.qrserver.com} with the code in the query string, so every render
 * sent the branch's check-in code, and the administrator's address, user agent
 * and the time they looked, to a third party (#298, D-285). The code is what a
 * mobile client scans to check in at the branch, which makes it the one value on
 * that page least worth disclosing.
 *
 * <p>The image is an SVG in a {@code data:} URI rather than inline markup so
 * that it stays an {@code <img>}, which an operator can still save or print as
 * they could the remote one; it also keeps the page's one user-derived image out
 * of raw-HTML output, which the sidebar uses only for its fixed icons. The
 * encoded text is the stored code exactly, as
 * legacy's {@code data=} parameter carried it, so every client that scanned the
 * old image scans this one.
 */
public final class BranchQrImage {

	/**
	 * Modules of blank margin on each side: the QR specification's quiet zone,
	 * without which a scanner may not find the finder patterns.
	 */
	private static final int QUIET_ZONE = 4;

	private BranchQrImage() {
	}

	/**
	 * The code as a {@code data:image/svg+xml;base64,...} URI, or {@code ""} for
	 * a missing or blank code -- legacy's own condition for rendering no image,
	 * which the template keeps.
	 */
	public static String dataUri(String code) {
		if (code == null || code.trim().isEmpty()) {
			return "";
		}
		return "data:image/svg+xml;base64,"
				+ Base64.getEncoder().encodeToString(svg(code).getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * The SVG itself: one square per dark module, on a white ground that
	 * includes the quiet zone, scaled by the viewer rather than by the markup.
	 *
	 * <p>At least medium error correction: a printed code on a wall gets scuffed.
	 * qrcodegen raises the level while the symbol keeps its version, so a 32-byte
	 * {@code generate_qr} code is drawn at quartile in a version-3 symbol.
	 */
	static String svg(String code) {
		QrCode qr = QrCode.encodeText(code, QrCode.Ecc.MEDIUM);
		int side = qr.size + QUIET_ZONE * 2;
		StringBuilder path = new StringBuilder();
		for (int y = 0; y < qr.size; y++) {
			for (int x = 0; x < qr.size; x++) {
				if (qr.getModule(x, y)) {
					path.append('M').append(x + QUIET_ZONE).append(',').append(y + QUIET_ZONE)
							.append("h1v1h-1z");
				}
			}
		}
		return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + side + " " + side
				+ "\" shape-rendering=\"crispEdges\"><rect width=\"100%\" height=\"100%\" "
				+ "fill=\"#fff\"/><path fill=\"#000\" d=\"" + path + "\"/></svg>";
	}
}
