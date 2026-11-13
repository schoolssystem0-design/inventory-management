package com.selectcode.pos.shared.files;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/** Object storage for uploaded and generated files: Cloudflare R2 in staging/production, local disk in development. */
public interface FileStorage {

	void put(String key, byte[] content, String contentType);

	byte[] get(String key);

	/** A short-lived direct download URL, if the store supports it (R2); otherwise the API streams the file. */
	Optional<URI> presignedGetUrl(String key, Duration validity, String downloadName);

	/**
	 * Deletes every object whose key starts with {@code prefix} (a purged shop's files: {@code shops/{id}/}).
	 * @return how many were deleted
	 */
	int deletePrefix(String prefix);

	/** Where a shop's files live. */
	static String shopPrefix(long shopId) {
		return "shops/" + shopId + "/";
	}
}
