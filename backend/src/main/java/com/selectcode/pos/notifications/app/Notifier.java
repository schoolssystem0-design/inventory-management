package com.selectcode.pos.notifications.app;

import com.selectcode.pos.shared.features.ModuleKey;
import com.selectcode.pos.shared.features.ShopFeatures;
import com.selectcode.pos.shared.tenant.TenantContext;
import java.util.Collection;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Queues messages in the shop's outbox (NTF-01, NTF-04, NTF-05). Called inside the business transaction, so a message
 * is only sent if the document it is about was saved; the dispatcher sends email and SMS after commit with retries.
 * A {@code dedupeKey} makes sure the same reminder is queued once (e.g. "subscription-due:INV-12:D3").
 */
@Service
public class Notifier {

	private final JdbcTemplate jdbc;

	private final ShopFeatures features;

	Notifier(JdbcTemplate jdbc, ShopFeatures features) {
		this.jdbc = jdbc;
		this.features = features;
	}

	/** In-app message (bell) for these users. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void inApp(Collection<Long> userIds, String topic, String title, String body, String link,
			String dedupeKey) {
		for (Long userId : userIds) {
			insert("IN_APP", null, userId, topic, title, body, link, "SENT",
					dedupeKey == null ? null : dedupeKey + ":u" + userId);
		}
	}

	/** In-app message for every active user holding {@code permission} (the owner always). */
	@Transactional(propagation = Propagation.MANDATORY)
	public void inAppFor(String permission, String topic, String title, String body, String link, String dedupeKey) {
		inApp(usersWith(permission), topic, title, body, link, dedupeKey);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public boolean email(String to, String topic, String subject, String body, String dedupeKey) {
		if (to == null || to.isBlank() || !to.contains("@")) {
			return false;
		}
		return insert("EMAIL", to.trim(), null, topic, subject, body, null, "PENDING", dedupeKey);
	}

	/**
	 * SMS through the Android gateway (NTF-01). Messages the shop sends (receipts, reminders) need the shop's
	 * {@code sms} module; messages from SelectCode to the shop ({@code fromPlatform}) do not.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean sms(String to, String topic, String body, String dedupeKey, boolean fromPlatform) {
		if (to == null || to.replaceAll("[^0-9]", "").length() < 10) {
			return false;
		}
		if (!fromPlatform && !features.isOn(ModuleKey.SMS)) {
			return false;
		}
		return insert("SMS", to.trim(), null, topic, null, body, null, "PENDING", dedupeKey);
	}

	/** Active users with a permission (owners always included). */
	public List<Long> usersWith(String permission) {
		return jdbc.queryForList("""
				select u.id from app_user u join role r on r.id = u.role_id
				where u.status = 'ACTIVE' and (u.is_owner or ? = any(r.permissions))""", Long.class, permission);
	}

	private boolean insert(String channel, String recipient, Long userId, String topic, String subject, String body,
			String link, String status, String dedupeKey) {
		try {
			return jdbc.update("""
					insert into notification (shop_id, channel, recipient, user_id, topic, subject, body, link, status,
					                          sent_at, dedupe_key)
					values (?, ?, ?, ?, ?, ?, ?, ?, ?, case when ? = 'SENT' then now() end, ?)
					on conflict (shop_id, dedupe_key) where dedupe_key is not null do nothing""",
					TenantContext.requireShopId(), channel, recipient, userId, topic,
					subject == null ? null : subject.length() > 200 ? subject.substring(0, 200) : subject, body,
					link, status, status, dedupeKey) == 1;
		}
		catch (DuplicateKeyException e) {
			return false;
		}
	}
}
