package com.selectcode.pos.shared.web;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * A business error with a stable lowercase {@code code} (docs/architecture/API-CONVENTIONS.md §3). The message is shown to shop staff
 * as the problem's {@code detail}, so write it in plain words.
 */
public class ApiException extends RuntimeException {

	private final HttpStatus status;

	private final String code;

	private final transient Map<String, Object> properties;

	public ApiException(HttpStatus status, String code, String message) {
		this(status, code, message, Map.of());
	}

	public ApiException(HttpStatus status, String code, String message, Map<String, Object> properties) {
		super(message);
		this.status = status;
		this.code = code;
		this.properties = properties;
	}

	public static ApiException notFound(String what) {
		return new ApiException(HttpStatus.NOT_FOUND, "not_found", what + " not found");
	}

	/** A rule of the business was broken (422). */
	public static ApiException rule(String code, String message) {
		return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
	}

	/** Clashes with existing data (409). */
	public static ApiException conflict(String code, String message) {
		return new ApiException(HttpStatus.CONFLICT, code, message);
	}

	public static ApiException forbidden(String code, String message) {
		return new ApiException(HttpStatus.FORBIDDEN, code, message);
	}

	/** One field is wrong (400 validation_failed with a field error). */
	public static ApiException field(String field, String code, String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", message,
				Map.of("errors", List.of(Map.of("field", field, "code", code, "message", message))));
	}

	public HttpStatus status() {
		return status;
	}

	public String code() {
		return code;
	}

	public Map<String, Object> properties() {
		return properties == null ? Map.of() : properties;
	}
}
