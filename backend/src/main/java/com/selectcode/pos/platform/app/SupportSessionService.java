package com.selectcode.pos.platform.app;

import com.selectcode.pos.platform.PlatformPrincipal;
import com.selectcode.pos.shared.audit.AuditService;
import com.selectcode.pos.shared.security.CurrentActor;
import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.shared.web.ApiException;
import com.selectcode.pos.shop.app.ShopConfigService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Support mode (PLT-17): a platform user opens a shop to help. The session is read-only until the owner gives an
 * approval code; every change is written to the shop's activity history and the platform audit log, and the shop's
 * screens show a "SelectCode support is viewing" banner.
 */
@Service
public class SupportSessionService {

	public record SupportSessionView(long id, long shopId, String shopCode, String shopName, String reason,
			boolean writeApproved, Instant startedAt) {
	}

	private final JdbcTemplate jdbc;

	private final ShopConfigService shopConfig;

	private final SecurityContextRepository securityContextRepository;

	private final AuditService audit;

	private final TransactionTemplate tx;

	SupportSessionService(JdbcTemplate jdbc, ShopConfigService shopConfig,
			SecurityContextRepository securityContextRepository, AuditService audit,
			PlatformTransactionManager transactions) {
		this.jdbc = jdbc;
		this.shopConfig = shopConfig;
		this.securityContextRepository = securityContextRepository;
		this.audit = audit;
		this.tx = new TransactionTemplate(transactions);
	}

	public SupportSessionView start(long shopId, String reason, HttpServletRequest request,
			HttpServletResponse response) {
		PlatformPrincipal current = (PlatformPrincipal) CurrentActor.require();
		String why = reason == null ? "" : reason.trim();
		if (why.isEmpty() || why.length() > 500) {
			throw ApiException.field("reason", "required", "Say why you are opening this shop");
		}
		SupportSessionView view = tx.execute(t -> {
			Map<String, Object> shop = jdbc.queryForList("select id, code, name from shop where id = ?", shopId)
				.stream()
				.findFirst()
				.orElseThrow(() -> ApiException.notFound("Shop"));
			if (current.supportSessionId() != null) {
				endRow(current.supportSessionId());
			}
			Long id = jdbc.queryForObject("""
					insert into support_session (platform_user_id, shop_id, reason) values (?, ?, ?) returning id""",
					Long.class, current.platformUserId(), shopId, why);
			audit.recordPlatform("SUPPORT_SESSION_STARTED", shopId, Map.of("reason", why, "sessionId", id));
			return new SupportSessionView(id, shopId, (String) shop.get("code"), (String) shop.get("name"), why, false,
					Instant.now());
		});
		save(current.inSupportSession(view.id(), shopId, view.shopCode(), view.shopName()), request, response);
		TenantContext.runAsShop(shopId, () -> tx.executeWithoutResult(t -> audit.record("SUPPORT_SESSION_STARTED",
				"support_session", view.id(), null, Map.of("by", current.name(), "reason", why))));
		return view;
	}

	public void end(HttpServletRequest request, HttpServletResponse response) {
		PlatformPrincipal current = (PlatformPrincipal) CurrentActor.require();
		if (current.supportSessionId() != null) {
			tx.executeWithoutResult(t -> {
				endRow(current.supportSessionId());
				audit.recordPlatform("SUPPORT_SESSION_ENDED", current.activeShopId(),
						Map.of("sessionId", current.supportSessionId()));
			});
		}
		save(current.outsideShops(), request, response);
	}

	/** The owner's approval code turns on changes for the rest of this support session. */
	public SupportSessionView approveWrites(String code) {
		PlatformPrincipal current = (PlatformPrincipal) CurrentActor.require();
		if (current.supportSessionId() == null) {
			throw ApiException.rule("no_support_session", "Open a shop first");
		}
		long shopId = current.activeShopId();
		boolean ok = TenantContext.callAsShop(shopId, () -> tx.execute(t -> shopConfig.useSupportCode(code)));
		if (!ok) {
			throw ApiException.field("code", "wrong", "The approval code is wrong or has expired");
		}
		tx.executeWithoutResult(t -> {
			jdbc.update("update support_session set write_approved_at = now() where id = ?", current.supportSessionId());
			audit.recordPlatform("SUPPORT_WRITE_APPROVED", shopId, Map.of("sessionId", current.supportSessionId()));
		});
		return current();
	}

	public SupportSessionView current() {
		PlatformPrincipal current = (PlatformPrincipal) CurrentActor.require();
		if (current.supportSessionId() == null) {
			return null;
		}
		List<SupportSessionView> rows = jdbc.query("""
				select ss.*, s.code, s.name from support_session ss join shop s on s.id = ss.shop_id where ss.id = ?""",
				(rs, i) -> new SupportSessionView(rs.getLong("id"), rs.getLong("shop_id"), rs.getString("code"),
						rs.getString("name"), rs.getString("reason"), rs.getTimestamp("write_approved_at") != null,
						rs.getTimestamp("started_at").toInstant()),
				current.supportSessionId());
		return rows.stream().findFirst().orElse(null);
	}

	private void endRow(long sessionId) {
		jdbc.update("update support_session set ended_at = now() where id = ? and ended_at is null", sessionId);
	}

	private void save(PlatformPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
		securityContextRepository.saveContext(context, request, response);
	}
}
