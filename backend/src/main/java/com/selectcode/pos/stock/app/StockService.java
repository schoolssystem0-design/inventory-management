package com.selectcode.pos.stock.app;

import com.selectcode.pos.shared.money.Amounts;
import com.selectcode.pos.shared.money.SaleMath;
import com.selectcode.pos.shared.security.CurrentActor;
import com.selectcode.pos.shared.security.ActorType;
import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.shared.web.ApiException;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only code that changes stock (docs/ARCHITECTURE.md §6, D1–D3, NFR-03/04/05). Every method runs inside the
 * caller's document transaction, so the document, its stock moves, payments and ledgers commit together or not at all.
 *
 * <ul>
 * <li>{@link #apply}: locks each stock row (in a fixed order, so two documents never deadlock), checks that stock
 * does not go below zero unless the shop allows it, updates the balance and writes a signed movement with the
 * balance after.</li>
 * <li>{@link #averageIn}: weighted average cost on receipts (PUR-04), across all branches of the shop.</li>
 * <li>Serial units (IMEI): one row per unit, locked before it moves, so the same IMEI cannot be sold twice
 * (TC-STK-03).</li>
 * <li>Batches: stock kept per batch; {@link #pickBatches} picks the earliest expiry first and never an expired batch
 * (MED-03, MED-04).</li>
 * </ul>
 */
@Service
public class StockService {

	public enum MoveType {
		OPENING, PURCHASE, SALE, SALE_RETURN, PURCHASE_RETURN, ADJUST_IN, ADJUST_OUT, COUNT, TRANSFER_OUT, TRANSFER_IN,
		CANCEL, BUYBACK, CHALLAN, CHALLAN_BACK
	}

	/** The document a movement belongs to (shown in stock history). */
	public record DocRef(String docType, long docId, String docNumber) {
	}

	/**
	 * One change of stock. {@code qty} is signed in base units (negative = out). {@code unitCost} is per base unit and
	 * only recorded (cost changes happen in {@link #averageIn}).
	 */
	public record Move(long productId, long branchId, Long batchId, Long serialId, BigDecimal qty, BigDecimal unitCost,
			String note) {

		public static Move of(long productId, long branchId, Long batchId, BigDecimal qty, BigDecimal unitCost) {
			return new Move(productId, branchId, batchId, null, qty, unitCost, null);
		}
	}

	public record Moved(Move move, BigDecimal balanceAfter) {
	}

	public record BatchPick(long batchId, String batchNo, LocalDate expiry, BigDecimal qty, BigDecimal cost) {
	}

	public record SerialUnit(long id, long productId, String serial, String serial2, String status, long branchId,
			BigDecimal cost, String condition) {
	}

	public record NewSerial(String serial, String serial2, String colour, String storage, String condition,
			String ptaStatus) {
	}

	private final JdbcTemplate jdbc;

	StockService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	// ------------------------------------------------------------------ quantities

	/**
	 * Applies the moves of one document. Lines are sorted by (product, branch, batch, serial) before locking. Fails
	 * with 409 {@code insufficient_stock} (with the available quantity) when stock would go below zero and the shop
	 * does not allow negative stock; batch stock never goes below zero.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public List<Moved> apply(MoveType type, DocRef doc, List<Move> moves) {
		long shopId = TenantContext.requireShopId();
		boolean allowNegative = Boolean.TRUE.equals(jdbc.queryForObject(
				"select allow_negative_stock from shop_settings where shop_id = ?", Boolean.class, shopId));
		List<Move> sorted = new ArrayList<>(moves);
		sorted.sort(Comparator.comparingLong(Move::productId)
			.thenComparingLong(Move::branchId)
			.thenComparingLong(m -> m.batchId() == null ? 0 : m.batchId())
			.thenComparingLong(m -> m.serialId() == null ? 0 : m.serialId()));
		Long userId = CurrentActor.type() == ActorType.USER ? CurrentActor.id() : null;
		String actor = CurrentActor.name();
		List<Moved> result = new ArrayList<>();
		for (Move move : sorted) {
			if (move.qty() == null || move.qty().signum() == 0) {
				continue;
			}
			jdbc.update("""
					insert into product_stock (shop_id, product_id, branch_id, batch_id, qty) values (?, ?, ?, ?, 0)
					on conflict (shop_id, product_id, branch_id, coalesce(batch_id, 0)) do nothing""", shopId,
					move.productId(), move.branchId(), move.batchId());
			BigDecimal current = jdbc.queryForObject("""
					select qty from product_stock
					where product_id = ? and branch_id = ? and coalesce(batch_id, 0) = ? for update""", BigDecimal.class,
					move.productId(), move.branchId(), move.batchId() == null ? 0L : move.batchId());
			BigDecimal after = Amounts.qty(current.add(move.qty()));
			if (move.qty().signum() < 0 && after.signum() < 0 && (!allowNegative || move.batchId() != null)) {
				throw insufficient(move.productId(), move.branchId(), move.batchId(), current);
			}
			jdbc.update("""
					update product_stock set qty = ?, updated_at = now()
					where product_id = ? and branch_id = ? and coalesce(batch_id, 0) = ?""", after, move.productId(),
					move.branchId(), move.batchId() == null ? 0L : move.batchId());
			jdbc.update("""
					insert into stock_movement (shop_id, product_id, branch_id, batch_id, serial_id, move_type, qty,
					                            balance_after, unit_cost, doc_type, doc_id, doc_number, user_id,
					                            actor_name, note)
					values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", shopId, move.productId(), move.branchId(),
					move.batchId(), move.serialId(), type.name(), Amounts.qty(move.qty()), after,
					Amounts.money(move.unitCost()), doc.docType(), doc.docId(), doc.docNumber(), userId, actor,
					move.note());
			result.add(new Moved(move, after));
		}
		return result;
	}

	/**
	 * Weighted average cost after receiving {@code qtyIn} base units at {@code unitCost} (call before the incoming
	 * move). Locks the product row; stock is counted across all branches.
	 * @return the new average cost
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public BigDecimal averageIn(long productId, BigDecimal qtyIn, BigDecimal unitCost) {
		BigDecimal avg = jdbc.queryForObject("select cost_avg from product where id = ? for update", BigDecimal.class,
				productId);
		BigDecimal onHand = jdbc.queryForObject(
				"select coalesce(sum(qty), 0) from product_stock where product_id = ?", BigDecimal.class, productId);
		BigDecimal next = SaleMath.averageCost(onHand, avg, qtyIn, unitCost);
		if (next.compareTo(avg) != 0) {
			jdbc.update("update product set cost_avg = ?, version = version + 1 where id = ?", next, productId);
		}
		return next;
	}

	/** Current average cost (per base unit). */
	public BigDecimal averageCost(long productId) {
		return jdbc.queryForObject("select cost_avg from product where id = ?", BigDecimal.class, productId);
	}

	/** Stock of a product at a branch (all batches). */
	public BigDecimal onHand(long productId, long branchId) {
		return jdbc.queryForObject(
				"select coalesce(sum(qty), 0) from product_stock where product_id = ? and branch_id = ?",
				BigDecimal.class, productId, branchId);
	}

	// ------------------------------------------------------------------ batches

	/** Finds the product's batch with this number or creates it (receipts, opening stock). */
	@Transactional(propagation = Propagation.MANDATORY)
	public long batch(long productId, String batchNo, LocalDate expiry, LocalDate mfg, BigDecimal cost,
			BigDecimal tradePrice, BigDecimal retailPrice) {
		if (batchNo == null || batchNo.isBlank()) {
			throw ApiException.field("batchNo", "required", "Enter the batch number");
		}
		String no = batchNo.trim().toUpperCase();
		if (no.length() > 40) {
			throw ApiException.field("batchNo", "too_long", "Batch number is too long");
		}
		List<Map<String, Object>> found = jdbc.queryForList(
				"select id, expiry_date from batch where product_id = ? and upper(batch_no) = ?", productId, no);
		if (!found.isEmpty()) {
			long id = ((Number) found.getFirst().get("id")).longValue();
			Object existingExpiry = found.getFirst().get("expiry_date");
			if (expiry != null && existingExpiry != null && !((Date) existingExpiry).toLocalDate().equals(expiry)) {
				throw ApiException.rule("batch_expiry_mismatch",
						"Batch " + no + " is already recorded with expiry " + existingExpiry);
			}
			if (cost != null) {
				jdbc.update("update batch set cost = ?, trade_price = coalesce(?, trade_price), "
						+ "retail_price = coalesce(?, retail_price), expiry_date = coalesce(expiry_date, ?) where id = ?",
						cost, tradePrice, retailPrice, expiry == null ? null : Date.valueOf(expiry), id);
			}
			return id;
		}
		return jdbc.queryForObject("""
				insert into batch (shop_id, product_id, batch_no, mfg_date, expiry_date, cost, trade_price, retail_price)
				values (?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, TenantContext.requireShopId(), productId,
				no, mfg == null ? null : Date.valueOf(mfg), expiry == null ? null : Date.valueOf(expiry), cost,
				tradePrice, retailPrice);
	}

	/**
	 * First-expiry-first-out (MED-03): batches with stock at the branch, never expired ones, earliest expiry first.
	 * The rows are locked. Fails with {@code insufficient_stock} (sellable quantity) if there is not enough.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public List<BatchPick> pickBatches(long productId, long branchId, BigDecimal qty, LocalDate today) {
		List<BatchPick> available = jdbc.query("""
				select b.id, b.batch_no, b.expiry_date, s.qty, b.cost from product_stock s join batch b on b.id = s.batch_id
				where s.product_id = ? and s.branch_id = ? and s.qty > 0 and (b.expiry_date is null or b.expiry_date >= ?)
				order by b.expiry_date nulls last, b.id
				for update of s""", (rs, i) -> new BatchPick(rs.getLong("id"), rs.getString("batch_no"),
				rs.getObject("expiry_date", LocalDate.class), rs.getBigDecimal("qty"), rs.getBigDecimal("cost")),
				productId, branchId, Date.valueOf(today));
		List<BatchPick> picks = new ArrayList<>();
		BigDecimal left = qty;
		for (BatchPick batch : available) {
			if (left.signum() <= 0) {
				break;
			}
			BigDecimal take = batch.qty().min(left);
			picks.add(new BatchPick(batch.batchId(), batch.batchNo(), batch.expiry(), take, batch.cost()));
			left = left.subtract(take);
		}
		if (left.signum() > 0) {
			BigDecimal sellable = available.stream().map(BatchPick::qty).reduce(BigDecimal.ZERO, BigDecimal::add);
			throw insufficient(productId, branchId, null, sellable);
		}
		return picks;
	}

	/** A batch the user chose: must belong to the product and not be expired (D9). */
	public BatchPick requireSellableBatch(long productId, long batchId, LocalDate today) {
		BatchPick batch = jdbc.query("""
				select id, batch_no, expiry_date, cost from batch where id = ? and product_id = ?""",
				(rs, i) -> new BatchPick(rs.getLong("id"), rs.getString("batch_no"),
						rs.getObject("expiry_date", LocalDate.class), null, rs.getBigDecimal("cost")),
				batchId, productId).stream().findFirst().orElseThrow(() -> ApiException.notFound("Batch"));
		if (batch.expiry() != null && batch.expiry().isBefore(today)) {
			throw new ApiException(HttpStatus.CONFLICT, "batch_expired",
					"Batch " + batch.batchNo() + " expired on " + batch.expiry() + " and cannot be sold",
					Map.of("batchId", batchId));
		}
		return batch;
	}

	// ------------------------------------------------------------------ serials (IMEI)

	/**
	 * Creates in-stock units (purchase, opening stock, buyback). A serial or second serial that is already active in
	 * the shop is refused with 409 {@code duplicate_serial} (MOB-03).
	 * @return the new unit ids, in input order
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public List<Long> createSerials(long productId, long branchId, List<NewSerial> serials, BigDecimal cost,
			String source, Long supplierId, Long grnId, Long buybackId) {
		List<Long> ids = new ArrayList<>();
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (NewSerial s : serials) {
			String serial = cleanSerial(s.serial(), "serial");
			String serial2 = s.serial2() == null || s.serial2().isBlank() ? null : cleanSerial(s.serial2(), "serial2");
			for (String value : serial2 == null ? List.of(serial) : List.of(serial, serial2)) {
				if (!seen.add(value)) {
					throw duplicateSerial(value);
				}
				Integer clash = jdbc.queryForObject("""
						select count(*) from serial_unit where status <> 'VOID'
						and (upper(serial) = ? or upper(serial2) = ?)""", Integer.class, value, value);
				if (clash != null && clash > 0) {
					throw duplicateSerial(value);
				}
			}
			String condition = s.condition() == null || s.condition().isBlank() ? "NEW" : s.condition().trim().toUpperCase();
			if (!List.of("NEW", "USED", "BOX_OPEN").contains(condition)) {
				throw ApiException.field("condition", "invalid", "Condition is NEW, USED or BOX_OPEN");
			}
			String pta = s.ptaStatus() == null || s.ptaStatus().isBlank() ? null : s.ptaStatus().trim().toUpperCase();
			if (pta != null && !List.of("APPROVED", "NON_PTA", "PATCHED").contains(pta)) {
				throw ApiException.field("ptaStatus", "invalid", "PTA status is APPROVED, NON_PTA or PATCHED");
			}
			try {
				ids.add(jdbc.queryForObject("""
						insert into serial_unit (shop_id, product_id, serial, serial2, colour, storage, condition, pta_status,
						                         branch_id, cost, source, supplier_id, grn_id, buyback_id)
						values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class,
						TenantContext.requireShopId(), productId, serial, serial2, trim(s.colour(), 40),
						trim(s.storage(), 40), condition, pta, branchId, Amounts.money(Amounts.nz(cost)), source,
						supplierId, grnId, buybackId));
			}
			catch (DuplicateKeyException e) {
				throw duplicateSerial(serial);
			}
		}
		return ids;
	}

	/**
	 * Locks a unit for selling or moving. It must be the product's, in stock and at the branch; otherwise 409
	 * {@code serial_unavailable} (another till sold it first, or it is elsewhere).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public SerialUnit lockSerial(String serialOrId, Long productId, Long branchId, String expectedStatus) {
		String value = serialOrId == null ? "" : serialOrId.trim().toUpperCase();
		List<SerialUnit> rows = jdbc.query("""
				select * from serial_unit where status <> 'VOID' and (upper(serial) = ? or upper(serial2) = ?)
				for update""", (rs, i) -> serial(rs), value, value);
		if (rows.isEmpty()) {
			throw new ApiException(HttpStatus.CONFLICT, "serial_unavailable", "IMEI/serial " + value + " is not in stock",
					Map.of("serial", value));
		}
		SerialUnit unit = rows.getFirst();
		if ((productId != null && unit.productId() != productId) || !expectedStatus.equals(unit.status())
				|| (branchId != null && unit.branchId() != branchId)) {
			throw new ApiException(HttpStatus.CONFLICT, "serial_unavailable",
					"IMEI/serial " + unit.serial() + " is " + describe(unit.status()) + " and cannot be used here",
					Map.of("serial", unit.serial(), "status", unit.status()));
		}
		return unit;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public SerialUnit lockSerialById(long id) {
		return jdbc.query("select * from serial_unit where id = ? for update", (rs, i) -> serial(rs), id)
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Serial unit"));
	}

	/** Records a unit's new status (and branch); the quantity move is written separately through {@link #apply}. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void setSerialStatus(long serialId, String status, Long branchId) {
		jdbc.update("update serial_unit set status = ?, branch_id = coalesce(?, branch_id), updated_at = now() where id = ?",
				status, branchId, serialId);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void markSerialSold(long serialId, long saleId, Long customerId, LocalDate warrantyUntil) {
		jdbc.update("""
				update serial_unit set status = 'SOLD', sale_id = ?, customer_id = ?, sold_at = now(), warranty_until = ?,
				       updated_at = now() where id = ?""", saleId, customerId,
				warrantyUntil == null ? null : Date.valueOf(warrantyUntil), serialId);
	}

	/** Back in stock after a return or a cancelled sale; a returned phone can be marked used. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void markSerialReturned(long serialId, boolean restock, String condition) {
		jdbc.update("""
				update serial_unit set status = ?, condition = coalesce(?, condition), sale_id = null, customer_id = null,
				       sold_at = null, warranty_until = null, updated_at = now() where id = ?""",
				restock ? "IN_STOCK" : "DEFECTIVE", condition, serialId);
	}

	// ------------------------------------------------------------------ helpers

	private ApiException insufficient(long productId, long branchId, Long batchId, BigDecimal available) {
		Map<String, Object> names = jdbc.queryForMap("""
				select p.name as product, b.name as branch from product p, branch b where p.id = ? and b.id = ?""",
				productId, branchId);
		String batch = batchId == null ? ""
				: " (batch " + jdbc.queryForObject("select batch_no from batch where id = ?", String.class, batchId) + ")";
		BigDecimal shown = available.signum() < 0 ? BigDecimal.ZERO : available;
		return new ApiException(HttpStatus.CONFLICT, "insufficient_stock",
				"Only " + shown.stripTrailingZeros().toPlainString() + " of " + names.get("product") + batch + " left at "
						+ names.get("branch") + ".",
				Map.of("productId", productId, "branchId", branchId, "available", Amounts.qtyText(shown)));
	}

	private static ApiException duplicateSerial(String serial) {
		return new ApiException(HttpStatus.CONFLICT, "duplicate_serial",
				"IMEI/serial " + serial + " is already in the system", Map.of("serial", serial));
	}

	private static String cleanSerial(String raw, String field) {
		String value = raw == null ? "" : raw.trim().toUpperCase().replaceAll("\\s+", "");
		if (value.isEmpty()) {
			throw ApiException.field(field, "required", "Enter the IMEI or serial number");
		}
		if (value.length() > 40 || !value.matches("[A-Z0-9/._-]+")) {
			throw ApiException.field(field, "invalid", "IMEI/serial " + value + " is not valid");
		}
		return value;
	}

	private static String describe(String status) {
		return switch (status) {
			case "SOLD" -> "already sold";
			case "IN_TRANSIT" -> "in transit between branches";
			case "IN_STOCK" -> "in stock at another branch or of another product";
			default -> status.toLowerCase().replace('_', ' ');
		};
	}

	private static String trim(String value, int max) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String t = value.trim();
		return t.length() > max ? t.substring(0, max) : t;
	}

	static SerialUnit serial(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new SerialUnit(rs.getLong("id"), rs.getLong("product_id"), rs.getString("serial"), rs.getString("serial2"),
				rs.getString("status"), rs.getLong("branch_id"), rs.getBigDecimal("cost"), rs.getString("condition"));
	}
}
