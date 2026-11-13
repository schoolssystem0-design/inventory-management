package com.selectcode.pos.customers.app;

import com.selectcode.pos.shared.audit.AuditService;
import com.selectcode.pos.shared.docs.TableDoc;
import com.selectcode.pos.shared.money.Amounts;
import com.selectcode.pos.shared.security.CurrentActor;
import com.selectcode.pos.shared.security.Permissions;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customers (CUS-01, CUS-02): record with type and credit limit, ledger and statement. CNIC and other personal
 * details are masked for users without {@code customers.pii} when the shop's masking setting is on (NFR-09).
 */
@Service
public class CustomerService {

	public record CustomerInput(String name, String phone, String cnic, String email, String address, String kind,
			String creditLimit, String openingBalance, String notes, Long version) {
	}

	public record CustomerView(long id, String code, String name, String phone, String cnic, String email,
			String address, String kind, String creditLimit, String balance, String openingBalance, String notes,
			boolean active, Instant createdAt, long version) {
	}

	public record CustomerListItem(long id, String code, String name, String phone, String kind, String balance,
			String creditLimit, boolean overLimit, boolean active) {
	}

	public record LedgerLine(long id, LocalDate date, Instant at, String docType, Long docId, String docNumber,
			String description, String debit, String credit, String balanceAfter) {
	}

	public record Statement(long customerId, String customerName, String phone, LocalDate from, LocalDate to,
			String opening, String totalDebit, String totalCredit, String closing, List<LedgerLine> lines) {
	}

	static final Set<String> KINDS = Set.of("RETAIL", "WHOLESALE", "CONTRACTOR", "WORKSHOP");

	private final JdbcTemplate jdbc;

	private final CustomerLedger ledger;

	private final ShopConfigService numbering;

	private final AuditService audit;

	private final ShopTime time;

	private final com.selectcode.pos.notifications.app.Notifier notifier;

	CustomerService(JdbcTemplate jdbc, CustomerLedger ledger, ShopConfigService numbering, AuditService audit,
			ShopTime time, com.selectcode.pos.notifications.app.Notifier notifier) {
		this.notifier = notifier;
		this.jdbc = jdbc;
		this.ledger = ledger;
		this.numbering = numbering;
		this.audit = audit;
		this.time = time;
	}

	@Transactional(readOnly = true)
	public PageResponse<CustomerListItem> list(String q, String kind, Boolean withBalance, Boolean active, int page,
			int size) {
		StringBuilder where = new StringBuilder(" where 1=1");
		List<Object> args = new ArrayList<>();
		where.append(active == null || active ? " and c.active" : " and not c.active");
		if (q != null && !q.isBlank()) {
			where.append(" and (lower(c.name) like ? or c.phone like ? or upper(c.code) = ?)");
			args.add("%" + q.trim().toLowerCase() + "%");
			args.add("%" + q.trim().replaceAll("[^0-9+]", "") + "%");
			args.add(q.trim().toUpperCase());
		}
		if (kind != null && !kind.isBlank()) {
			where.append(" and c.kind = ?");
			args.add(kind.trim().toUpperCase());
		}
		if (Boolean.TRUE.equals(withBalance)) {
			where.append(" and c.balance > 0");
		}
		long total = jdbc.queryForObject("select count(*) from customer c" + where, Long.class, args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(size);
		pageArgs.add((long) page * size);
		boolean pii = showPii();
		List<CustomerListItem> items = jdbc.query("select c.* from customer c" + where
				+ " order by c.name limit ? offset ?",
				(rs, i) -> new CustomerListItem(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
						pii ? rs.getString("phone") : maskPhone(rs.getString("phone")), rs.getString("kind"),
						Amounts.text(rs.getBigDecimal("balance")), Amounts.text(rs.getBigDecimal("credit_limit")),
						rs.getBigDecimal("balance").compareTo(rs.getBigDecimal("credit_limit")) > 0,
						rs.getBoolean("active")),
				pageArgs.toArray());
		return new PageResponse<>(items, page, size, total);
	}

	@Transactional(readOnly = true)
	public CustomerView get(long id) {
		boolean pii = showPii();
		return jdbc.query("select * from customer where id = ?", (rs, i) -> new CustomerView(rs.getLong("id"),
				rs.getString("code"), rs.getString("name"), pii ? rs.getString("phone") : maskPhone(rs.getString("phone")),
				pii ? rs.getString("cnic") : maskCnic(rs.getString("cnic")), pii ? rs.getString("email") : null,
				rs.getString("address"), rs.getString("kind"), Amounts.text(rs.getBigDecimal("credit_limit")),
				Amounts.text(rs.getBigDecimal("balance")), Amounts.text(rs.getBigDecimal("opening_balance")),
				rs.getString("notes"), rs.getBoolean("active"), rs.getTimestamp("created_at").toInstant(),
				rs.getLong("version")), id).stream().findFirst().orElseThrow(() -> ApiException.notFound("Customer"));
	}

	/** The customer with this phone number, if any (POS quick lookup, POS-05). */
	@Transactional(readOnly = true)
	public java.util.Optional<CustomerView> byPhone(String phone) {
		String p = normalisePhone(phone);
		if (p == null) {
			return java.util.Optional.empty();
		}
		return jdbc.queryForList("select id from customer where phone = ?", Long.class, p).stream().findFirst()
			.map(this::get);
	}

	@Transactional
	public CustomerView create(CustomerInput in) {
		String name = required(in.name(), "name", 200);
		String phone = normalisePhone(in.phone());
		String kind = kind(in.kind());
		BigDecimal limit = Amounts.nz(Amounts.parseMoney(in.creditLimit(), "creditLimit"));
		BigDecimal opening = parseSigned(in.openingBalance(), "openingBalance");
		String code = numbering.nextNumber("CUSTOMER", null);
		long id;
		try {
			id = jdbc.queryForObject("""
					insert into customer (shop_id, code, name, phone, cnic, email, address, kind, credit_limit,
					                      opening_balance, notes)
					values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, TenantContext.requireShopId(),
					code, name, phone, cnic(in.cnic()), trim(in.email(), 254), trim(in.address(), 500), kind, limit,
					opening, trim(in.notes(), 1000));
		}
		catch (DuplicateKeyException e) {
			throw ApiException.conflict("duplicate_phone", "A customer with phone " + phone + " already exists");
		}
		if (opening.signum() != 0) {
			ledger.post(id, "OPENING", null, null, "Opening balance", opening.signum() > 0 ? opening : null,
					opening.signum() < 0 ? opening.negate() : null);
		}
		audit.record("CUSTOMER_CREATED", "customer", id, null, Map.of("name", name));
		return get(id);
	}

	@Transactional
	public CustomerView update(long id, CustomerInput in) {
		Map<String, Object> row = jdbc.queryForList("select * from customer where id = ? for update", id)
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Customer"));
		if (in.version() != null && in.version() != ((Number) row.get("version")).longValue()) {
			throw ApiException.conflict("stale_version", "Someone else changed this customer. Reload and try again.");
		}
		String name = in.name() == null ? (String) row.get("name") : required(in.name(), "name", 200);
		String phone = in.phone() == null ? (String) row.get("phone") : normalisePhone(in.phone());
		String kind = in.kind() == null ? (String) row.get("kind") : kind(in.kind());
		BigDecimal limit = in.creditLimit() == null ? (BigDecimal) row.get("credit_limit")
				: Amounts.nz(Amounts.parseMoney(in.creditLimit(), "creditLimit"));
		String cnic = in.cnic() == null ? (String) row.get("cnic") : cnic(in.cnic());
		if (in.cnic() != null && !showPii() && in.cnic().contains("*")) {
			cnic = (String) row.get("cnic");
		}
		try {
			jdbc.update("""
					update customer set name = ?, phone = ?, cnic = ?, email = ?, address = ?, kind = ?, credit_limit = ?,
					       notes = ?, updated_at = now(), version = version + 1 where id = ?""", name, phone, cnic,
					in.email() == null ? row.get("email") : trim(in.email(), 254),
					in.address() == null ? row.get("address") : trim(in.address(), 500), kind, limit,
					in.notes() == null ? row.get("notes") : trim(in.notes(), 1000), id);
		}
		catch (DuplicateKeyException e) {
			throw ApiException.conflict("duplicate_phone", "A customer with phone " + phone + " already exists");
		}
		Map<String, Object> before = new LinkedHashMap<>();
		before.put("creditLimit", Amounts.text((BigDecimal) row.get("credit_limit")));
		before.put("name", row.get("name"));
		audit.record("CUSTOMER_UPDATED", "customer", id, before,
				Map.of("creditLimit", Amounts.text(limit), "name", name));
		return get(id);
	}

	@Transactional
	public CustomerView setActive(long id, boolean active) {
		int n = jdbc.update("update customer set active = ?, updated_at = now(), version = version + 1 where id = ?",
				active, id);
		if (n == 0) {
			throw ApiException.notFound("Customer");
		}
		audit.record(active ? "CUSTOMER_ACTIVATED" : "CUSTOMER_DEACTIVATED", "customer", id, null, null);
		return get(id);
	}

	@Transactional(readOnly = true)
	public PageResponse<LedgerLine> ledger(long id, int page, int size) {
		get(id);
		long total = jdbc.queryForObject("select count(*) from customer_ledger where customer_id = ?", Long.class, id);
		List<LedgerLine> lines = jdbc.query("""
				select * from customer_ledger where customer_id = ? order by id desc limit ? offset ?""",
				(rs, i) -> line(rs), id, size, (long) page * size);
		return new PageResponse<>(lines, page, size, total);
	}

	/** Statement for a period (TC-CUS-03): opening, lines and closing match the ledger. */
	@Transactional(readOnly = true)
	public Statement statement(long id, LocalDate from, LocalDate to) {
		CustomerView c = get(id);
		LocalDate end = to == null ? time.today() : to;
		LocalDate start = from == null ? end.withDayOfMonth(1) : from;
		BigDecimal opening = jdbc.queryForObject("""
				select coalesce((select balance_after from customer_ledger where customer_id = ? and business_date < ?
				                 order by id desc limit 1), 0)""", BigDecimal.class, id, Date.valueOf(start));
		List<LedgerLine> lines = jdbc.query("""
				select * from customer_ledger where customer_id = ? and business_date between ? and ? order by id""",
				(rs, i) -> line(rs), id, Date.valueOf(start), Date.valueOf(end));
		BigDecimal debit = lines.stream().map(l -> new BigDecimal(l.debit())).reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal credit = lines.stream().map(l -> new BigDecimal(l.credit())).reduce(BigDecimal.ZERO, BigDecimal::add);
		return new Statement(id, c.name(), c.phone(), start, end, Amounts.text(opening), Amounts.text(debit),
				Amounts.text(credit), Amounts.text(opening.add(debit).subtract(credit)), lines);
	}

	/** Emails the statement (CUS-02, NTF-05) to the given address or the customer's own. */
	@Transactional
	public boolean emailStatement(long id, LocalDate from, LocalDate to, String email, String shopName) {
		Statement s = statement(id, from, to);
		String to_ = email != null && !email.isBlank() ? email.trim()
				: jdbc.queryForObject("select email from customer where id = ?", String.class, id);
		if (to_ == null || !to_.contains("@")) {
			throw ApiException.field("email", "required", "Enter the email address");
		}
		StringBuilder body = new StringBuilder(shopName + "\nStatement for " + s.customerName() + ", " + s.from()
				+ " to " + s.to() + "\n\nOpening balance: Rs " + s.opening() + "\n");
		for (LedgerLine l : s.lines()) {
			body.append(l.date()).append("  ").append(l.description()).append("  ")
				.append(new BigDecimal(l.debit()).signum() > 0 ? "+" + l.debit() : "-" + l.credit())
				.append("  = ").append(l.balanceAfter()).append('\n');
		}
		body.append("\nClosing balance: Rs ").append(s.closing());
		return notifier.email(to_, "statement", "Statement from " + shopName, body.toString(), null);
	}

	/** The statement as a printable table (PDF / WhatsApp). */
	public TableDoc statementDoc(Statement s, String shopName) {
		TableDoc.Builder doc = TableDoc.builder("Customer statement")
			.heading(shopName)
			.heading(s.customerName() + (s.phone() == null ? "" : " · " + s.phone()))
			.heading("Period: " + s.from() + " to " + s.to())
			.column("date", "Date", "DATE")
			.column("doc", "Document", "TEXT")
			.column("desc", "Details", "TEXT")
			.column("debit", "Debit", "MONEY")
			.column("credit", "Credit", "MONEY")
			.column("balance", "Balance", "MONEY");
		doc.row(java.util.Arrays.asList(s.from(), "", "Opening balance", null, null, s.opening()));
		for (LedgerLine l : s.lines()) {
			doc.row(java.util.Arrays.asList(l.date(), l.docNumber(), l.description(), zeroToNull(l.debit()),
					zeroToNull(l.credit()), l.balanceAfter()));
		}
		doc.totals(java.util.Arrays.asList(null, null, "Closing balance", s.totalDebit(), s.totalCredit(), s.closing()));
		return doc.build();
	}

	// ------------------------------------------------------------------ helpers

	/** Full personal details: with {@code customers.pii}, or for everyone when the shop does not mask them. */
	boolean showPii() {
		return CurrentActor.hasPermission(Permissions.CUSTOMERS_PII) || !Boolean.TRUE.equals(jdbc.queryForObject(
				"select mask_customer_pii from shop_settings where shop_id = ?", Boolean.class,
				TenantContext.requireShopId()));
	}

	public static String maskCnic(String cnic) {
		if (cnic == null || cnic.length() < 4) {
			return cnic;
		}
		return cnic.substring(0, Math.min(5, cnic.length())) + "-*******-" + cnic.substring(cnic.length() - 1);
	}

	public static String maskPhone(String phone) {
		if (phone == null || phone.length() < 7) {
			return phone;
		}
		return phone.substring(0, 4) + "***" + phone.substring(phone.length() - 4);
	}

	/** Keeps digits and a leading + ("0300-1234567" → "03001234567"). */
	public static String normalisePhone(String phone) {
		if (phone == null || phone.isBlank()) {
			return null;
		}
		String p = phone.trim().replaceAll("[^0-9+]", "");
		if (p.length() < 7 || p.length() > 15) {
			throw ApiException.field("phone", "invalid", "Enter a phone number like 0300-1234567");
		}
		return p;
	}

	private static String cnic(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String digits = raw.replaceAll("[^0-9]", "");
		if (digits.length() != 13) {
			throw ApiException.field("cnic", "invalid", "CNIC has 13 digits, like 35202-1234567-1");
		}
		return digits.substring(0, 5) + "-" + digits.substring(5, 12) + "-" + digits.substring(12);
	}

	private static String kind(String raw) {
		String k = raw == null || raw.isBlank() ? "RETAIL" : raw.trim().toUpperCase();
		if (!KINDS.contains(k)) {
			throw ApiException.field("kind", "invalid", "Customer type is retail, wholesale, contractor or workshop");
		}
		return k;
	}

	private static BigDecimal parseSigned(String raw, String field) {
		if (raw == null || raw.isBlank()) {
			return BigDecimal.ZERO.setScale(2);
		}
		boolean negative = raw.trim().startsWith("-");
		BigDecimal value = Amounts.parseMoney(negative ? raw.trim().substring(1) : raw, field);
		return negative ? value.negate() : value;
	}

	private static LedgerLine line(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new LedgerLine(rs.getLong("id"), rs.getObject("business_date", LocalDate.class),
				rs.getTimestamp("at").toInstant(), rs.getString("doc_type"), (Long) rs.getObject("doc_id"),
				rs.getString("doc_number"), rs.getString("description"), Amounts.text(rs.getBigDecimal("debit")),
				Amounts.text(rs.getBigDecimal("credit")), Amounts.text(rs.getBigDecimal("balance_after")));
	}

	private static String zeroToNull(String amount) {
		return amount == null || new BigDecimal(amount).signum() == 0 ? null : amount;
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
