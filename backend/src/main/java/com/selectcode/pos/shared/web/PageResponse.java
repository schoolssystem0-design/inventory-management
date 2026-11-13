package com.selectcode.pos.shared.web;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/** Paged list response (docs/architecture/API-CONVENTIONS.md §2): {@code items, page, size, total}. */
public record PageResponse<T>(List<T> items, int page, int size, long total) {

	public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
		return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
				page.getTotalElements());
	}

	public static <T> PageResponse<T> of(List<T> items, int page, int size, long total) {
		return new PageResponse<>(items, page, size, total);
	}

	/** Clamps client paging input to the documented limits (default 50, max 200). */
	public static int size(Integer requested) {
		return requested == null ? 50 : Math.max(1, Math.min(200, requested));
	}

	public static int page(Integer requested) {
		return requested == null ? 0 : Math.max(0, requested);
	}
}
