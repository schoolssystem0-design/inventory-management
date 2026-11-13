package com.selectcode.pos.sales.app;

import com.selectcode.pos.notifications.app.Notifier;
import com.selectcode.pos.sales.app.SaleQueries.ReceiptView;
import com.selectcode.pos.sales.app.SaleQueries.SaleLineView;
import com.selectcode.pos.shared.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sends a receipt by email (NTF-05) or SMS (when the shop's SMS module is on). */
@Service
public class ReceiptSender {

	public record SendReceipt(String email, String phone) {
	}

	public record Sent(boolean email, boolean sms) {
	}

	private final SaleQueries queries;

	private final Notifier notifier;

	ReceiptSender(SaleQueries queries, Notifier notifier, JdbcTemplate jdbc) {
		this.queries = queries;
		this.notifier = notifier;
	}

	@Transactional
	public Sent send(long saleId, SendReceipt in) {
		ReceiptView r = queries.receipt(saleId);
		if (in == null || (blank(in.email()) && blank(in.phone()))) {
			throw ApiException.field("email", "required", "Enter an email or phone number");
		}
		StringBuilder body = new StringBuilder();
		body.append(r.businessName()).append('\n');
		if (r.address() != null) {
			body.append(r.address()).append('\n');
		}
		body.append("Invoice ").append(r.sale().number()).append(" · ").append(r.sale().date()).append("\n\n");
		for (SaleLineView l : r.sale().lines()) {
			body.append(l.productName()).append(" × ").append(l.qty()).append("  Rs ").append(l.total());
			if (l.serial() != null) {
				body.append("  (IMEI ").append(l.serial()).append(')');
			}
			body.append('\n');
		}
		body.append("\nTotal Rs ").append(r.sale().total()).append(" · Paid Rs ").append(r.sale().paid());
		if (new java.math.BigDecimal(r.sale().due()).signum() > 0) {
			body.append(" · Due Rs ").append(r.sale().due());
		}
		if (r.footer() != null) {
			body.append("\n\n").append(r.footer());
		}
		boolean email = !blank(in.email()) && notifier.email(in.email(), "receipt",
				"Receipt " + r.sale().number() + " from " + r.businessName(), body.toString(), null);
		boolean sms = !blank(in.phone()) && notifier.sms(in.phone(), "receipt", r.businessName() + ": invoice "
				+ r.sale().number() + " Rs " + r.sale().total() + ". Thank you!", null, false);
		if (!email && !sms) {
			throw ApiException.rule("not_sent", blank(in.email()) ? "SMS receipts are not switched on for this shop"
					: "Enter a valid email address");
		}
		return new Sent(email, sms);
	}

	private static boolean blank(String s) {
		return s == null || s.isBlank();
	}
}
