package com.selectcode.pos.reports.app;

import com.selectcode.pos.reports.app.ReportService.Access;
import com.selectcode.pos.reports.app.ReportService.ReportResult;
import com.selectcode.pos.shared.docs.ExcelWriter;
import com.selectcode.pos.shared.docs.PdfWriter;
import com.selectcode.pos.shared.docs.TableDoc;
import com.selectcode.pos.shared.files.FilePurpose;
import com.selectcode.pos.shared.files.FileService;
import com.selectcode.pos.shared.security.ActorType;
import com.selectcode.pos.shared.security.CurrentActor;
import com.selectcode.pos.shared.security.Permissions;
import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.shared.web.ApiException;
import com.selectcode.pos.shared.web.RateLimiter;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Background exports (OPS-13, TC-RPT-02) and the owner's Excel backup (NFR-13, OPS-14): the request returns a job at
 * once; the file is made on a background thread with the requester's access and kept as a private file the requester
 * can download. Limited to 5 exports an hour per shop.
 */
@Service
public class ExportService {

	private static final Logger log = LoggerFactory.getLogger(ExportService.class);

	public static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

	public record ExportRequest(String format, LocalDate from, LocalDate to, Long branchId, Map<String, String> params) {
	}

	public record ExportJob(long id, String kind, String format, String status, Long fileId, Integer rows,
			String error, Instant createdAt, Instant finishedAt) {
	}

	private final JdbcTemplate jdbc;

	private final ReportService reports;

	private final FileService files;

	private final RateLimiter rateLimiter;

	private final JsonMapper json;

	private final TransactionTemplate tx;

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	ExportService(JdbcTemplate jdbc, ReportService reports, FileService files, RateLimiter rateLimiter, JsonMapper json,
			PlatformTransactionManager transactions) {
		this.jdbc = jdbc;
		this.reports = reports;
		this.files = files;
		this.rateLimiter = rateLimiter;
		this.json = json;
		this.tx = new TransactionTemplate(transactions);
	}

	/** Queues a report export; {@code kind} is the report key, or BACKUP for the owner's full backup. */
	@Transactional
	public ExportJob request(String kind, ExportRequest in) {
		long shopId = TenantContext.requireShopId();
		String format = in.format() == null ? "XLSX" : in.format().trim().toUpperCase();
		if (!List.of("XLSX", "PDF").contains(format)) {
			throw ApiException.field("format", "invalid", "Choose XLSX or PDF");
		}
		Access access = reports.access();
		if ("BACKUP".equals(kind)) {
			if (!CurrentActor.hasPermission(Permissions.DATA_EXPORT)) {
				throw ApiException.forbidden("forbidden", "Only the owner can download the full backup");
			}
			format = "XLSX";
		}
		else {
			// Runs the check (report exists, visible, filters valid) before queueing.
			reports.run(kind, in.from(), in.to(), in.branchId(), in.params(), access, 1);
		}
		rateLimiter.check("exports:" + shopId, 5, Duration.ofHours(1));
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("from", in.from() == null ? null : in.from().toString());
		params.put("to", in.to() == null ? null : in.to().toString());
		params.put("branchId", in.branchId());
		params.put("params", in.params() == null ? Map.of() : in.params());
		params.put("cost", access.cost());
		params.put("pii", access.pii());
		params.put("branches", access.branches());
		Long userId = CurrentActor.type() == ActorType.USER ? CurrentActor.id() : null;
		long id = jdbc.queryForObject("""
				insert into export_job (shop_id, kind, format, params, requested_by, actor_name)
				values (?, ?, ?, ?::jsonb, ?, ?) returning id""", Long.class, shopId, kind, format,
				json.writeValueAsString(params), userId, CurrentActor.name());
		String fmt = format;
		Runnable work = () -> executor.submit(() -> TenantContext.runAsShop(shopId,
				() -> runJob(id, kind, fmt, in, access, userId)));
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					work.run();
				}
			});
		}
		else {
			work.run();
		}
		return get(id);
	}

	@Transactional(readOnly = true)
	public ExportJob get(long id) {
		return jdbc.query("select * from export_job where id = ?", (rs, i) -> new ExportJob(rs.getLong("id"),
				rs.getString("kind"), rs.getString("format"), rs.getString("status"), (Long) rs.getObject("file_id"),
				(Integer) rs.getObject("row_count"), rs.getString("error"), rs.getTimestamp("created_at").toInstant(),
				rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant()), id)
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Export"));
	}

	@Transactional(readOnly = true)
	public List<ExportJob> recent() {
		return jdbc.queryForList("select id from export_job order by id desc limit 20", Long.class)
			.stream()
			.map(this::get)
			.toList();
	}

	private void runJob(long id, String kind, String format, ExportRequest in, Access access, Long userId) {
		try {
			tx.executeWithoutResult(s -> jdbc.update("update export_job set status = 'RUNNING' where id = ?", id));
			tx.executeWithoutResult(s -> {
				byte[] bytes;
				int rows;
				String name;
				if ("BACKUP".equals(kind)) {
					List<TableDoc> sheets = backup();
					bytes = ExcelWriter.write(sheets);
					rows = sheets.stream().mapToInt(d -> d.rows().size()).sum();
					name = "backup-" + LocalDate.now(com.selectcode.pos.shared.time.ShopTime.ZONE) + ".xlsx";
				}
				else {
					ReportResult result = reports.run(kind, in.from(), in.to(), in.branchId(), in.params(), access, null);
					TableDoc doc = ReportService.toDoc(result);
					bytes = "PDF".equals(format) ? PdfWriter.write(doc) : ExcelWriter.write(doc);
					rows = result.rows().size();
					name = kind + "-" + LocalDate.now(com.selectcode.pos.shared.time.ShopTime.ZONE) + ("PDF".equals(format) ? ".pdf" : ".xlsx");
				}
				var file = files.store(FilePurpose.EXPORT, name, "PDF".equals(format) ? "application/pdf" : XLSX, bytes,
						userId);
				jdbc.update("""
						update export_job set status = 'DONE', file_id = ?, row_count = ?, finished_at = now() where id = ?""",
						file.id(), rows, id);
			});
		}
		catch (RuntimeException e) {
			log.warn("export {} failed: {}", id, e.getMessage(), e);
			String message = e instanceof ApiException a ? a.getMessage() : "The export could not be made";
			tx.executeWithoutResult(s -> jdbc.update(
					"update export_job set status = 'FAILED', error = ?, finished_at = now() where id = ?", message, id));
		}
	}

	/** The owner's full Excel backup (NFR-13): one sheet per kind of data. */
	List<TableDoc> backup() {
		List<TableDoc> sheets = new ArrayList<>();
		sheets.add(sheet("Products", """
				select p.sku, p.name, p.urdu_name, c.name as category, b.name as brand, u.name as unit, p.kind, p.price,
				       p.cost_avg, p.wholesale_price, p.min_price, p.reorder_level, p.active,
				       (select string_agg(barcode, ' ') from product_barcode pb where pb.product_id = p.id) as barcodes
				from product p left join category c on c.id = p.category_id left join brand b on b.id = p.brand_id
				join unit u on u.id = p.unit_id order by p.name""",
				new String[][] { { "sku", "SKU", "TEXT" }, { "name", "Name", "TEXT" }, { "urdu_name", "Urdu name", "TEXT" },
						{ "category", "Category", "TEXT" }, { "brand", "Brand", "TEXT" }, { "unit", "Unit", "TEXT" },
						{ "kind", "Kind", "TEXT" }, { "price", "Price", "MONEY" }, { "cost_avg", "Average cost", "MONEY" },
						{ "wholesale_price", "Wholesale", "MONEY" }, { "min_price", "Lowest price", "MONEY" },
						{ "reorder_level", "Reorder level", "QTY" }, { "active", "Active", "TEXT" },
						{ "barcodes", "Barcodes", "TEXT" } }));
		sheets.add(sheet("Stock", """
				select p.sku, p.name, b.name as branch, bt.batch_no, bt.expiry_date, s.qty from product_stock s
				join product p on p.id = s.product_id join branch b on b.id = s.branch_id left join batch bt on bt.id = s.batch_id
				where s.qty <> 0 order by p.name, b.name""",
				new String[][] { { "sku", "SKU", "TEXT" }, { "name", "Name", "TEXT" }, { "branch", "Branch", "TEXT" },
						{ "batch_no", "Batch", "TEXT" }, { "expiry_date", "Expiry", "DATE" }, { "qty", "Qty", "QTY" } }));
		sheets.add(sheet("IMEI units", """
				select u.serial, u.serial2, p.name, u.status, b.name as branch, u.condition, u.cost from serial_unit u
				join product p on p.id = u.product_id join branch b on b.id = u.branch_id order by p.name, u.serial""",
				new String[][] { { "serial", "IMEI / serial", "TEXT" }, { "serial2", "IMEI 2", "TEXT" },
						{ "name", "Product", "TEXT" }, { "status", "Status", "TEXT" }, { "branch", "Branch", "TEXT" },
						{ "condition", "Condition", "TEXT" }, { "cost", "Cost", "MONEY" } }));
		sheets.add(sheet("Customers", """
				select code, name, phone, cnic, address, kind, credit_limit, balance, active from customer order by name""",
				new String[][] { { "code", "Code", "TEXT" }, { "name", "Name", "TEXT" }, { "phone", "Phone", "TEXT" },
						{ "cnic", "CNIC", "TEXT" }, { "address", "Address", "TEXT" }, { "kind", "Type", "TEXT" },
						{ "credit_limit", "Credit limit", "MONEY" }, { "balance", "Balance", "MONEY" },
						{ "active", "Active", "TEXT" } }));
		sheets.add(sheet("Suppliers", """
				select code, name, company, phone, ntn, terms_days, balance, active from supplier order by name""",
				new String[][] { { "code", "Code", "TEXT" }, { "name", "Name", "TEXT" }, { "company", "Company", "TEXT" },
						{ "phone", "Phone", "TEXT" }, { "ntn", "NTN", "TEXT" }, { "terms_days", "Terms (days)", "INT" },
						{ "balance", "Owed", "MONEY" }, { "active", "Active", "TEXT" } }));
		sheets.add(sheet("Sales", """
				select s.number, s.business_date, b.name as branch, s.customer_name, s.actor_name, s.status, s.subtotal,
				       s.line_discount + s.bill_discount as discount, s.tax, s.total, s.returned, s.paid, s.due, s.cost_total
				from sale s join branch b on b.id = s.branch_id order by s.id""",
				new String[][] { { "number", "Invoice", "TEXT" }, { "business_date", "Date", "DATE" },
						{ "branch", "Branch", "TEXT" }, { "customer_name", "Customer", "TEXT" },
						{ "actor_name", "Cashier", "TEXT" }, { "status", "Status", "TEXT" },
						{ "subtotal", "Subtotal", "MONEY" }, { "discount", "Discount", "MONEY" }, { "tax", "Tax", "MONEY" },
						{ "total", "Total", "MONEY" }, { "returned", "Returned", "MONEY" }, { "paid", "Paid", "MONEY" },
						{ "due", "Due", "MONEY" }, { "cost_total", "Cost", "MONEY" } }));
		sheets.add(sheet("Sale lines", """
				select s.number, l.product_name, l.qty, l.unit_name, l.price, l.discount, l.tax, l.total, l.unit_cost,
				       l.qty_returned from sale_line l join sale s on s.id = l.sale_id order by s.id, l.line_no""",
				new String[][] { { "number", "Invoice", "TEXT" }, { "product_name", "Item", "TEXT" }, { "qty", "Qty", "QTY" },
						{ "unit_name", "Unit", "TEXT" }, { "price", "Price", "MONEY" }, { "discount", "Discount", "MONEY" },
						{ "tax", "Tax", "MONEY" }, { "total", "Total", "MONEY" }, { "unit_cost", "Cost", "MONEY" },
						{ "qty_returned", "Returned", "QTY" } }));
		sheets.add(sheet("Purchases", """
				select g.number, g.business_date, s.name as supplier, g.supplier_bill_no, p.name as product, l.qty, l.bonus_qty,
				       l.unit_cost, l.net from grn_line l join grn g on g.id = l.grn_id join supplier s on s.id = g.supplier_id
				join product p on p.id = l.product_id order by g.id, l.id""",
				new String[][] { { "number", "Receipt", "TEXT" }, { "business_date", "Date", "DATE" },
						{ "supplier", "Supplier", "TEXT" }, { "supplier_bill_no", "Bill no.", "TEXT" },
						{ "product", "Item", "TEXT" }, { "qty", "Qty", "QTY" }, { "bonus_qty", "Bonus", "QTY" },
						{ "unit_cost", "Rate", "MONEY" }, { "net", "Amount", "MONEY" } }));
		sheets.add(sheet("Expenses", """
				select e.number, e.business_date, c.name as category, e.amount, e.method_key, e.paid_to, e.note, e.status
				from expense e join expense_category c on c.id = e.category_id order by e.id""",
				new String[][] { { "number", "Number", "TEXT" }, { "business_date", "Date", "DATE" },
						{ "category", "Category", "TEXT" }, { "amount", "Amount", "MONEY" }, { "method_key", "Paid by", "TEXT" },
						{ "paid_to", "Paid to", "TEXT" }, { "note", "Note", "TEXT" }, { "status", "Status", "TEXT" } }));
		return sheets;
	}

	private TableDoc sheet(String title, String sql, String[][] columns) {
		TableDoc.Builder doc = TableDoc.builder(title);
		for (String[] c : columns) {
			doc.column(c[0], c[1], c[2]);
		}
		jdbc.query(sql, rs -> {
			List<Object> row = new ArrayList<>();
			for (String[] c : columns) {
				Object v = rs.getObject(c[0]);
				if (v instanceof java.sql.Date d) {
					v = d.toLocalDate();
				}
				else if (v instanceof BigDecimal b) {
					v = b.toPlainString();
				}
				else if (v instanceof Boolean b) {
					v = b ? "Yes" : "No";
				}
				row.add(v);
			}
			doc.row(row);
		});
		return doc.build();
	}
}
