package com.selectcode.pos.stock.app;

import com.selectcode.pos.catalog.app.ProductCatalog;
import com.selectcode.pos.catalog.app.ProductCatalog.ProductRef;
import com.selectcode.pos.iam.app.ApprovalService;
import com.selectcode.pos.iam.app.ApprovalService.ApprovalRequest;
import com.selectcode.pos.shared.audit.AuditService;
import com.selectcode.pos.shared.features.ModuleKey;
import com.selectcode.pos.shared.features.ShopFeatures;
import com.selectcode.pos.shared.money.Amounts;
import com.selectcode.pos.shared.security.ActorType;
import com.selectcode.pos.shared.security.CurrentActor;
import com.selectcode.pos.shared.security.Permissions;
import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.shared.time.ShopTime;
import com.selectcode.pos.shared.web.ApiException;
import com.selectcode.pos.shared.web.PageResponse;
import com.selectcode.pos.shop.app.BranchAccess;
import com.selectcode.pos.shop.app.ShopConfigService;
import com.selectcode.pos.stock.app.StockService.DocRef;
import com.selectcode.pos.stock.app.StockService.Move;
import com.selectcode.pos.stock.app.StockService.MoveType;
import com.selectcode.pos.stock.app.StockService.NewSerial;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock adjustments with a reason (STK-02) and opening stock (ONB-03). Write-offs above the shop's limit need a
 * manager's approval (ACC-04); the value written off is stored on the document for reports.
 */
@Service
public class StockAdjustments {

	public record AdjustmentLineInput(Long productId, String qty, String unitCost, Long batchId, String batchNo,
			LocalDate expiry, List<String> serials, List<NewSerial> newSerials) {
	}

	/** @param reason DAMAGED, EXPIRED, LOST, OWN_USE (out), FOUND (in) or OTHER (signed quantities) */
	public record AdjustmentInput(Long branchId, String reason, String note, List<AdjustmentLineInput> lines) {
	}

	public record AdjustmentLineView(long productId, String productName, String batchNo, String serial, String qty,
			String unitCost, String value) {
	}

	public record AdjustmentView(long id, String number, long branchId, String branchName, String reason, String note,
			String valueIn, String valueOut, String approvedByName, String actorName, LocalDate businessDate, Instant at,
			List<AdjustmentLineView> lines) {
	}

	public record AdjustmentListItem(long id, String number, String branchName, String reason, int lines,
			String valueIn, String valueOut, String actorName, Instant at) {
	}

	public record OpeningLineInput(Long productId, String qty, String unitCost, String batchNo, LocalDate expiry,
			List<NewSerial> serials) {
	}

	public record OpeningStockInput(Long branchId, List<OpeningLineInput> lines) {
	}

	static final Set<String> OUT_REASONS = Set.of("DAMAGED", "EXPIRED", "LOST", "OWN_USE");

	private final JdbcTemplate jdbc;

	private final StockService stock;

	private final ProductCatalog catalog;

	private final BranchAccess branches;

	private final ShopConfigService numbering;

	private final ApprovalService approvals;

	private final ShopFeatures features;

	private final AuditService audit;

	private final ShopTime time;

	StockAdjustments(JdbcTemplate jdbc, StockService stock, ProductCatalog catalog, BranchAccess branches,
			ShopConfigService numbering, ApprovalService approvals, ShopFeatures features, AuditService audit,
			ShopTime time) {
		this.jdbc = jdbc;
		this.stock = stock;
		this.catalog = catalog;
		this.branches = branches;
		this.numbering = numbering;
		this.approvals = approvals;
		this.features = features;
		this.audit = audit;
		this.time = time;
	}

	@Transactional
	public AdjustmentView create(AdjustmentInput in, ApprovalRequest approval) {
		var branch = branches.requireActive(in.branchId());
		String reason = in.reason() == null ? "" : in.reason().trim().toUpperCase();
		if (!Set.of("DAMAGED", "EXPIRED", "LOST", "OWN_USE", "FOUND", "OTHER").contains(reason)) {
			throw ApiException.field("reason", "invalid", "Choose a reason: damaged, expired, lost, found, own use or other");
		}
		if (in.lines() == null || in.lines().isEmpty()) {
			throw ApiException.field("lines", "required", "Add at least one item");
		}
		LocalDate today = time.today();
		record Planned(ProductRef product, BigDecimal qty, BigDecimal unitCost, Long batchId, List<StockService.SerialUnit> serials,
				AdjustmentLineInput input) {
		}
		List<Planned> planned = new ArrayList<>();
		BigDecimal valueOut = BigDecimal.ZERO;
		BigDecimal valueIn = BigDecimal.ZERO;
		for (int i = 0; i < in.lines().size(); i++) {
			AdjustmentLineInput line = in.lines().get(i);
			String f = "lines[" + i + "]";
			if (line.productId() == null) {
				throw ApiException.field(f + ".productId", "required", "Choose a product");
			}
			ProductRef product = catalog.require(line.productId());
			if (!product.tracksStock()) {
				throw ApiException.rule("not_stocked", product.name() + " does not keep stock");
			}
			boolean negative = line.qty() != null && line.qty().trim().startsWith("-");
			BigDecimal qty;
			if (product.serial()) {
				int count = line.serials() != null && !line.serials().isEmpty() ? line.serials().size()
						: line.newSerials() == null ? 0 : line.newSerials().size();
				if (count == 0) {
					throw ApiException.field(f + ".serials", "required", "Scan the IMEI/serial of each unit");
				}
				qty = BigDecimal.valueOf(count);
			}
			else {
				if (negative && !"OTHER".equals(reason)) {
					throw ApiException.field(f + ".qty", "negative", "Enter the quantity without a minus sign");
				}
				qty = Amounts.parseQty(negative ? line.qty().trim().substring(1) : line.qty(), f + ".qty");
				if (qty == null || qty.signum() == 0) {
					throw ApiException.field(f + ".qty", "required", "Enter the quantity");
				}
				ProductCatalog.checkQty(product, catalog.unit(product, null), qty, f + ".qty");
			}
			boolean out = OUT_REASONS.contains(reason) || ("OTHER".equals(reason) && negative)
					|| (product.serial() && line.serials() != null && !line.serials().isEmpty());
			if ("FOUND".equals(reason) && product.serial() && (line.newSerials() == null || line.newSerials().isEmpty())) {
				throw ApiException.field(f + ".newSerials", "required", "Enter the IMEI/serial of each unit found");
			}
			Long batchId = line.batchId();
			if (product.batch()) {
				if (out && batchId == null) {
					throw ApiException.field(f + ".batchId", "required", "Choose the batch");
				}
				if (!out && batchId == null) {
					batchId = stock.batch(product.id(), line.batchNo(), line.expiry(), null, null, null, null);
				}
			}
			else {
				batchId = null;
			}
			BigDecimal unitCost = Amounts.parseMoney(line.unitCost(), f + ".unitCost");
			if (unitCost == null || !CurrentActor.hasPermission(Permissions.COST_VIEW)) {
				unitCost = product.costAvg();
				if (batchId != null) {
					BigDecimal batchCost = jdbc.queryForObject("select cost from batch where id = ?", BigDecimal.class,
							batchId);
					unitCost = batchCost == null ? unitCost : batchCost;
				}
			}
			List<StockService.SerialUnit> units = List.of();
			if (product.serial() && out) {
				units = new ArrayList<>();
				for (String serial : line.serials()) {
					units.add(stock.lockSerial(serial, product.id(), branch.id(), "IN_STOCK"));
				}
				unitCost = units.stream().map(StockService.SerialUnit::cost).reduce(BigDecimal.ZERO, BigDecimal::add)
					.divide(BigDecimal.valueOf(units.size()), 2, java.math.RoundingMode.HALF_UP);
			}
			BigDecimal value = Amounts.money(qty.multiply(unitCost));
			if (out) {
				valueOut = valueOut.add(value);
			}
			else {
				valueIn = valueIn.add(value);
			}
			planned.add(new Planned(product, out ? qty.negate() : qty, unitCost, batchId, units, line));
		}

		BigDecimal limit = jdbc.queryForObject("select writeoff_limit from shop_settings where shop_id = ?",
				BigDecimal.class, TenantContext.requireShopId());
		Long approvedBy = null;
		if (valueOut.compareTo(limit) > 0) {
			approvedBy = approvals.require(approval, Permissions.STOCK_ADJUST, "Stock write-off of Rs " + valueOut,
					"write-off above Rs " + limit.toPlainString());
		}

		String number = numbering.nextNumber("ADJUSTMENT", branch.id());
		long id = jdbc.queryForObject("""
				insert into adjustment (shop_id, number, branch_id, reason, note, value_in, value_out, approved_by,
				                        created_by, actor_name, business_date)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, TenantContext.requireShopId(),
				number, branch.id(), reason, trim(in.note(), 500), Amounts.money(valueIn), Amounts.money(valueOut),
				approvedBy, userId(), CurrentActor.name(), Date.valueOf(today));
		DocRef doc = new DocRef("ADJUSTMENT", id, number);
		List<Move> moves = new ArrayList<>();
		for (Planned p : planned) {
			if (p.product().serial()) {
				if (p.qty().signum() < 0) {
					for (StockService.SerialUnit unit : p.serials()) {
						stock.setSerialStatus(unit.id(), "WRITTEN_OFF", null);
						moves.add(new Move(p.product().id(), branch.id(), null, unit.id(), BigDecimal.ONE.negate(),
								unit.cost(), reason));
						insertLine(id, p.product().id(), null, unit.id(), BigDecimal.ONE.negate(), unit.cost());
					}
				}
				else {
					stock.averageIn(p.product().id(), p.qty(), p.unitCost());
					List<Long> ids = stock.createSerials(p.product().id(), branch.id(), p.input().newSerials(),
							p.unitCost(), "ADJUSTMENT", null, null, null);
					for (Long serialId : ids) {
						moves.add(new Move(p.product().id(), branch.id(), null, serialId, BigDecimal.ONE, p.unitCost(),
								reason));
						insertLine(id, p.product().id(), null, serialId, BigDecimal.ONE, p.unitCost());
					}
				}
				continue;
			}
			if (p.qty().signum() > 0) {
				stock.averageIn(p.product().id(), p.qty(), p.unitCost());
			}
			moves.add(new Move(p.product().id(), branch.id(), p.batchId(), null, p.qty(), p.unitCost(), reason));
			insertLine(id, p.product().id(), p.batchId(), null, p.qty(), p.unitCost());
		}
		List<Move> outs = moves.stream().filter(m -> m.qty().signum() < 0).toList();
		List<Move> ins = moves.stream().filter(m -> m.qty().signum() > 0).toList();
		stock.apply(MoveType.ADJUST_OUT, doc, outs);
		stock.apply(MoveType.ADJUST_IN, doc, ins);
		audit.record("STOCK_ADJUSTED", "adjustment", id, null,
				Map.of("number", number, "reason", reason, "valueOut", valueOut.toPlainString()));
		return get(id);
	}

	/**
	 * Opening stock per branch (ONB-03): posted as OPENING movements, not as a purchase, at the cost the owner enters
	 * (which becomes the average cost).
	 */
	@Transactional
	public AdjustmentView opening(OpeningStockInput in) {
		var branch = branches.requireActive(in.branchId());
		if (in.lines() == null || in.lines().isEmpty()) {
			throw ApiException.field("lines", "required", "Add at least one item");
		}
		record Planned(ProductRef product, BigDecimal qty, BigDecimal cost, Long batchId, List<NewSerial> serials) {
		}
		List<Planned> planned = new ArrayList<>();
		BigDecimal valueIn = BigDecimal.ZERO;
		for (int i = 0; i < in.lines().size(); i++) {
			OpeningLineInput line = in.lines().get(i);
			String f = "lines[" + i + "]";
			if (line.productId() == null) {
				throw ApiException.field(f + ".productId", "required", "Choose a product");
			}
			ProductRef product = catalog.require(line.productId());
			if (!product.tracksStock()) {
				throw ApiException.rule("not_stocked", product.name() + " does not keep stock");
			}
			BigDecimal cost = Amounts.parseMoney(line.unitCost(), f + ".unitCost");
			if (cost == null || !CurrentActor.hasPermission(Permissions.COST_VIEW)) {
				cost = product.costAvg();
			}
			BigDecimal qty;
			Long batchId = null;
			if (product.serial()) {
				if (line.serials() == null || line.serials().isEmpty()) {
					throw ApiException.field(f + ".serials", "required", "Enter the IMEI/serial of each unit");
				}
				qty = BigDecimal.valueOf(line.serials().size());
			}
			else {
				qty = Amounts.parseQty(line.qty(), f + ".qty");
				if (qty == null || qty.signum() <= 0) {
					throw ApiException.field(f + ".qty", "required", "Enter the quantity");
				}
				ProductCatalog.checkQty(product, catalog.unit(product, null), qty, f + ".qty");
				if (product.batch()) {
					if (line.expiry() == null && features.isOn(ModuleKey.BATCHES)) {
						throw ApiException.field(f + ".expiry", "required", "Enter the expiry date");
					}
					batchId = stock.batch(product.id(), line.batchNo(), line.expiry(), null, cost, null, null);
				}
			}
			valueIn = valueIn.add(Amounts.money(cost.multiply(qty)));
			planned.add(new Planned(product, qty, cost, batchId, line.serials()));
		}
		planned.sort(java.util.Comparator.comparingLong(p -> p.product().id()));
		String number = numbering.nextNumber("ADJUSTMENT", branch.id());
		long id = jdbc.queryForObject("""
				insert into adjustment (shop_id, number, branch_id, reason, note, value_in, created_by, actor_name,
				                        business_date)
				values (?, ?, ?, 'OPENING', 'Opening stock', ?, ?, ?, ?) returning id""", Long.class,
				TenantContext.requireShopId(), number, branch.id(), Amounts.money(valueIn), userId(),
				CurrentActor.name(), Date.valueOf(time.today()));
		DocRef doc = new DocRef("OPENING", id, number);
		List<Move> moves = new ArrayList<>();
		for (Planned p : planned) {
			stock.averageIn(p.product().id(), p.qty(), p.cost());
			if (p.product().serial()) {
				for (Long serialId : stock.createSerials(p.product().id(), branch.id(), p.serials(), p.cost(), "OPENING",
						null, null, null)) {
					moves.add(new Move(p.product().id(), branch.id(), null, serialId, BigDecimal.ONE, p.cost(), null));
					insertLine(id, p.product().id(), null, serialId, BigDecimal.ONE, p.cost());
				}
			}
			else {
				moves.add(new Move(p.product().id(), branch.id(), p.batchId(), null, p.qty(), p.cost(), null));
				insertLine(id, p.product().id(), p.batchId(), null, p.qty(), p.cost());
			}
		}
		stock.apply(MoveType.OPENING, doc, moves);
		audit.record("OPENING_STOCK", "adjustment", id, null, Map.of("number", number, "lines", planned.size()));
		return get(id);
	}

	@Transactional(readOnly = true)
	public AdjustmentView get(long id) {
		boolean cost = CurrentActor.hasPermission(Permissions.COST_VIEW);
		Map<String, Object> row = jdbc.queryForList("""
				select a.*, b.name as branch_name, u.display_name as approver from adjustment a
				join branch b on b.id = a.branch_id left join app_user u on u.id = a.approved_by where a.id = ?"""
				+ branches.filter("a.branch_id"), id).stream().findFirst()
			.orElseThrow(() -> ApiException.notFound("Adjustment"));
		List<AdjustmentLineView> lines = jdbc.query("""
				select l.*, p.name, bt.batch_no, s.serial from adjustment_line l join product p on p.id = l.product_id
				left join batch bt on bt.id = l.batch_id left join serial_unit s on s.id = l.serial_id
				where l.adjustment_id = ? order by l.id""",
				(rs, i) -> new AdjustmentLineView(rs.getLong("product_id"), rs.getString("name"),
						rs.getString("batch_no"), rs.getString("serial"), Amounts.qtyText(rs.getBigDecimal("qty")),
						cost ? Amounts.text(rs.getBigDecimal("unit_cost")) : null,
						cost ? Amounts.text(rs.getBigDecimal("value")) : null),
				id);
		return new AdjustmentView(id, (String) row.get("number"), ((Number) row.get("branch_id")).longValue(),
				(String) row.get("branch_name"), (String) row.get("reason"), (String) row.get("note"),
				cost ? Amounts.text((BigDecimal) row.get("value_in")) : null,
				cost ? Amounts.text((BigDecimal) row.get("value_out")) : null, (String) row.get("approver"),
				(String) row.get("actor_name"), ((Date) row.get("business_date")).toLocalDate(),
				((java.sql.Timestamp) row.get("at")).toInstant(), lines);
	}

	@Transactional(readOnly = true)
	public PageResponse<AdjustmentListItem> list(Long branchId, String reason, LocalDate from, LocalDate to, int page,
			int size) {
		boolean cost = CurrentActor.hasPermission(Permissions.COST_VIEW);
		StringBuilder where = new StringBuilder(" where 1=1" + branches.filter("a.branch_id"));
		List<Object> args = new ArrayList<>();
		if (branchId != null) {
			where.append(" and a.branch_id = ?");
			args.add(branchId);
		}
		if (reason != null && !reason.isBlank()) {
			where.append(" and a.reason = ?");
			args.add(reason.trim().toUpperCase());
		}
		if (from != null) {
			where.append(" and a.business_date >= ?");
			args.add(Date.valueOf(from));
		}
		if (to != null) {
			where.append(" and a.business_date <= ?");
			args.add(Date.valueOf(to));
		}
		long total = jdbc.queryForObject("select count(*) from adjustment a" + where, Long.class, args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(size);
		pageArgs.add((long) page * size);
		List<AdjustmentListItem> items = jdbc.query("""
				select a.*, b.name as branch_name, (select count(*) from adjustment_line l where l.adjustment_id = a.id) as n
				from adjustment a join branch b on b.id = a.branch_id""" + where + " order by a.id desc limit ? offset ?",
				(rs, i) -> new AdjustmentListItem(rs.getLong("id"), rs.getString("number"), rs.getString("branch_name"),
						rs.getString("reason"), rs.getInt("n"), cost ? Amounts.text(rs.getBigDecimal("value_in")) : null,
						cost ? Amounts.text(rs.getBigDecimal("value_out")) : null, rs.getString("actor_name"),
						rs.getTimestamp("at").toInstant()),
				pageArgs.toArray());
		return new PageResponse<>(items, page, size, total);
	}

	private void insertLine(long adjustmentId, long productId, Long batchId, Long serialId, BigDecimal qty,
			BigDecimal unitCost) {
		jdbc.update("""
				insert into adjustment_line (shop_id, adjustment_id, product_id, batch_id, serial_id, qty, unit_cost, value)
				values (?, ?, ?, ?, ?, ?, ?, ?)""", TenantContext.requireShopId(), adjustmentId, productId, batchId,
				serialId, Amounts.qty(qty), Amounts.money(unitCost), Amounts.money(qty.abs().multiply(unitCost)));
	}

	static Long userId() {
		return CurrentActor.type() == ActorType.USER ? CurrentActor.id() : null;
	}

	static String trim(String value, int max) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String t = value.trim();
		return t.length() > max ? t.substring(0, max) : t;
	}
}
