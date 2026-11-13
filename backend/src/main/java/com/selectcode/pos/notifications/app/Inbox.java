package com.selectcode.pos.notifications.app;

import com.selectcode.pos.shared.security.ActorType;
import com.selectcode.pos.shared.security.CurrentActor;
import com.selectcode.pos.shared.web.ApiException;
import com.selectcode.pos.shared.web.PageResponse;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The bell (NTF-04): the signed-in user's in-app messages. */
@Service
public class Inbox {

	public record InboxItem(long id, String topic, String title, String body, String link, boolean read,
			Instant at) {
	}

	public record InboxSummary(long unread) {
	}

	private final JdbcTemplate jdbc;

	Inbox(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public PageResponse<InboxItem> list(boolean unreadOnly, int page, int size) {
		Long user = user();
		if (user == null) {
			return new PageResponse<>(List.of(), page, size, 0);
		}
		String where = " where channel = 'IN_APP' and user_id = ?" + (unreadOnly ? " and read_at is null" : "");
		long total = jdbc.queryForObject("select count(*) from notification" + where, Long.class, user);
		List<InboxItem> items = jdbc.query("select * from notification" + where
				+ " order by (read_at is null) desc, created_at desc limit ? offset ?",
				(rs, i) -> new InboxItem(rs.getLong("id"), rs.getString("topic"), rs.getString("subject"),
						rs.getString("body"), rs.getString("link"), rs.getTimestamp("read_at") != null,
						rs.getTimestamp("created_at").toInstant()),
				user, size, (long) page * size);
		return new PageResponse<>(items, page, size, total);
	}

	@Transactional(readOnly = true)
	public InboxSummary summary() {
		Long user = user();
		if (user == null) {
			return new InboxSummary(0);
		}
		Long n = jdbc.queryForObject(
				"select count(*) from notification where channel = 'IN_APP' and user_id = ? and read_at is null",
				Long.class, user);
		return new InboxSummary(n == null ? 0 : n);
	}

	@Transactional
	public void read(long id) {
		int n = jdbc.update("""
				update notification set read_at = coalesce(read_at, now()), status = 'READ'
				where id = ? and channel = 'IN_APP' and user_id = ?""", id, user());
		if (n == 0) {
			throw ApiException.notFound("Notification");
		}
	}

	@Transactional
	public void readAll() {
		jdbc.update("""
				update notification set read_at = now(), status = 'READ'
				where channel = 'IN_APP' and user_id = ? and read_at is null""", user());
	}

	private static Long user() {
		return CurrentActor.type() == ActorType.USER ? CurrentActor.id() : null;
	}
}
