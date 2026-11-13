package com.selectcode.pos.purchasing.app;

import com.selectcode.pos.shared.audit.AuditService;
import com.selectcode.pos.shared.docs.TableDoc;
import com.selectcode.pos.shared.money.Amounts;
import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.shared.time.ShopTime;
import com.selectcode.pos.shared.web.ApiException;
import com.selectcode.pos.shared.web.PageResponse;
import com.selectcode.pos.shop.app.ShopConfigService;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Suppliers (PUR-01) with their ledger and statement (PUR-06). The balance is what the shop owes the supplier:
 * receipts are credits, payments and returns are debits.
 */
@Service
public class SupplierService {

	public record SupplierInput(String name, String company, String phone, String email, String ntn, String address,
			Integer termsDays, String openingBalance, String notes, Long version) {
	}

	public record SupplierView(long id, String code, String name, String company, String phone, String email,
			String ntn, String address, int termsDays, String openingBalance, String balance, String notes,
			boolean active, Instant createdAt, long version) {
	}

	public record SupplierListItem(long id, String code, String name, String company, String phone, int termsDays,
			String balance, boolean active) {
	}

	public record SupplierLedgerLine(long id, LocalDate date, Instant at, String docType, Long docId,
			String docNumber, String description, String debit, String credit, String balanceAfter) {
	}

	public record SupplierStatement(long supplierId, String supplierName, LocalDate from, LocalDate to, String opening,
			String totalDebit, String totalCredit, String closing, List<SupplierLedgerLine> lines) {
	}

	private final JdbcTemplate jdbc;

	private final ShopConfigService numbering;

	private final AuditService audit;

	private final ShopTime time;

	SupplierService(JdbcTemplate jdbc, ShopConfigService numbering, AuditService audit, ShopTime time) {
		this.jdbc = jdbc;
		this.numbering = numbering;
		this.audit = audit;
		this.time = time;
	}

	@Transactional(readOnly = true)
	public PageResponse<SupplierListItem> list(String q, Boolean withBalance, Boolean active, int page, int size) {
		StringBuilder where = new StringBuilder(" where 1=1");
		List<Object> args = new ArrayList<>();
		where.append(active == null || active ? " and active" : " and not active");
		if (q != null && !q.isBlank()) {
			where.append(" and (lower(name) like ? or lower(coalesce(company, '')) like ? or phone like ? or upper(code) = ?)");
			String like = "%" + q.trim().toLowerCase() + "%";
			args.add(like);
			args.add(like);
			args.add("%" + q.trim() + "%");
			args.add(q.trim().toUpperCase());
		}
		if (Boolean.TRUE.equals(withBalance)) {
			where.append(" and balance > 0");
		}
		long total = jdbc.queryForObject("select count(*) from supplier" + where, Long.class, args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(size);
		pageArgs.add((long) page * size);
		List<SupplierListItem> items = jdbc.query("select * from supplier" + where + " order by name limit ? offset ?",
				(rs, i) -> new SupplierListItem(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
						rs.getString("company"), rs.getString("phone"), rs.getInt("terms_days"),
						Amounts.text(rs.getBigDecimal("balance")), rs.getBoolean("active")),
				pageArgs.toArray());
		return new PageResponse<>(items, page, size, total);
	}

	@Transactional(readOnly = true)
	public SupplierView get(long id) {
		return jdbc.query("select * from supplier where id = ?", (rs, i) -> new SupplierView(rs.getLong("id"),
				rs.getString("code"), rs.getString("name"), rs.getString("company"), rs.getString("phone"),
				rs.getString("email"), rs.getString("ntn"), rs.getString("address"), rs.getInt("terms_days"),
				Amounts.text(rs.getBigDecimal("opening_balance")), Amounts.text(rs.getBigDecimal("balance")),
				rs.getString("notes"), rs.getBoolean("active"), rs.getTimestamp("created_at").toInstant(),
				rs.getLong("version")), id).stream().findFirst().orElseThrow(() -> ApiException.notFound("Supplier"));
	}

	@Transactional
	public SupplierView create(SupplierInput in) {
		String name = required(in.name(), "name", 200);
		int terms = in.termsDays() == null ? 0 : in.termsDays();
		if (terms < 0 || terms > 365) {
			throw ApiException.field("termsDays", "out_of_range", "Payment terms are 0 to 365 days");
		}
		BigDecimal opening = signed(in.openingBalance(), "openingBalance");
		String code = numbering.nextNumber("SUPPLIER", null);
		long id = jdbc.queryForObject("""
				insert into supplier (shop_id, code, name, company, phone, email, ntn, address, terms_days,
				                      opening_balance, notes)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, TenantContext.requireShopId(), code,
				name, trim(in.company(), 200), trim(in.phone(), 30), trim(in.email(), 254), trim(in.ntn(), 30),
				trim(in.address(), 500), terms, opening, trim(in.notes(), 1000));
		if (opening.signum() != 0) {
			post(id, "OPENING", null, null, "Opening balance", opening.signum() < 0 ? opening.negate() : null,
					opening.signum() > 0 ? opening : null);
		}
		audit.record("SUPPLIER_CREATED", "supplier", id, null, Map.of("name", name));
		return get(id);
	}

	@Transactional
	public SupplierView update(long id, SupplierInput in) {
		Map<String, Object> row = jdbc.queryForList("select * from supplier where id = ? for update", id)
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Supplier"));
		if (in.version() != null && in.version() != ((Number) row.get("version")).longValue()) {
			throw ApiException.conflict("stale_version", "Someone else changed this supplier. Reload and try again.");
		}
		int terms = in.termsDays() == null ? ((Number) row.get("terms_days")).intValue() : in.termsDays();
		if (terms < 0 || terms > 365) {
			throw ApiException.field("termsDays", "out_of_range", "Payment terms are 0 to 365 days");
		}
		jdbc.update("""
				update supplier set name = ?, company = ?, phone = ?, email = ?, ntn = ?, address = ?, terms_days = ?,
				       notes = ?, updated_at = now(), version = version + 1 where id = ?""",
				in.name() == null ? row.get("name") : required(in.name(), "name", 200),
				in.company() == null ? row.get("company") : trim(in.company(), 200),
				in.phone() == null ? row.get("phone") : trim(in.phone(), 30),
				in.email() == null ? row.get("email") : trim(in.email(), 254),
				in.ntn() == null ? row.get("ntn") : trim(in.ntn(), 30),
				in.address() == null ? row.get("address") : trim(in.address(), 500), terms,
				in.notes() == null ? row.get("notes") : trim(in.notes(), 1000), id);
		audit.record("SUPPLIER_UPDATED", "supplier", id, null, null);
		return get(id);
	}

	@Transactional
	public SupplierView setActive(long id, boolean active) {
		if (jdbc.update("update supplier set active = ?, version = version + 1 where id = ?", active, id) == 0) {
			throw ApiException.notFound("Supplier");
		}
		return get(id);
	}

	/**
	 * One ledger line plus the stored balance, in the caller's transaction. Credit = the shop owes more (receipts);
	 * debit = owes less (payments, returns).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public BigDecimal post(long supplierId, String docType, Long docId, String docNumber, String description,
			BigDecimal debit, BigDecimal credit) {
		BigDecimal d = Amounts.money(Amounts.nz(debit));
		BigDecimal c = Amounts.money(Amounts.nz(credit));
		BigDecimal after = jdbc.queryForObject(
				"update supplier set balance = balance + ? - ?, updated_at = now() where id = ? returning balance",
				BigDecimal.class, c, d, supplierId);
		if (after == null) {
			throw ApiException.notFound("Supplier");
		}
		jdbc.update("""
				insert into supplier_ledger (shop_id, supplier_id, doc_type, doc_id, doc_number, description, debit,
				                             credit, balance_after, business_date)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", TenantContext.requireShopId(), supplierId, docType, docId,
				docNumber, description.length() > 300 ? description.substring(0, 300) : description, d, c, after,
				Date.valueOf(time.today()));
		return after;
	}

	/** Locks and returns the supplier row (404 if not in this shop). */
	@Transactional(propagation = Propagation.MANDATORY)
	public Map<String, Object> lock(long supplierId) {
		return jdbc.queryForList("select * from supplier where id = ? for update", supplierId)
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Supplier"));
	}

	@Transactional(readOnly = true)
	public PageResponse<SupplierLedgerLine> ledger(long id, int page, int size) {
		get(id);
		long total = jdbc.queryForObject("select count(*) from supplier_ledger where supplier_id = ?", Long.class, id);
		List<SupplierLedgerLine> lines = jdbc.query(
				"select * from supplier_ledger where supplier_id = ? order by id desc limit ? offset ?",
				(rs, i) -> line(rs), id, size, (long) page * size);
		return new PageResponse<>(lines, page, size, total);
	}

	@Transactional(readOnly = true)
	public SupplierStatement statement(long id, LocalDate from, LocalDate to) {
		SupplierView s = get(id);
		LocalDate end = to == null ? time.today() : to;
		LocalDate start = from == null ? end.withDayOfMonth(1) : from;
		BigDecimal opening = jdbc.queryForObject("""
				select coalesce((select balance_after from supplier_ledger where supplier_id = ? and business_date < ?
				                 order by id desc limit 1), 0)""", BigDecimal.class, id, Date.valueOf(start));
		List<SupplierLedgerLine> lines = jdbc.query(
				"select * from supplier_ledger where supplier_id = ? and business_date between ? and ? order by id",
				(rs, i) -> line(rs), id, Date.valueOf(start), Date.valueOf(end));
		BigDecimal debit = lines.stream().map(l -> new BigDecimal(l.debit())).reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal credit = lines.stream().map(l -> new BigDecimal(l.credit())).reduce(BigDecimal.ZERO, BigDecimal::add);
		return new SupplierStatement(id, s.name(), start, end, Amounts.text(opening), Amounts.text(debit),
				Amounts.text(credit), Amounts.text(opening.add(credit).subtract(debit)), lines);
	}

	public TableDoc statementDoc(SupplierStatement s, String shopName) {
		TableDoc.Builder doc = TableDoc.builder("Supplier statement")
			.heading(shopName)
			.heading(s.supplierName())
			.heading("Period: " + s.from() + " to " + s.to())
			.column("date", "Date", "DATE")
			.column("doc", "Document", "TEXT")
			.column("desc", "Details", "TEXT")
			.column("debit", "Paid / returned", "MONEY")
			.column("credit", "Bills", "MONEY")
			.column("balance", "Balance", "MONEY");
		doc.row(Arrays.asList(s.from(), "", "Opening balance", null, null, s.opening()));
		for (SupplierLedgerLine l : s.lines()) {
			doc.row(Arrays.asList(l.date(), l.docNumber(), l.description(), l.debit(), l.credit(), l.balanceAfter()));
		}
		doc.totals(Arrays.asList(null, null, "Closing balance", s.totalDebit(), s.totalCredit(), s.closing()));
		return doc.build();
	}

	private static SupplierLedgerLine line(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new SupplierLedgerLine(rs.getLong("id"), rs.getObject("business_date", LocalDate.class),
				rs.getTimestamp("at").toInstant(), rs.getString("doc_type"), (Long) rs.getObject("doc_id"),
				rs.getString("doc_number"), rs.getString("description"), Amounts.text(rs.getBigDecimal("debit")),
				Amounts.text(rs.getBigDecimal("credit")), Amounts.text(rs.getBigDecimal("balance_after")));
	}

	private static BigDecimal signed(String raw, String field) {
		if (raw == null || raw.isBlank()) {
			return BigDecimal.ZERO.setScale(2);
		}
		boolean negative = raw.trim().startsWith("-");
		BigDecimal value = Amounts.parseMoney(negative ? raw.trim().substring(1) : raw, field);
		return negative ? value.negate() : value;
	}

	static String required(String value, String field, int max) {
		if (value == null || value.isBlank()) {
			throw ApiException.field(field, "required", "This is required");
		}
		String t = value.trim();
		if (t.length() > max) {
			throw ApiException.field(field, "too_long", "Use at most " + max + " characters");
		}
		return t;
	}

	static String trim(String value, int max) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String t = value.trim();
		return t.length() > max ? t.substring(0, max) : t;
	}
}
