package com.selectcode.pos.shared.audit;

import com.selectcode.pos.shared.security.ActorPrincipal;
import com.selectcode.pos.shared.security.ActorType;
import com.selectcode.pos.shared.security.CurrentActor;
import com.selectcode.pos.shared.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/**
 * Activity history (ACC-07, PLT-27). Called by application services inside their transaction, so the audit row commits
 * or rolls back together with the change. Rows are insert-only (database grants).
 *
 * <p>When a platform user acts inside a shop (support mode), the row goes to that shop's log (actor type PLATFORM,
 * with the support session) and to the platform audit log.
 */
@Service
public class AuditService {

	private final JdbcTemplate jdbc;

	private final JsonMapper json;

	public AuditService(JdbcTemplate jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/** Records an action in the current shop's activity history. */
	public void record(String action, String entityType, Long entityId, Object before, Object after, String reason) {
		long shopId = TenantContext.requireShopId();
		ActorPrincipal actor = CurrentActor.get().orElse(null);
		ActorType actorType = actor == null ? ActorType.SYSTEM : actor.actorType();
		Long actorId = actor == null ? null : actor.actorId();
		String actorName = actor == null ? "System" : actor.displayName();
		Long supportSession = actor == null ? null : actor.supportSessionId();
		jdbc.update("""
				insert into audit_log (shop_id, actor_type, actor_id, actor_name, support_session_id, action,
				                       entity_type, entity_id, before_data, after_data, reason, ip)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)""", shopId, actorType.name(), actorId,
				truncate(actorName, 200), supportSession, action, entityType, entityId, toJson(before), toJson(after),
				truncate(reason, 500), clientIp());
		if (actorType == ActorType.PLATFORM) {
			recordPlatform(action, shopId, Map.of("entityType", String.valueOf(entityType), "entityId",
					String.valueOf(entityId)));
		}
	}

	public void record(String action, String entityType, Long entityId, Object before, Object after) {
		record(action, entityType, entityId, before, after, null);
	}

	/** Records a platform user's action in the platform audit log. */
	public void recordPlatform(String action, Long shopId, Object details) {
		Long actorId = CurrentActor.get()
			.filter(a -> a.actorType() == ActorType.PLATFORM)
			.map(ActorPrincipal::actorId)
			.orElse(null);
		jdbc.update("""
				insert into platform_audit_log (actor_id, action, shop_id, details, ip)
				values (?, ?, ?, ?::jsonb, ?)""", actorId, action, shopId, toJson(details), clientIp());
	}

	private String toJson(Object value) {
		return value == null ? null : json.writeValueAsString(value);
	}

	private static String clientIp() {
		if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
			HttpServletRequest request = attributes.getRequest();
			return truncate(request.getRemoteAddr(), 64);
		}
		return null;
	}

	private static String truncate(String value, int max) {
		return value == null || value.length() <= max ? value : value.substring(0, max);
	}
}
