package com.selectcode.pos.notifications.api;

import com.selectcode.pos.notifications.app.Inbox;
import com.selectcode.pos.notifications.app.Inbox.InboxItem;
import com.selectcode.pos.notifications.app.Inbox.InboxSummary;
import com.selectcode.pos.shared.security.AnyAuthenticated;
import com.selectcode.pos.shared.web.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's in-app notifications (bell icon, NTF-04). */
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {

	private final Inbox inbox;

	NotificationController(Inbox inbox) {
		this.inbox = inbox;
	}

	@AnyAuthenticated
	@GetMapping
	PageResponse<InboxItem> list(@RequestParam(required = false) Boolean unreadOnly,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return inbox.list(Boolean.TRUE.equals(unreadOnly), PageResponse.page(page), PageResponse.size(size));
	}

	@AnyAuthenticated
	@GetMapping("/summary")
	InboxSummary summary() {
		return inbox.summary();
	}

	@AnyAuthenticated
	@PostMapping("/{id}/read")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void read(@PathVariable long id) {
		inbox.read(id);
	}

	@AnyAuthenticated
	@PostMapping("/read-all")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void readAll() {
		inbox.readAll();
	}
}
