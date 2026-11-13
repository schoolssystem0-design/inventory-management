package com.selectcode.pos.stock.app;

import com.selectcode.pos.catalog.app.ProductCatalog;
import com.selectcode.pos.catalog.app.ProductCatalog.ProductRef;
import com.selectcode.pos.shared.audit.AuditService;
import com.selectcode.pos.shared.money.Amounts;
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
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transfers between branches (STK-04): dispatch takes the stock out of the sending branch and the goods are in
 * transit (sellable nowhere) until the receiving branch confirms; a short receipt records the missing quantity.
 */
@Service
public class StockTransfers {

	public record TransferLineInput(Long productId, String qty, Long batchId, List<String> serials) {
	}

	public record TransferInput(Long fromBranchId, Long toBranchId, String note, List<TransferLineInput> lines) {
	}

	public record ReceiveLineInput(Long lineId, String qtyReceived) {
	}

	public record TransferLineView(long id, long productId, String productName, String batchNo, String serial,
			String qtySent, String qtyReceived, String unitCost) {
	}

	public record TransferView(long id, String number, long fromBranchId, String fromBranchName, long toBranchId,
			String toBranchName, String status, String note, String dispatchedBy, Instant dispatchedAt,
			String receivedBy, Instant receivedAt, String shortQty, List<TransferLineView> lines) {
	}

	public record TransferListItem(long id, String number, String fromBranchName, String toBranchName, String status,
			int lines, Instant dispatchedAt, Instant receivedAt) {
	}

	private final JdbcTemplate jdbc;

	private final StockService stock;

	private final ProductCatalog catalog;

	private final BranchAccess branches;

	private final ShopConfigService numbering;

	private final AuditService audit;

	private final ShopTime time;

	StockTransfers(JdbcTemplate jdbc, StockService stock, ProductCatalog catalog, BranchAccess branches,
			ShopConfigService numbering, AuditService audit, ShopTime time) {
		this.jdbc = jdbc;
		this.stock = stock;
		this.catalog = catalog;
		this.branches = branches;
		this.numbering = numbering;
		this.audit = audit;
		this.time = time;
	}

	@Transactional
	public TransferView dispatch(TransferInput in) {
		var from = branches.requireActive(in.fromBranchId());
		if (in.toBranchId() == null) {
			throw ApiException.field("toBranchId", "required", "Choose the branch receiving the goods");
		}
		Map<String, Object> to = jdbc.queryForList("select id, name, active from branch where id = ?", in.toBranchId())
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Branch"));
		if (!(Boolean) to.get("active")) {
			throw ApiException.rule("branch_inactive", to.get("name") + " is switched off");
		}
		if (from.id() == in.toBranchId()) {
			throw ApiException.field("toBranchId", "same_branch", "Choose a different branch to send to");
		}
		if (in.lines() == null || in.lines().isEmpty()) {
			throw ApiException.field("lines", "required", "Add at least one item");
		}
		LocalDateHolder today = new LocalDateHolder(time.today());
		String number = numbering.nextNumber("TRANSFER", from.id());
		long id = jdbc.queryForObject("""
				insert into transfer (shop_id, number, from_branch_id, to_branch_id, note, dispatched_by, business_date)
				values (?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, TenantContext.requireShopId(), number,
				from.id(), in.toBranchId(), StockAdjustments.trim(in.note(), 500), StockAdjustments.userId(),
				Date.valueOf(today.date()));
		DocRef doc = new DocRef("TRANSFER", id, number);
		List<Move> moves = new ArrayList<>();
		for (int i = 0; i < in.lines().size(); i++) {
			TransferLineInput line = in.lines().get(i);
			String f = "lines[" + i + "]";
			if (line.productId() == null) {
				throw ApiException.field(f + ".productId", "required", "Choose a product");
			}
			ProductRef product = catalog.require(line.productId());
			if (!product.tracksStock()) {
				throw ApiException.rule("not_stocked", product.name() + " does not keep stock");
			}
			if (product.serial()) {
				if (line.serials() == null || line.serials().isEmpty()) {
					throw ApiException.field(f + ".serials", "required", "Scan the IMEI/serial of each unit");
				}
				for (String serial : line.serials()) {
					StockService.SerialUnit unit = stock.lockSerial(serial, product.id(), from.id(), "IN_STOCK");
					stock.setSerialStatus(unit.id(), "IN_TRANSIT", null);
					insertLine(id, product.id(), null, unit.id(), BigDecimal.ONE, unit.cost());
					moves.add(new Move(product.id(), from.id(), null, unit.id(), BigDecimal.ONE.negate(), unit.cost(),
							"To " + to.get("name")));
				}
				continue;
			}
			BigDecimal qty = Amounts.parseQty(line.qty(), f + ".qty");
			if (qty == null || qty.signum() <= 0) {
				throw ApiException.field(f + ".qty", "required", "Enter the quantity");
			}
			ProductCatalog.checkQty(product, catalog.unit(product, null), qty, f + ".qty");
			if (product.batch()) {
				List<StockService.BatchPick> picks = line.batchId() != null
						? List.of(new StockService.BatchPick(line.batchId(), null, null, qty, null))
						: stock.pickBatches(product.id(), from.id(), qty, today.date());
				for (StockService.BatchPick pick : picks) {
					insertLine(id, product.id(), pick.batchId(), null, pick.qty(), product.costAvg());
					moves.add(new Move(product.id(), from.id(), pick.batchId(), null, pick.qty().negate(),
							product.costAvg(), "To " + to.get("name")));
				}
			}
			else {
				insertLine(id, product.id(), null, null, qty, product.costAvg());
				moves.add(new Move(product.id(), from.id(), null, null, qty.negate(), product.costAvg(),
						"To " + to.get("name")));
			}
		}
		stock.apply(MoveType.TRANSFER_OUT, doc, moves);
		audit.record("TRANSFER_DISPATCHED", "transfer", id, null,
				Map.of("number", number, "from", from.name(), "to", to.get("name")));
		return get(id);
	}

	/**
	 * The receiving branch confirms what arrived. Without lines, everything sent is received; a line received short
	 * keeps its missing quantity on the transfer (shown as short).
	 */
	@Transactional
	public TransferView receive(long id, List<ReceiveLineInput> received) {
		Map<String, Object> t = jdbc.queryForList("select * from transfer where id = ? for update", id)
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Transfer"));
		long toBranch = ((Number) t.get("to_branch_id")).longValue();
		long fromBranch = ((Number) t.get("from_branch_id")).longValue();
		if (!branches.allowed(toBranch) && !branches.allowed(fromBranch)) {
			throw ApiException.notFound("Transfer");
		}
		if (!branches.allowed(toBranch)) {
			throw ApiException.forbidden("not_receiving_branch", "Only the receiving branch can receive this transfer");
		}
		if (!"DISPATCHED".equals(t.get("status"))) {
			throw ApiException.rule("transfer_closed", "This transfer is already " + t.get("status").toString().toLowerCase());
		}
		Map<Long, BigDecimal> wanted = new HashMap<>();
		if (received != null) {
			for (int i = 0; i < received.size(); i++) {
				ReceiveLineInput r = received.get(i);
				if (r.lineId() == null) {
					throw ApiException.field("lines[" + i + "].lineId", "required", "Choose the line");
				}
				BigDecimal qty = Amounts.parseQty(r.qtyReceived(), "lines[" + i + "].qtyReceived");
				wanted.put(r.lineId(), qty == null ? BigDecimal.ZERO : qty);
			}
		}
		String number = (String) t.get("number");
		DocRef doc = new DocRef("TRANSFER", id, number);
		List<Move> moves = new ArrayList<>();
		for (Map<String, Object> line : jdbc.queryForList("select * from transfer_line where transfer_id = ? order by id",
				id)) {
			long lineId = ((Number) line.get("id")).longValue();
			BigDecimal sent = (BigDecimal) line.get("qty_sent");
			BigDecimal got = wanted.getOrDefault(lineId, sent);
			if (got.compareTo(sent) > 0) {
				throw ApiException.rule("over_received", "More received than was sent on a line");
			}
			long productId = ((Number) line.get("product_id")).longValue();
			Long batchId = line.get("batch_id") == null ? null : ((Number) line.get("batch_id")).longValue();
			Long serialId = line.get("serial_id") == null ? null : ((Number) line.get("serial_id")).longValue();
			BigDecimal cost = (BigDecimal) line.get("unit_cost");
			jdbc.update("update transfer_line set qty_received = ? where id = ?", got, lineId);
			if (serialId != null) {
				stock.lockSerialById(serialId);
				stock.setSerialStatus(serialId, got.signum() > 0 ? "IN_STOCK" : "WRITTEN_OFF", toBranch);
			}
			if (got.signum() > 0) {
				moves.add(new Move(productId, toBranch, batchId, serialId, got, cost, "From transfer " + number));
			}
		}
		stock.apply(MoveType.TRANSFER_IN, doc, moves);
		jdbc.update("update transfer set status = 'RECEIVED', received_by = ?, received_at = now() where id = ?",
				StockAdjustments.userId(), id);
		audit.record("TRANSFER_RECEIVED", "transfer", id, null, Map.of("number", number));
		return get(id);
	}

	@Transactional(readOnly = true)
	public TransferView get(long id) {
		boolean cost = CurrentActor.hasPermission(Permissions.COST_VIEW);
		Map<String, Object> t = jdbc.queryForList("""
				select t.*, f.name as from_name, d.name as to_name, ud.display_name as dispatcher, ur.display_name as receiver
				from transfer t join branch f on f.id = t.from_branch_id join branch d on d.id = t.to_branch_id
				left join app_user ud on ud.id = t.dispatched_by left join app_user ur on ur.id = t.received_by
				where t.id = ?""", id).stream().findFirst().orElseThrow(() -> ApiException.notFound("Transfer"));
		if (!branches.allowed(((Number) t.get("from_branch_id")).longValue())
				&& !branches.allowed(((Number) t.get("to_branch_id")).longValue())) {
			throw ApiException.notFound("Transfer");
		}
		List<TransferLineView> lines = jdbc.query("""
				select l.*, p.name, b.batch_no, s.serial from transfer_line l join product p on p.id = l.product_id
				left join batch b on b.id = l.batch_id left join serial_unit s on s.id = l.serial_id
				where l.transfer_id = ? order by l.id""",
				(rs, i) -> new TransferLineView(rs.getLong("id"), rs.getLong("product_id"), rs.getString("name"),
						rs.getString("batch_no"), rs.getString("serial"), Amounts.qtyText(rs.getBigDecimal("qty_sent")),
						Amounts.qtyText(rs.getBigDecimal("qty_received")),
						cost ? Amounts.text(rs.getBigDecimal("unit_cost")) : null),
				id);
		BigDecimal shortQty = lines.stream()
			.filter(l -> l.qtyReceived() != null)
			.map(l -> new BigDecimal(l.qtySent()).subtract(new BigDecimal(l.qtyReceived())))
			.reduce(BigDecimal.ZERO, BigDecimal::add);
		return new TransferView(id, (String) t.get("number"), ((Number) t.get("from_branch_id")).longValue(),
				(String) t.get("from_name"), ((Number) t.get("to_branch_id")).longValue(), (String) t.get("to_name"),
				(String) t.get("status"), (String) t.get("note"), (String) t.get("dispatcher"),
				((Timestamp) t.get("dispatched_at")).toInstant(), (String) t.get("receiver"),
				t.get("received_at") == null ? null : ((Timestamp) t.get("received_at")).toInstant(),
				Amounts.qtyText(shortQty), lines);
	}

	@Transactional(readOnly = true)
	public PageResponse<TransferListItem> list(String status, Long branchId, int page, int size) {
		List<Long> allowed = branches.allowedIds();
		StringBuilder where = new StringBuilder(" where 1=1");
		List<Object> args = new ArrayList<>();
		if (allowed != null) {
			where.append(allowed.isEmpty() ? " and false"
					: " and (t.from_branch_id = any(?) or t.to_branch_id = any(?))");
			if (!allowed.isEmpty()) {
				args.add(allowed.toArray(Long[]::new));
				args.add(allowed.toArray(Long[]::new));
			}
		}
		if (status != null && !status.isBlank()) {
			where.append(" and t.status = ?");
			args.add(status.trim().toUpperCase());
		}
		if (branchId != null) {
			where.append(" and (t.from_branch_id = ? or t.to_branch_id = ?)");
			args.add(branchId);
			args.add(branchId);
		}
		long total = jdbc.queryForObject("select count(*) from transfer t" + where, Long.class, args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(size);
		pageArgs.add((long) page * size);
		List<TransferListItem> items = jdbc.query("""
				select t.*, f.name as from_name, d.name as to_name,
				       (select count(*) from transfer_line l where l.transfer_id = t.id) as n
				from transfer t join branch f on f.id = t.from_branch_id join branch d on d.id = t.to_branch_id"""
				+ where + " order by t.id desc limit ? offset ?",
				(rs, i) -> new TransferListItem(rs.getLong("id"), rs.getString("number"), rs.getString("from_name"),
						rs.getString("to_name"), rs.getString("status"), rs.getInt("n"),
						rs.getTimestamp("dispatched_at").toInstant(),
						rs.getTimestamp("received_at") == null ? null : rs.getTimestamp("received_at").toInstant()),
				pageArgs.toArray());
		return new PageResponse<>(items, page, size, total);
	}

	private void insertLine(long transferId, long productId, Long batchId, Long serialId, BigDecimal qty,
			BigDecimal unitCost) {
		jdbc.update("""
				insert into transfer_line (shop_id, transfer_id, product_id, batch_id, serial_id, qty_sent, unit_cost)
				values (?, ?, ?, ?, ?, ?, ?)""", TenantContext.requireShopId(), transferId, productId, batchId, serialId,
				Amounts.qty(qty), Amounts.money(unitCost));
	}

	private record LocalDateHolder(java.time.LocalDate date) {
	}
}
