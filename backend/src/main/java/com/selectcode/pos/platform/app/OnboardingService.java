package com.selectcode.pos.platform.app;

import com.selectcode.pos.shared.audit.AuditService;
import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.shared.web.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The owner's first-login checklist (ONB-02) and the onboarding view per shop for SelectCode staff (OPS-05), plus
 * terms and privacy acceptance (OPS-16).
 */
@Service
public class OnboardingService {

	public record Step(String key, String label, boolean done, String link) {
	}

	public record Checklist(List<Step> steps, int done, int total, boolean complete) {
	}

	public record Terms(String currentVersion, String acceptedVersion, Instant acceptedAt, boolean required) {
	}

	private final JdbcTemplate jdbc;

	private final AuditService audit;

	private final TransactionTemplate tx;

	OnboardingService(JdbcTemplate jdbc, AuditService audit, PlatformTransactionManager transactions) {
		this.jdbc = jdbc;
		this.audit = audit;
		this.tx = new TransactionTemplate(transactions);
	}

	/** The current shop's checklist. */
	@Transactional(readOnly = true)
	public Checklist checklist() {
		Map<String, Object> m = jdbc.queryForMap("""
				select (s.address is not null and s.phones is not null) as profile, s.logo_file_id is not null as logo,
				       (select count(*) from app_user) > 1 as users, (select count(*) from product) > 0 as products,
				       exists (select 1 from stock_movement where move_type in ('OPENING', 'PURCHASE')) as stock,
				       s.printer_tested as printer, exists (select 1 from sale) as sale
				from shop_settings s where s.shop_id = ?""", TenantContext.requireShopId());
		List<Step> steps = List.of(
				new Step("profile", "Business details and receipt header", (Boolean) m.get("profile"), "/settings"),
				new Step("logo", "Shop logo", (Boolean) m.get("logo"), "/settings"),
				new Step("users", "Add your staff", (Boolean) m.get("users"), "/users"),
				new Step("products", "Add or import products", (Boolean) m.get("products"), "/products/import"),
				new Step("stock", "Enter opening stock", (Boolean) m.get("stock"), "/stock/opening"),
				new Step("printer", "Test the receipt printer", (Boolean) m.get("printer"), "/settings"),
				new Step("sale", "Make a test sale", (Boolean) m.get("sale"), "/pos"));
		int done = (int) steps.stream().filter(Step::done).count();
		return new Checklist(steps, done, steps.size(), done == steps.size());
	}

	/** Checklist of any shop, for the platform's shop page. */
	public Checklist checklist(long shopId) {
		jdbc.queryForList("select id from shop where id = ?", Long.class, shopId)
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Shop"));
		return TenantContext.callAsShop(shopId, () -> tx.execute(s -> checklist()));
	}

	@Transactional
	public Checklist printerTested() {
		jdbc.update("update shop_settings set printer_tested = true where shop_id = ?", TenantContext.requireShopId());
		return checklist();
	}

	@Transactional(readOnly = true)
	public Terms terms() {
		String current = jdbc.queryForList("select value from platform_setting where setting_key = 'terms_version'",
				String.class).stream().findFirst().orElse("1");
		Map<String, Object> s = jdbc.queryForMap(
				"select terms_version, terms_accepted_at from shop_settings where shop_id = ?",
				TenantContext.requireShopId());
		String accepted = (String) s.get("terms_version");
		return new Terms(current, accepted,
				s.get("terms_accepted_at") == null ? null : ((java.sql.Timestamp) s.get("terms_accepted_at")).toInstant(),
				!current.equals(accepted));
	}

	/** The owner accepts the current terms and privacy policy; who and when is stored. */
	@Transactional
	public Terms accept(String version) {
		Terms terms = terms();
		if (version == null || !version.trim().equals(terms.currentVersion())) {
			throw ApiException.rule("terms_changed", "Please read the latest terms (version " + terms.currentVersion() + ")");
		}
		jdbc.update("update shop_settings set terms_version = ?, terms_accepted_at = now() where shop_id = ?",
				terms.currentVersion(), TenantContext.requireShopId());
		audit.record("TERMS_ACCEPTED", "shop_settings", TenantContext.requireShopId(), null,
				Map.of("version", terms.currentVersion()));
		return terms();
	}
}
