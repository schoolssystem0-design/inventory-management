package com.selectcode.pos.shared.web;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * Builds RFC 9457 problem responses in the project's shape (docs/architecture/API-CONVENTIONS.md §3): a stable lowercase
 * {@code code}, a {@code detail} written for shop staff, {@code requestId} and optional field {@code errors}.
 */
public final class Problems {

	private static final String TYPE_BASE = "https://selectcodepos.com/problems/";

	private Problems() {
	}

	public static ProblemDetail of(HttpStatusCode status, String code, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(URI.create(TYPE_BASE + code.replace('_', '-')));
		problem.setProperty("code", code);
		problem.setProperty("requestId", RequestIdFilter.current());
		return problem;
	}

	public static ProblemDetail validation(List<Map<String, String>> errors) {
		ProblemDetail problem = of(HttpStatus.BAD_REQUEST, "validation_failed", "Some fields need attention");
		problem.setTitle("Validation failed");
		problem.setProperty("errors", errors);
		return problem;
	}
}
