package com.selectcode.pos.notifications.app;

import com.selectcode.pos.shared.jobs.Jobs;
import com.selectcode.pos.shared.messaging.MessageSender;
import com.selectcode.pos.shared.messaging.OutgoingMessage;
import com.selectcode.pos.shared.tenant.TenantContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends queued email and SMS (NTF-01, NTF-05, TC-NTF-01): a failed message is retried after 1, 5 and 15 minutes and
 * then marked failed. SMS go through the Android SMS gateway set in the platform settings ({@code sms_gateway_url},
 * the same gateway as the school system) and count against the plan's SMS per month.
 */
@Service
public class NotificationDispatcher {

	private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

	static final int MAX_ATTEMPTS = 3;

	private static final Duration[] BACKOFF = { Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15) };

	private final JdbcTemplate jdbc;

	private final MessageSender messages;

	private final Jobs jobs;

	private final JsonMapper json;

	private final Clock clock;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	NotificationDispatcher(JdbcTemplate jdbc, MessageSender messages, Jobs jobs, JsonMapper json, Clock clock) {
		this.jdbc = jdbc;
		this.messages = messages;
		this.jobs = jobs;
		this.json = json;
		this.clock = clock;
	}

	@Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
	public void run() {
		if (!jobs.enabled()) {
			return;
		}
		jobs.exclusive("notifications", () -> dispatchAll());
	}

	/** Sends what is due for every shop (callable from tests). */
	public void dispatchAll() {
		List<Long> shops = jdbc.queryForList("select id from shop where purged_at is null order by id", Long.class);
		jobs.forEachShop("notifications", shops, shop -> dispatchShop());
	}

	/** Sends the current shop's due messages. Runs inside a per-shop transaction. */
	public int dispatchShop() {
		long shopId = TenantContext.requireShopId();
		List<Map<String, Object>> due = jdbc.queryForList("""
				select * from notification where status = 'PENDING' and channel in ('EMAIL', 'SMS') and next_attempt_at <= now()
				order by id limit 50 for update skip locked""");
		int sent = 0;
		for (Map<String, Object> n : due) {
			long id = ((Number) n.get("id")).longValue();
			String channel = (String) n.get("channel");
			String error = null;
			try {
				if ("SMS".equals(channel) && !hasSmsCredit(shopId)) {
					jdbc.update("update notification set status = 'FAILED', last_error = ? where id = ?",
							"No SMS left on the plan this month", id);
					continue;
				}
				boolean ok = "EMAIL".equals(channel)
						? messages.send(OutgoingMessage.email((String) n.get("recipient"), (String) n.get("subject"),
								(String) n.get("body")))
						: sendSms((String) n.get("recipient"), (String) n.get("body"));
				if (!ok) {
					error = "Not accepted by the provider";
				}
			}
			catch (RuntimeException e) {
				error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			}
			if (error == null) {
				jdbc.update("update notification set status = 'SENT', sent_at = now(), attempts = attempts + 1 where id = ?",
						id);
				sent++;
			}
			else {
				int attempts = ((Number) n.get("attempts")).intValue() + 1;
				boolean giveUp = attempts >= MAX_ATTEMPTS;
				jdbc.update("""
						update notification set attempts = ?, last_error = ?, status = ?, next_attempt_at = ? where id = ?""",
						attempts, error.length() > 500 ? error.substring(0, 500) : error, giveUp ? "FAILED" : "PENDING",
						java.sql.Timestamp.from(clock.instant().plus(BACKOFF[Math.min(attempts - 1, BACKOFF.length - 1)])),
						id);
				log.warn("notification {} ({}) failed, attempt {}: {}", id, channel, attempts, error);
			}
		}
		return sent;
	}

	private boolean hasSmsCredit(long shopId) {
		Map<String, Object> plan = jdbc.queryForMap("""
				select p.sms_per_month from shop s join plan p on p.id = s.plan_id where s.id = ?""", shopId);
		int allowed = ((Number) plan.get("sms_per_month")).intValue();
		LocalDate first = LocalDate.now(clock.withZone(ZoneId.of("Asia/Karachi"))).withDayOfMonth(1);
		Integer used = jdbc.queryForObject("""
				select count(*) from notification where channel = 'SMS' and status = 'SENT' and sent_at >= ?""",
				Integer.class, java.sql.Timestamp.from(first.atStartOfDay(ZoneId.of("Asia/Karachi")).toInstant()));
		return used == null || used < allowed;
	}

	/** POST {to, message} with the gateway token to the Android SMS gateway; without a gateway, the dev outbox. */
	boolean sendSms(String to, String body) {
		Map<String, String> settings = new java.util.HashMap<>();
		jdbc.query("select setting_key, value from platform_setting where setting_key in ('sms_gateway_url', 'sms_gateway_token')",
				rs -> {
					settings.put(rs.getString(1), rs.getString(2));
				});
		String url = settings.getOrDefault("sms_gateway_url", "");
		if (url == null || url.isBlank()) {
			if (messages.supports(OutgoingMessage.Channel.SMS)) {
				return messages.send(new OutgoingMessage(OutgoingMessage.Channel.SMS, to, null, body));
			}
			throw new IllegalStateException("No SMS gateway is set up");
		}
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(20))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + settings.getOrDefault("sms_gateway_token", ""))
				.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("to", to, "message", body))))
				.build();
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() / 100 != 2) {
				throw new IllegalStateException("SMS gateway answered " + response.statusCode());
			}
			return true;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("SMS sending interrupted");
		}
		catch (java.io.IOException e) {
			throw new IllegalStateException("SMS gateway not reachable: " + e.getMessage());
		}
	}
}
