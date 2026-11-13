package com.selectcode.pos.sales.app;

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
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Till sessions (POS-07, POS-08, SUP-07): open with a float, cash in and out with a reason, close with the counted
 * cash; the difference (short / over) is recorded and the Z-report shows everything the till took. Each till's cash is
 * kept separately, so three tills can run and close independently.
 */
@Service
public class TillService {

	public record OpenTill(Long tillId, String openingFloat) {
	}

	public record CashMovementInput(String kind, String amount, String reason) {
	}

	/** @param countedOther counted card slips / wallet totals by method, for the record */
	public record CloseTill(String countedCash, Map<String, String> countedOther, String note) {
	}

	public record SessionView(long id, long tillId, String tillName, long branchId, String branchName, Long userId,
			String userName, Instant openedAt, String openingFloat, Instant closedAt, String expectedCash,
			String countedCash, String difference, String note, boolean open) {
	}

	public record MethodTotal(String method, String amount) {
	}

	public record ZReport(SessionView session, int sales, int cancelled, int returns, String grossSales,
			String discounts, String netSales, String tax, String returnsValue, List<MethodTotal> payments,
			String cashSales, String cashReceipts, String cashRefunds, String cashIn, String cashOut,
			String cashExpenses, String cashBuybacks, String openingFloat, String expectedCash, String countedCash,
			String difference) {
	}

	public record CashMovementView(long id, String kind, String amount, String reason, String actorName, Instant at) {
	}

	private final JdbcTemplate jdbc;

	private final BranchAccess branches;

	private final AuditService audit;

	private final ShopTime time;

	private final JsonMapper json;

	TillService(JdbcTemplate jdbc, BranchAccess branches, AuditService audit, ShopTime time, JsonMapper json) {
		this.jdbc = jdbc;
		this.branches = branches;
		this.audit = audit;
		this.time = time;
		this.json = json;
	}

	@Transactional
	public SessionView open(OpenTill in) {
		if (in.tillId() == null) {
			throw ApiException.field("tillId", "required", "Choose a till");
		}
		Map<String, Object> till = jdbc.queryForList("select * from till where id = ?", in.tillId())
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Till"));
		long branchId = ((Number) till.get("branch_id")).longValue();
		branches.requireActive(branchId);
		if (!(Boolean) till.get("active")) {
			throw ApiException.rule("till_inactive", till.get("name") + " is switched off");
		}
		BigDecimal opening = Amounts.nz(Amounts.parseMoney(in.openingFloat(), "openingFloat"));
		try {
			long id = jdbc.queryForObject("""
					insert into till_session (shop_id, till_id, branch_id, user_id, actor_name, opening_float, business_date)
					values (?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, TenantContext.requireShopId(), in.tillId(),
					branchId, userId(), CurrentActor.name(), opening, Date.valueOf(time.today()));
			audit.record("TILL_OPENED", "till_session", id, null, Map.of("till", till.get("name"), "float",
					opening.toPlainString()));
			return get(id);
		}
		catch (DuplicateKeyException e) {
			throw ApiException.conflict("till_open", till.get("name") + " is already open. Close it first.");
		}
	}

	/** The current user's open sessions (normally one). */
	@Transactional(readOnly = true)
	public List<SessionView> mine() {
		return jdbc.queryForList("select id from till_session where closed_at is null and user_id = ? order by id",
				Long.class, userId()).stream().map(this::get).toList();
	}

	@Transactional(readOnly = true)
	public PageResponse<SessionView> list(Long branchId, Boolean open, LocalDate from, LocalDate to, int page,
			int size) {
		StringBuilder where = new StringBuilder(" where 1=1" + branches.filter("branch_id"));
		List<Object> args = new ArrayList<>();
		if (branchId != null) {
			where.append(" and branch_id = ?");
			args.add(branchId);
		}
		if (open != null) {
			where.append(open ? " and closed_at is null" : " and closed_at is not null");
		}
		if (from != null) {
			where.append(" and business_date >= ?");
			args.add(Date.valueOf(from));
		}
		if (to != null) {
			where.append(" and business_date <= ?");
			args.add(Date.valueOf(to));
		}
		long total = jdbc.queryForObject("select count(*) from till_session" + where, Long.class, args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(size);
		pageArgs.add((long) page * size);
		List<Long> ids = jdbc.queryForList("select id from till_session" + where + " order by id desc limit ? offset ?",
				Long.class, pageArgs.toArray());
		return new PageResponse<>(ids.stream().map(this::get).toList(), page, size, total);
	}

	@Transactional(readOnly = true)
	public SessionView get(long id) {
		boolean seeCounts = canSeeCounts();
		return jdbc.query("""
				select s.*, t.name as till_name, b.name as branch_name from till_session s join till t on t.id = s.till_id
				join branch b on b.id = s.branch_id where s.id = ?""" + branches.filter("s.branch_id"), (rs, i) -> {
			boolean own = rs.getObject("user_id") != null && rs.getLong("user_id") == (userId() == null ? -1 : userId());
			boolean show = seeCounts || own;
			return new SessionView(rs.getLong("id"), rs.getLong("till_id"), rs.getString("till_name"),
					rs.getLong("branch_id"), rs.getString("branch_name"), (Long) rs.getObject("user_id"),
					rs.getString("actor_name"), rs.getTimestamp("opened_at").toInstant(),
					Amounts.text(rs.getBigDecimal("opening_float")),
					rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toInstant(),
					show ? Amounts.text(rs.getBigDecimal("expected_cash")) : null,
					show ? Amounts.text(rs.getBigDecimal("counted_cash")) : null,
					show ? Amounts.text(rs.getBigDecimal("difference")) : null, rs.getString("note"),
					rs.getTimestamp("closed_at") == null);
		}, id).stream().findFirst().orElseThrow(() -> ApiException.notFound("Till session"));
	}

	@Transactional
	public CashMovementView cashMovement(long sessionId, CashMovementInput in) {
		Map<String, Object> session = lockOpen(sessionId);
		requireOwnerOrManager(session);
		String kind = in.kind() == null ? "" : in.kind().trim().toUpperCase();
		if (!List.of("IN", "OUT").contains(kind)) {
			throw ApiException.field("kind", "invalid", "Choose cash in or cash out");
		}
		BigDecimal amount = Amounts.requireMoney(in.amount(), "amount");
		if (amount.signum() <= 0) {
			throw ApiException.field("amount", "invalid", "Enter an amount above zero");
		}
		if (in.reason() == null || in.reason().isBlank()) {
			throw ApiException.field("reason", "required", "Write why the cash is taken or added");
		}
		if ("OUT".equals(kind) && expectedCash(sessionId).compareTo(amount) < 0) {
			throw ApiException.rule("not_enough_cash", "There is not that much cash in the till");
		}
		long id = jdbc.queryForObject("""
				insert into cash_movement (shop_id, till_session_id, kind, amount, reason, user_id, actor_name)
				values (?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, TenantContext.requireShopId(), sessionId, kind,
				amount, in.reason().trim().length() > 300 ? in.reason().trim().substring(0, 300) : in.reason().trim(),
				userId(), CurrentActor.name());
		audit.record("CASH_" + kind, "till_session", sessionId, null,
				Map.of("amount", amount.toPlainString(), "reason", in.reason().trim()));
		return new CashMovementView(id, kind, Amounts.text(amount), in.reason().trim(), CurrentActor.name(),
				time.now());
	}

	@Transactional(readOnly = true)
	public List<CashMovementView> cashMovements(long sessionId) {
		get(sessionId);
		return jdbc.query("select * from cash_movement where till_session_id = ? order by id",
				(rs, i) -> new CashMovementView(rs.getLong("id"), rs.getString("kind"),
						Amounts.text(rs.getBigDecimal("amount")), rs.getString("reason"), rs.getString("actor_name"),
						rs.getTimestamp("at").toInstant()),
				sessionId);
	}

	/** Closes the till with the counted cash; the difference (short negative, over positive) is kept. */
	@Transactional
	public ZReport close(long sessionId, CloseTill in) {
		Map<String, Object> session = lockOpen(sessionId);
		requireOwnerOrManager(session);
		BigDecimal counted = Amounts.requireMoney(in.countedCash(), "countedCash");
		BigDecimal expected = expectedCash(sessionId);
		BigDecimal difference = counted.subtract(expected);
		Map<String, String> other = new LinkedHashMap<>();
		if (in.countedOther() != null) {
			in.countedOther().forEach((k, v) -> other.put(k, Amounts.text(Amounts.parseMoney(v, "countedOther." + k))));
		}
		jdbc.update("""
				update till_session set closed_at = now(), closed_by = ?, counted_cash = ?, expected_cash = ?,
				       difference = ?, counted_other = ?::jsonb, note = ? where id = ?""", userId(), counted, expected,
				difference, json.writeValueAsString(other),
				in.note() == null ? null : in.note().trim().substring(0, Math.min(500, in.note().trim().length())),
				sessionId);
		audit.record("TILL_CLOSED", "till_session", sessionId, null, Map.of("counted", counted.toPlainString(),
				"expected", expected.toPlainString(), "difference", difference.toPlainString()));
		return zReport(sessionId);
	}

	/** Z-report (POS-07): what the till took, by method, and the cash reconciliation. */
	@Transactional(readOnly = true)
	public ZReport zReport(long sessionId) {
		SessionView session = get(sessionId);
		boolean show = canSeeCounts() || (session.userId() != null && session.userId().equals(userId()));
		Map<String, Object> s = jdbc.queryForMap("""
				select count(*) filter (where status = 'POSTED') as sales, count(*) filter (where status = 'CANCELLED') as cancelled,
				       coalesce(sum(subtotal) filter (where status = 'POSTED'), 0) as gross,
				       coalesce(sum(line_discount + bill_discount) filter (where status = 'POSTED'), 0) as discounts,
				       coalesce(sum(total) filter (where status = 'POSTED'), 0) as net,
				       coalesce(sum(tax) filter (where status = 'POSTED'), 0) as tax
				from sale where till_session_id = ?""", sessionId);
		Map<String, Object> r = jdbc.queryForMap("""
				select count(*) as n, coalesce(sum(value), 0) as value from sale_return where till_session_id = ?""",
				sessionId);
		List<MethodTotal> methods = jdbc.query("""
				select method_key, sum(amount) as amount from sale_payment where till_session_id = ? group by method_key
				order by method_key""", (rs, i) -> new MethodTotal(rs.getString("method_key"),
				Amounts.text(rs.getBigDecimal("amount"))), sessionId);
		Cash cash = cash(sessionId);
		BigDecimal expected = cash.expected();
		Map<String, Object> row = jdbc.queryForMap("select counted_cash, difference from till_session where id = ?",
				sessionId);
		return new ZReport(session, ((Number) s.get("sales")).intValue(), ((Number) s.get("cancelled")).intValue(),
				((Number) r.get("n")).intValue(), Amounts.text((BigDecimal) s.get("gross")),
				Amounts.text((BigDecimal) s.get("discounts")), Amounts.text((BigDecimal) s.get("net")),
				Amounts.text((BigDecimal) s.get("tax")), Amounts.text((BigDecimal) r.get("value")), methods,
				Amounts.text(cash.sales()), Amounts.text(cash.receipts()), Amounts.text(cash.refunds()),
				Amounts.text(cash.in()), Amounts.text(cash.out()), Amounts.text(cash.expenses()),
				Amounts.text(cash.buybacks()), Amounts.text(cash.opening()), show ? Amounts.text(expected) : null,
				show ? Amounts.text((BigDecimal) row.get("counted_cash")) : null,
				show ? Amounts.text((BigDecimal) row.get("difference")) : null);
	}

	record Cash(BigDecimal opening, BigDecimal sales, BigDecimal receipts, BigDecimal refunds, BigDecimal in,
			BigDecimal out, BigDecimal expenses, BigDecimal buybacks) {

		BigDecimal expected() {
			return opening.add(sales).add(receipts).subtract(refunds).add(in).subtract(out).subtract(expenses)
				.subtract(buybacks);
		}
	}

	/** Cash that should be in the drawer: float + cash taken − cash paid out. */
	public BigDecimal expectedCash(long sessionId) {
		return cash(sessionId).expected();
	}

	Cash cash(long sessionId) {
		Map<String, Object> m = jdbc.queryForMap("""
				select s.opening_float,
				  coalesce((select sum(amount) from sale_payment p where p.till_session_id = s.id and p.method_key = 'CASH'
				            and p.kind = 'SALE'), 0) as sales,
				  coalesce((select sum(amount) from sale_payment p where p.till_session_id = s.id and p.method_key = 'CASH'
				            and p.kind = 'RECEIPT'), 0)
				  + coalesce((select sum(unallocated) from customer_payment c where c.till_session_id = s.id
				              and c.method_key = 'CASH'), 0) as receipts,
				  coalesce((select -sum(amount) from sale_payment p where p.till_session_id = s.id and p.method_key = 'CASH'
				            and p.kind in ('REFUND', 'CANCEL')), 0) as refunds,
				  coalesce((select sum(amount) from cash_movement c where c.till_session_id = s.id and c.kind = 'IN'), 0) as cash_in,
				  coalesce((select sum(amount) from cash_movement c where c.till_session_id = s.id and c.kind = 'OUT'), 0) as cash_out,
				  coalesce((select sum(amount) from expense e where e.till_session_id = s.id and e.status = 'POSTED'), 0) as expenses,
				  coalesce((select sum(price) from buyback b where b.till_session_id = s.id and b.method_key = 'CASH'), 0) as buybacks
				from till_session s where s.id = ?""", sessionId);
		return new Cash((BigDecimal) m.get("opening_float"), (BigDecimal) m.get("sales"), (BigDecimal) m.get("receipts"),
				(BigDecimal) m.get("refunds"), (BigDecimal) m.get("cash_in"), (BigDecimal) m.get("cash_out"),
				(BigDecimal) m.get("expenses"), (BigDecimal) m.get("buybacks"));
	}

	/**
	 * The open session to take money into or pay money out of: the given one, or the current user's open session at
	 * the branch. Must belong to the current user unless they may manage tills of others.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Map<String, Object> requireOpenSession(Long sessionId, Long branchId) {
		Map<String, Object> session;
		if (sessionId != null) {
			session = jdbc.queryForList("select * from till_session where id = ? for share", sessionId)
				.stream()
				.findFirst()
				.orElseThrow(() -> ApiException.notFound("Till session"));
		}
		else {
			session = jdbc.queryForList("""
					select * from till_session where closed_at is null and user_id = ? and (cast(? as bigint) is null or branch_id = ?)
					order by id desc limit 1 for share""", userId(), branchId, branchId).stream().findFirst()
				.orElseThrow(() -> ApiException.rule("till_closed", "Open your till first"));
		}
		if (session.get("closed_at") != null) {
			throw ApiException.rule("till_closed", "This till session is closed. Open the till again.");
		}
		if (!branches.allowed(((Number) session.get("branch_id")).longValue())) {
			throw ApiException.notFound("Till session");
		}
		Long owner = session.get("user_id") == null ? null : ((Number) session.get("user_id")).longValue();
		if (owner != null && !owner.equals(userId()) && !CurrentActor.hasPermission(Permissions.APPROVALS_GRANT)) {
			throw ApiException.forbidden("not_your_till", "This till is open for " + session.get("actor_name"));
		}
		return session;
	}

	private Map<String, Object> lockOpen(long sessionId) {
		Map<String, Object> session = jdbc.queryForList("select * from till_session where id = ? for update", sessionId)
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Till session"));
		if (!branches.allowed(((Number) session.get("branch_id")).longValue())) {
			throw ApiException.notFound("Till session");
		}
		if (session.get("closed_at") != null) {
			throw ApiException.rule("till_closed", "This till session is already closed");
		}
		return session;
	}

	private void requireOwnerOrManager(Map<String, Object> session) {
		Long owner = session.get("user_id") == null ? null : ((Number) session.get("user_id")).longValue();
		if (owner != null && !owner.equals(userId()) && !CurrentActor.hasPermission(Permissions.APPROVALS_GRANT)) {
			throw ApiException.forbidden("not_your_till", "Only " + session.get("actor_name") + " or a manager can do this");
		}
	}

	private static boolean canSeeCounts() {
		return CurrentActor.hasPermission(Permissions.CASH_VIEW) || CurrentActor.hasPermission(Permissions.APPROVALS_GRANT);
	}

	static Long userId() {
		return CurrentActor.type() == ActorType.USER ? CurrentActor.id() : null;
	}

	@SuppressWarnings("unused")
	private static Timestamp ts(Instant i) {
		return Timestamp.from(i);
	}
}
