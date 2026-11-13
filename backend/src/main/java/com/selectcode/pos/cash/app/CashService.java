package com.selectcode.pos.cash.app;

import com.selectcode.pos.sales.app.TillService;
import com.selectcode.pos.shared.audit.AuditService;
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
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expenses (CSH-01), the daily cash summary per branch (CSH-03) and profit and loss by month and branch (CSH-04).
 *
 * <p>Daily cash: opening floats + cash sales + cash received − cash refunds − cash paid out (suppliers, used items,
 * petty cash) − cash expenses = expected closing; the counted cash of the closed tills shows any difference.
 */
@Service
public class CashService {

	public record ExpenseCategoryView(long id, String name, boolean active) {
	}

	/** @param tillSessionId paid from this till's cash (counts in its closing) */
	public record ExpenseInput(Long branchId, Long categoryId, String amount, String method, Long tillSessionId,
			LocalDate date, String paidTo, String note, Long attachmentFileId) {
	}

	public record ExpenseView(long id, String number, long branchId, String branchName, long categoryId,
			String categoryName, String amount, String method, Long tillSessionId, String paidTo, String note,
			Long attachmentFileId, String status, String voidReason, String actorName, LocalDate date, Instant at) {
	}

	public record MethodAmount(String method, String amount) {
	}

	public record DailyCash(LocalDate date, Long branchId, String branchName, String opening, String cashSales,
			String cashReceipts, String cashIn, String cashRefunds, String supplierPayments, String buybacks,
			String cashOut, String expenses, String expectedClosing, String counted, String difference,
			int openTills, List<MethodAmount> otherTakings) {
	}

	public record ProfitLossRow(String month, Long branchId, String branchName, String grossSales, String returns,
			String netSales, String costOfSales, String grossProfit, String returnCharges, String stockLoss,
			String expenses, String netProfit, String otherIncome) {
	}

	public record ProfitLoss(LocalDate from, LocalDate to, Long branchId, List<ProfitLossRow> months,
			ProfitLossRow total, List<MethodAmount> expensesByCategory) {
	}

	private final JdbcTemplate jdbc;

	private final TillService tills;

	private final BranchAccess branches;

	private final ShopConfigService numbering;

	private final AuditService audit;

	private final ShopTime time;

	CashService(JdbcTemplate jdbc, TillService tills, BranchAccess branches, ShopConfigService numbering,
			AuditService audit, ShopTime time) {
		this.jdbc = jdbc;
		this.tills = tills;
		this.branches = branches;
		this.numbering = numbering;
		this.audit = audit;
		this.time = time;
	}

	// ------------------------------------------------------------------ expense categories

	@Transactional(readOnly = true)
	public List<ExpenseCategoryView> categories() {
		return jdbc.query("select * from expense_category order by active desc, name",
				(rs, i) -> new ExpenseCategoryView(rs.getLong("id"), rs.getString("name"), rs.getBoolean("active")));
	}

	@Transactional
	public ExpenseCategoryView createCategory(String name) {
		String n = name == null ? "" : name.trim();
		if (n.isEmpty() || n.length() > 60) {
			throw ApiException.field("name", "required", "Enter a name of up to 60 characters");
		}
		try {
			long id = jdbc.queryForObject("insert into expense_category (shop_id, name) values (?, ?) returning id",
					Long.class, TenantContext.requireShopId(), n);
			return new ExpenseCategoryView(id, n, true);
		}
		catch (DuplicateKeyException e) {
			throw ApiException.conflict("duplicate_name", "This category already exists");
		}
	}

	@Transactional
	public ExpenseCategoryView updateCategory(long id, String name, Boolean active) {
		Map<String, Object> row = jdbc.queryForList("select * from expense_category where id = ?", id)
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Expense category"));
		String n = name == null ? (String) row.get("name") : name.trim();
		if (n.isEmpty() || n.length() > 60) {
			throw ApiException.field("name", "required", "Enter a name of up to 60 characters");
		}
		boolean a = active == null ? (Boolean) row.get("active") : active;
		try {
			jdbc.update("update expense_category set name = ?, active = ? where id = ?", n, a, id);
		}
		catch (DuplicateKeyException e) {
			throw ApiException.conflict("duplicate_name", "This category already exists");
		}
		return new ExpenseCategoryView(id, n, a);
	}

	// ------------------------------------------------------------------ expenses

	@Transactional
	public ExpenseView createExpense(ExpenseInput in) {
		if (in.categoryId() == null) {
			throw ApiException.field("categoryId", "required", "Choose a category");
		}
		Map<String, Object> category = jdbc.queryForList("select * from expense_category where id = ?", in.categoryId())
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Expense category"));
		if (!(Boolean) category.get("active")) {
			throw ApiException.rule("category_inactive", category.get("name") + " is switched off");
		}
		BigDecimal amount = Amounts.requireMoney(in.amount(), "amount");
		if (amount.signum() <= 0) {
			throw ApiException.field("amount", "invalid", "Enter an amount above zero");
		}
		String method = in.method() == null || in.method().isBlank() ? "CASH" : in.method().trim().toUpperCase();
		if ("ACCOUNT".equals(method) || "VOUCHER".equals(method)) {
			throw ApiException.field("method", "invalid", "Choose cash, bank or a wallet");
		}
		Integer ok = jdbc.queryForObject("select count(*) from payment_method where method_key = ? and active",
				Integer.class, method);
		if (ok == null || ok == 0) {
			throw ApiException.field("method", "invalid", "This payment method is not switched on");
		}
		Long sessionId = null;
		long branchId;
		if (in.tillSessionId() != null) {
			if (!"CASH".equals(method)) {
				throw ApiException.field("tillSessionId", "cash_only", "Only cash expenses come out of a till");
			}
			Map<String, Object> session = tills.requireOpenSession(in.tillSessionId(), null);
			sessionId = ((Number) session.get("id")).longValue();
			branchId = ((Number) session.get("branch_id")).longValue();
			if (tills.expectedCash(sessionId).compareTo(amount) < 0) {
				throw ApiException.rule("not_enough_cash", "There is not that much cash in the till");
			}
		}
		else {
			branchId = in.branchId() == null ? branches.defaultBranchId() : in.branchId();
			branches.requireActive(branchId);
		}
		LocalDate today = time.today();
		LocalDate date = in.date() == null ? today : in.date();
		if (date.isAfter(today)) {
			throw ApiException.field("date", "future", "The date cannot be in the future");
		}
		if (in.attachmentFileId() != null) {
			Integer f = jdbc.queryForObject("select count(*) from stored_file where id = ?", Integer.class,
					in.attachmentFileId());
			if (f == null || f == 0) {
				throw ApiException.notFound("Attachment");
			}
		}
		String number = numbering.nextNumber("EXPENSE", branchId);
		long id = jdbc.queryForObject("""
				insert into expense (shop_id, number, branch_id, category_id, amount, method_key, till_session_id, paid_to,
				                     note, attachment_file_id, created_by, actor_name, business_date)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, TenantContext.requireShopId(),
				number, branchId, in.categoryId(), amount, method, sessionId, trim(in.paidTo(), 200),
				trim(in.note(), 500), in.attachmentFileId(), userId(), CurrentActor.name(), Date.valueOf(date));
		audit.record("EXPENSE_ADDED", "expense", id, null,
				Map.of("number", number, "amount", amount.toPlainString(), "category", category.get("name")));
		return expense(id);
	}

	/** Voids a wrong expense (it stays in the list, marked void). */
	@Transactional
	public ExpenseView voidExpense(long id, String reason) {
		ExpenseView e = expense(id);
		if ("VOID".equals(e.status())) {
			throw ApiException.rule("expense_void", "This expense is already void");
		}
		if (reason == null || reason.isBlank()) {
			throw ApiException.field("reason", "required", "Write why the expense is void");
		}
		jdbc.update("update expense set status = 'VOID', void_reason = ? where id = ?", trim(reason, 300), id);
		audit.record("EXPENSE_VOIDED", "expense", id, null, Map.of("reason", reason.trim()));
		return expense(id);
	}

	@Transactional(readOnly = true)
	public ExpenseView expense(long id) {
		return jdbc.query("""
				select e.*, b.name as branch_name, c.name as category_name from expense e join branch b on b.id = e.branch_id
				join expense_category c on c.id = e.category_id where e.id = ?""" + branches.filter("e.branch_id"),
				(rs, i) -> view(rs), id).stream().findFirst().orElseThrow(() -> ApiException.notFound("Expense"));
	}

	@Transactional(readOnly = true)
	public PageResponse<ExpenseView> expenses(LocalDate from, LocalDate to, Long branchId, Long categoryId,
			int page, int size) {
		StringBuilder where = new StringBuilder(" where 1=1" + branches.filter("e.branch_id"));
		List<Object> args = new ArrayList<>();
		if (from != null) {
			where.append(" and e.business_date >= ?");
			args.add(Date.valueOf(from));
		}
		if (to != null) {
			where.append(" and e.business_date <= ?");
			args.add(Date.valueOf(to));
		}
		if (branchId != null) {
			where.append(" and e.branch_id = ?");
			args.add(branchId);
		}
		if (categoryId != null) {
			where.append(" and e.category_id = ?");
			args.add(categoryId);
		}
		long total = jdbc.queryForObject("select count(*) from expense e" + where, Long.class, args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(size);
		pageArgs.add((long) page * size);
		List<ExpenseView> items = jdbc.query("""
				select e.*, b.name as branch_name, c.name as category_name from expense e join branch b on b.id = e.branch_id
				join expense_category c on c.id = e.category_id""" + where
				+ " order by e.business_date desc, e.id desc limit ? offset ?", (rs, i) -> view(rs), pageArgs.toArray());
		return new PageResponse<>(items, page, size, total);
	}

	// ------------------------------------------------------------------ daily cash

	@Transactional(readOnly = true)
	public DailyCash daily(LocalDate dateIn, Long branchIdIn) {
		LocalDate date = dateIn == null ? time.today() : dateIn;
		Long branchId = branchIdIn;
		String branchName = "All branches";
		if (branchId != null) {
			branchName = branches.require(branchId).name();
		}
		String b = branchId != null ? " and branch_id = " + branchId : branches.filter("branch_id");
		String sb = branchId != null ? " and s.branch_id = " + branchId : branches.filter("s.branch_id");
		Date d = Date.valueOf(date);
		Map<String, Object> m = jdbc.queryForMap("""
				select
				  coalesce((select sum(opening_float) from till_session where business_date = ?""" + b + """
				  ), 0) as opening,
				  coalesce((select sum(amount) from sale_payment where business_date = ? and method_key = 'CASH' and kind = 'SALE'"""
				+ b + """
				  ), 0) as cash_sales,
				  coalesce((select sum(amount) from sale_payment where business_date = ? and method_key = 'CASH' and kind = 'RECEIPT'"""
				+ b + """
				  ), 0) + coalesce((select sum(unallocated) from customer_payment where business_date = ? and method_key = 'CASH'"""
				+ b + """
				  ), 0) as receipts,
				  coalesce((select -sum(amount) from sale_payment where business_date = ? and method_key = 'CASH'
				            and kind in ('REFUND', 'CANCEL')""" + b + """
				  ), 0) as refunds,
				  coalesce((select sum(c.amount) from cash_movement c join till_session s on s.id = c.till_session_id
				            where c.kind = 'IN' and s.business_date = ?""" + sb + """
				  ), 0) as cash_in,
				  coalesce((select sum(c.amount) from cash_movement c join till_session s on s.id = c.till_session_id
				            where c.kind = 'OUT' and s.business_date = ?""" + sb + """
				  ), 0) as cash_out,
				  coalesce((select sum(amount) from supplier_payment where business_date = ? and method_key = 'CASH'"""
				+ b.replace("branch_id", "coalesce(branch_id, -1)") + """
				  ), 0) as supplier_payments,
				  coalesce((select sum(price) from buyback where business_date = ? and method_key = 'CASH'""" + b + """
				  ), 0) as buybacks,
				  coalesce((select sum(amount) from expense where business_date = ? and method_key = 'CASH' and status = 'POSTED'"""
				+ b + """
				  ), 0) as expenses,
				  coalesce((select sum(counted_cash) from till_session where business_date = ? and closed_at is not null"""
				+ b + """
				  ), 0) as counted,
				  coalesce((select sum(expected_cash) from till_session where business_date = ? and closed_at is not null"""
				+ b + """
				  ), 0) as expected_closed,
				  (select count(*) from till_session where business_date = ? and closed_at is null""" + b + """
				  ) as open_tills""", d, d, d, d, d, d, d, d, d, d, d, d, d);
		BigDecimal opening = (BigDecimal) m.get("opening");
		BigDecimal sales = (BigDecimal) m.get("cash_sales");
		BigDecimal receipts = (BigDecimal) m.get("receipts");
		BigDecimal refunds = (BigDecimal) m.get("refunds");
		BigDecimal in = (BigDecimal) m.get("cash_in");
		BigDecimal out = (BigDecimal) m.get("cash_out");
		BigDecimal suppliers = (BigDecimal) m.get("supplier_payments");
		BigDecimal buybacks = (BigDecimal) m.get("buybacks");
		BigDecimal expenses = (BigDecimal) m.get("expenses");
		BigDecimal expected = opening.add(sales).add(receipts).add(in).subtract(refunds).subtract(suppliers)
			.subtract(buybacks).subtract(out).subtract(expenses);
		BigDecimal counted = (BigDecimal) m.get("counted");
		BigDecimal expectedClosed = (BigDecimal) m.get("expected_closed");
		List<MethodAmount> other = jdbc.query("select method_key, sum(amount) as amount from sale_payment "
				+ "where business_date = ? and method_key <> 'CASH'" + b + " group by method_key order by method_key",
				(rs, i) -> new MethodAmount(rs.getString("method_key"), Amounts.text(rs.getBigDecimal("amount"))), d);
		return new DailyCash(date, branchId, branchName, Amounts.text(opening), Amounts.text(sales),
				Amounts.text(receipts), Amounts.text(in), Amounts.text(refunds), Amounts.text(suppliers),
				Amounts.text(buybacks), Amounts.text(out), Amounts.text(expenses), Amounts.text(expected),
				Amounts.text(counted), Amounts.text(counted.subtract(expectedClosed)),
				((Number) m.get("open_tills")).intValue(), other);
	}

	// ------------------------------------------------------------------ profit and loss

	/** Profit and loss by month (and branch): sales − cost of goods − stock losses − expenses (CSH-04). */
	@Transactional(readOnly = true)
	public ProfitLoss profitLoss(LocalDate fromIn, LocalDate toIn, Long branchId) {
		if (!CurrentActor.hasPermission(Permissions.COST_VIEW)) {
			throw ApiException.forbidden("forbidden", "Profit needs permission to see cost");
		}
		LocalDate to = toIn == null ? time.today() : toIn;
		LocalDate from = fromIn == null ? to.withDayOfMonth(1) : fromIn;
		if (branchId != null) {
			branches.require(branchId);
		}
		List<ProfitLossRow> months = new ArrayList<>();
		BigDecimal[] sum = new BigDecimal[10];
		java.util.Arrays.fill(sum, BigDecimal.ZERO);
		for (YearMonth ym = YearMonth.from(from); !ym.isAfter(YearMonth.from(to)); ym = ym.plusMonths(1)) {
			LocalDate start = ym.atDay(1).isBefore(from) ? from : ym.atDay(1);
			LocalDate end = ym.atEndOfMonth().isAfter(to) ? to : ym.atEndOfMonth();
			BigDecimal[] v = figures(start, end, branchId);
			for (int i = 0; i < v.length; i++) {
				sum[i] = sum[i].add(v[i]);
			}
			months.add(row(ym.toString(), branchId, v));
		}
		String b = branchId != null ? " and e.branch_id = " + branchId : branches.filter("e.branch_id");
		List<MethodAmount> byCategory = jdbc.query("""
				select c.name, sum(e.amount) as amount from expense e join expense_category c on c.id = e.category_id
				where e.status = 'POSTED' and e.business_date between ? and ?""" + b
				+ " group by c.name order by sum(e.amount) desc",
				(rs, i) -> new MethodAmount(rs.getString("name"), Amounts.text(rs.getBigDecimal("amount"))),
				Date.valueOf(from), Date.valueOf(to));
		return new ProfitLoss(from, to, branchId, months, row("Total", branchId, sum), byCategory);
	}

	/**
	 * [grossSales, returns, netSales, costOfSales, grossProfit, returnCharges, stockLoss, expenses, netProfit,
	 * otherIncome], all without sales tax. Other income is installment markup and late fees.
	 */
	private BigDecimal[] figures(LocalDate from, LocalDate to, Long branchId) {
		String sb = branchId != null ? " and s.branch_id = " + branchId : branches.filter("s.branch_id");
		String rb = branchId != null ? " and r.branch_id = " + branchId : branches.filter("r.branch_id");
		String ab = branchId != null ? " and a.branch_id = " + branchId : branches.filter("a.branch_id");
		String eb = branchId != null ? " and e.branch_id = " + branchId : branches.filter("e.branch_id");
		String mb = branchId != null ? " and m.branch_id = " + branchId : branches.filter("m.branch_id");
		Date f = Date.valueOf(from);
		Date t = Date.valueOf(to);
		Map<String, Object> m = jdbc.queryForMap("""
				select
				  coalesce((select sum(l.total - l.tax) from sale_line l join sale s on s.id = l.sale_id
				            where s.status = 'POSTED' and s.business_date between ? and ?""" + sb + """
				  ), 0) as gross,
				  coalesce((select sum(s.cost_total) from sale s where s.status = 'POSTED' and s.business_date between ? and ?"""
				+ sb + """
				  ), 0) as cost,
				  coalesce((select sum(rl.value * (l.total - l.tax) / nullif(l.total, 0)) from sale_return_line rl
				            join sale_line l on l.id = rl.sale_line_id join sale_return r on r.id = rl.return_id
				            where r.business_date between ? and ?""" + rb + """
				  ), 0) as returns,
				  coalesce((select sum(r.cost_restocked) from sale_return r where r.business_date between ? and ?""" + rb
				+ """
				  ), 0) as cost_back,
				  coalesce((select sum(r.return_charge) from sale_return r where r.business_date between ? and ?""" + rb
				+ """
				  ), 0) as charges,
				  coalesce((select sum(a.value_out) - sum(a.value_in) from adjustment a where a.reason <> 'OPENING'
				            and a.business_date between ? and ?""" + ab + """
				  ), 0) as adjust_loss,
				  coalesce((select -sum(m.qty * m.unit_cost) from stock_movement m where m.move_type = 'COUNT'
				            and m.at >= ? and m.at < ?""" + mb + """
				  ), 0) as count_loss,
				  coalesce((select sum(e.amount) from expense e where e.status = 'POSTED' and e.business_date between ? and ?"""
				+ eb + """
				  ), 0) as expenses,
				  coalesce((select sum(cl.debit - cl.credit) from customer_ledger cl
				            join installment_plan p on p.id = cl.doc_id join sale s on s.id = p.sale_id
				            where cl.doc_type in ('INSTALLMENT_MARKUP', 'LATE_FEE') and cl.business_date between ? and ?"""
				+ sb + """
				  ), 0) as other_income""", f, t, f, t, f, t, f, t, f, t, f, t,
				java.sql.Timestamp.from(ShopTime.startOf(from)), java.sql.Timestamp.from(ShopTime.startOf(to.plusDays(1))),
				f, t, f, t);
		BigDecimal gross = Amounts.money((BigDecimal) m.get("gross"));
		BigDecimal returns = Amounts.money((BigDecimal) m.get("returns"));
		BigDecimal net = gross.subtract(returns);
		BigDecimal cost = Amounts.money(((BigDecimal) m.get("cost")).subtract((BigDecimal) m.get("cost_back")));
		BigDecimal grossProfit = net.subtract(cost);
		BigDecimal charges = Amounts.money((BigDecimal) m.get("charges"));
		BigDecimal loss = Amounts.money(((BigDecimal) m.get("adjust_loss")).add((BigDecimal) m.get("count_loss")));
		BigDecimal expenses = Amounts.money((BigDecimal) m.get("expenses"));
		BigDecimal other = Amounts.money((BigDecimal) m.get("other_income"));
		BigDecimal netProfit = grossProfit.add(charges).add(other).subtract(loss).subtract(expenses);
		return new BigDecimal[] { gross, returns, net, cost, grossProfit, charges, loss, expenses, netProfit, other };
	}

	private ProfitLossRow row(String label, Long branchId, BigDecimal[] v) {
		String name = branchId == null ? "All branches"
				: jdbc.queryForObject("select name from branch where id = ?", String.class, branchId);
		return new ProfitLossRow(label, branchId, name, Amounts.text(v[0]), Amounts.text(v[1]), Amounts.text(v[2]),
				Amounts.text(v[3]), Amounts.text(v[4]), Amounts.text(v[5]), Amounts.text(v[6]), Amounts.text(v[7]),
				Amounts.text(v[8]), Amounts.text(v[9]));
	}

	private static ExpenseView view(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new ExpenseView(rs.getLong("id"), rs.getString("number"), rs.getLong("branch_id"),
				rs.getString("branch_name"), rs.getLong("category_id"), rs.getString("category_name"),
				Amounts.text(rs.getBigDecimal("amount")), rs.getString("method_key"),
				(Long) rs.getObject("till_session_id"), rs.getString("paid_to"), rs.getString("note"),
				(Long) rs.getObject("attachment_file_id"), rs.getString("status"), rs.getString("void_reason"),
				rs.getString("actor_name"), rs.getObject("business_date", LocalDate.class),
				rs.getTimestamp("at").toInstant());
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
