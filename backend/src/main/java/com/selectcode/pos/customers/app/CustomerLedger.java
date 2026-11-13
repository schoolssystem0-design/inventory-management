package com.selectcode.pos.customers.app;

import com.selectcode.pos.shared.money.Amounts;
import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.shared.time.ShopTime;
import com.selectcode.pos.shared.web.ApiException;
import java.math.BigDecimal;
import java.sql.Date;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The customer account (CUS-02, D5): every invoice, return, refund and payment is a ledger line with the running
 * balance, written in the same transaction as the document and the customer's stored balance. Debit = the customer
 * owes more; credit = owes less. A negative balance is an advance held for the customer.
 */
@Service
public class CustomerLedger {

	public record Account(long id, String name, String phone, BigDecimal balance, BigDecimal creditLimit,
			boolean active) {

		/** Owed after adding {@code due}, and whether that passes the credit limit. */
		public boolean exceedsLimit(BigDecimal due) {
			return balance.add(due).compareTo(creditLimit) > 0;
		}
	}

	private final JdbcTemplate jdbc;

	private final ShopTime time;

	CustomerLedger(JdbcTemplate jdbc, ShopTime time) {
		this.jdbc = jdbc;
		this.time = time;
	}

	/** The customer's account (404 if not in this shop). Locks the row when {@code lock} is true. */
	@Transactional(propagation = Propagation.MANDATORY)
	public Account account(long customerId, boolean lock) {
		return jdbc.query("select id, name, phone, balance, credit_limit, active from customer where id = ?"
				+ (lock ? " for update" : ""), (rs, i) -> new Account(rs.getLong("id"), rs.getString("name"),
						rs.getString("phone"), rs.getBigDecimal("balance"), rs.getBigDecimal("credit_limit"),
						rs.getBoolean("active")),
				customerId).stream().findFirst().orElseThrow(() -> ApiException.notFound("Customer"));
	}

	/**
	 * Writes one ledger line and moves the stored balance (the customer row must be locked by the caller through
	 * {@link #account(long, boolean)} or is locked here).
	 * @return balance after
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public BigDecimal post(long customerId, String docType, Long docId, String docNumber, String description,
			BigDecimal debit, BigDecimal credit) {
		BigDecimal d = Amounts.money(Amounts.nz(debit));
		BigDecimal c = Amounts.money(Amounts.nz(credit));
		if (d.signum() < 0 || c.signum() < 0) {
			throw new IllegalArgumentException("Ledger amounts are never negative");
		}
		if (d.signum() == 0 && c.signum() == 0) {
			return account(customerId, true).balance();
		}
		BigDecimal after = jdbc.queryForObject(
				"update customer set balance = balance + ? - ?, updated_at = now() where id = ? returning balance",
				BigDecimal.class, d, c, customerId);
		if (after == null) {
			throw ApiException.notFound("Customer");
		}
		jdbc.update("""
				insert into customer_ledger (shop_id, customer_id, doc_type, doc_id, doc_number, description, debit, credit,
				                             balance_after, business_date)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", TenantContext.requireShopId(), customerId, docType, docId,
				docNumber, description.length() > 300 ? description.substring(0, 300) : description, d, c, after,
				Date.valueOf(time.today()));
		return after;
	}

	/** Sum of the ledger, for the nightly balance check (D5). */
	public Map<String, Object> check(long customerId) {
		return jdbc.queryForMap("""
				select c.balance, coalesce(sum(l.debit), 0) - coalesce(sum(l.credit), 0) as ledger
				from customer c left join customer_ledger l on l.customer_id = c.id where c.id = ? group by c.balance""",
				customerId);
	}
}
