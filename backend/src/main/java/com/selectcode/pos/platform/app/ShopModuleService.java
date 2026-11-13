package com.selectcode.pos.platform.app;

import com.selectcode.pos.shared.features.ModuleKey;
import com.selectcode.pos.shared.features.PlanLimits;
import com.selectcode.pos.shared.features.ShopFeatures;
import com.selectcode.pos.shared.security.CurrentActor;
import com.selectcode.pos.shared.security.ActorType;
import com.selectcode.pos.shared.tenant.TenantContext;
import java.sql.Array;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Module switches per shop (docs/SHOP-TYPES-AND-MODULES.md §1) and plan limits (BIL-08). Switches are cached per shop
 * and evicted whenever they change.
 */
@Service
public class ShopModuleService implements ShopFeatures, PlanLimits {

	/**
	 * @param standard on by default for the shop type
	 * @param offered offered by the shop type (owner may switch it on)
	 * @param allowedByPlan the plan allows it (Starter: first-release modules only)
	 */
	public record ModuleState(String key, String label, int phase, boolean enabled, String source, boolean standard,
			boolean offered, boolean ownerCanToggle, boolean allowedByPlan) {
	}

	record TypeModules(Set<String> defaults, Set<String> offered) {
	}

	private final JdbcTemplate jdbc;

	private final Map<Long, Set<ModuleKey>> cache = new ConcurrentHashMap<>();

	ShopModuleService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	// ------------------------------------------------------------------ ShopFeatures

	@Override
	public Set<ModuleKey> enabled() {
		return TenantContext.shopId().map(this::enabledFor).orElse(Set.of());
	}

	public Set<ModuleKey> enabledFor(long shopId) {
		return cache.computeIfAbsent(shopId, id -> {
			Set<ModuleKey> set = EnumSet.noneOf(ModuleKey.class);
			for (String key : jdbc.queryForList("select module_key from shop_module where shop_id = ? and enabled",
					String.class, id)) {
				ModuleKey.of(key).ifPresent(set::add);
			}
			return java.util.Collections.unmodifiableSet(set);
		});
	}

	// ------------------------------------------------------------------ PlanLimits

	@Override
	public OptionalInt max(Limit limit) {
		Long shopId = TenantContext.shopId().orElse(null);
		if (shopId == null) {
			return OptionalInt.empty();
		}
		String column = switch (limit) {
			case USERS -> "max_users";
			case BRANCHES -> "max_branches";
			case PRODUCTS -> "max_products";
			case TILLS -> "max_tills";
		};
		Integer max = jdbc.queryForObject(
				"select p." + column + " from shop s join plan p on p.id = s.plan_id where s.id = ?", Integer.class, shopId);
		return max == null ? OptionalInt.empty() : OptionalInt.of(max);
	}

	// ------------------------------------------------------------------ state and changes

	public List<ModuleState> states(long shopId) {
		TypeModules type = typeModules(shopId);
		boolean phase1Only = Boolean.TRUE.equals(jdbc.queryForObject(
				"select p.phase1_modules_only from shop s join plan p on p.id = s.plan_id where s.id = ?", Boolean.class,
				shopId));
		Map<String, Object[]> stored = new LinkedHashMap<>();
		jdbc.query("select module_key, enabled, source from shop_module where shop_id = ?", rs -> {
			stored.put(rs.getString(1), new Object[] { rs.getBoolean(2), rs.getString(3) });
		}, shopId);
		List<ModuleState> states = new ArrayList<>();
		for (ModuleKey module : ModuleKey.values()) {
			Object[] row = stored.get(module.key());
			boolean enabled = row != null && (Boolean) row[0];
			states.add(new ModuleState(module.key(), module.label(), module.phase(), enabled,
					row == null ? null : (String) row[1], type.defaults().contains(module.key()),
					type.offered().contains(module.key()), module.ownerCanToggle(), !phase1Only || module.phase() == 1));
		}
		return states;
	}

	/** Writes the type's defaults for a new shop (inside the creation transaction). */
	public Set<ModuleKey> initialise(long shopId, List<String> extra, List<String> removed) {
		TypeModules type = typeModules(shopId);
		boolean phase1Only = Boolean.TRUE.equals(jdbc.queryForObject(
				"select p.phase1_modules_only from shop s join plan p on p.id = s.plan_id where s.id = ?", Boolean.class,
				shopId));
		Set<String> keys = new java.util.LinkedHashSet<>();
		keys.addAll(type.defaults());
		keys.addAll(type.offered());
		if (extra != null) {
			keys.addAll(extra);
		}
		for (String key : keys) {
			ModuleKey module = ModuleKey.of(key).orElse(null);
			if (module == null) {
				continue;
			}
			boolean wanted = (type.defaults().contains(key) || (extra != null && extra.contains(key)))
					&& (removed == null || !removed.contains(key));
			boolean planBlocks = phase1Only && module.phase() > 1;
			String source = extra != null && extra.contains(key) || removed != null && removed.contains(key) ? "OVERRIDE"
					: planBlocks && wanted ? "PLAN" : "TYPE";
			jdbc.update("""
					insert into shop_module (shop_id, module_key, enabled, source, changed_by) values (?, ?, ?, ?, ?)""",
					shopId, key, wanted && !planBlocks, source, platformActor());
		}
		cache.remove(shopId);
		return enabledFor(shopId);
	}

	/** Switches modules on or off ({@code OVERRIDE}). */
	public Set<ModuleKey> set(long shopId, Map<String, Boolean> changes) {
		for (Map.Entry<String, Boolean> change : changes.entrySet()) {
			jdbc.update("""
					insert into shop_module (shop_id, module_key, enabled, source, changed_by, changed_at)
					values (?, ?, ?, 'OVERRIDE', ?, now())
					on conflict (shop_id, module_key) do update set enabled = excluded.enabled, source = 'OVERRIDE',
					  changed_by = excluded.changed_by, changed_at = now()""", shopId, change.getKey(), change.getValue(),
					platformActor());
		}
		cache.remove(shopId);
		return enabledFor(shopId);
	}

	/** After a plan change: modules beyond the plan are switched off, type modules within it switched back on. */
	public Set<ModuleKey> applyPlan(long shopId) {
		boolean phase1Only = Boolean.TRUE.equals(jdbc.queryForObject(
				"select p.phase1_modules_only from shop s join plan p on p.id = s.plan_id where s.id = ?", Boolean.class,
				shopId));
		for (ModuleKey module : ModuleKey.values()) {
			if (module.phase() > 1 && phase1Only) {
				jdbc.update("update shop_module set enabled = false, source = 'PLAN', changed_at = now() "
						+ "where shop_id = ? and module_key = ? and enabled", shopId, module.key());
			}
			else {
				jdbc.update("update shop_module set enabled = true, source = 'TYPE', changed_at = now() "
						+ "where shop_id = ? and module_key = ? and source = 'PLAN'", shopId, module.key());
			}
		}
		cache.remove(shopId);
		return enabledFor(shopId);
	}

	public void evict(long shopId) {
		cache.remove(shopId);
	}

	TypeModules typeModules(long shopId) {
		return jdbc.queryForObject("""
				select t.default_modules, t.offered_modules from shop s join shop_type t on t.id = s.shop_type_id
				where s.id = ?""", (rs, i) -> new TypeModules(set(rs.getArray(1)), set(rs.getArray(2))), shopId);
	}

	private static Set<String> set(Array array) throws java.sql.SQLException {
		return array == null ? Set.of() : Set.of((String[]) array.getArray());
	}

	private static Long platformActor() {
		return CurrentActor.type() == ActorType.PLATFORM ? CurrentActor.id() : null;
	}
}
