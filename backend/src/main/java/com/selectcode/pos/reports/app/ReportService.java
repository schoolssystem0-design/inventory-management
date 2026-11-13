package com.selectcode.pos.reports.app;

import com.selectcode.pos.customers.app.CustomerService;
import com.selectcode.pos.reports.app.ReportCatalog.Col;
import com.selectcode.pos.reports.app.ReportCatalog.Ctx;
import com.selectcode.pos.reports.app.ReportCatalog.Def;
import com.selectcode.pos.reports.app.ReportCatalog.Sql;
import com.selectcode.pos.shared.docs.TableDoc;
import com.selectcode.pos.shared.features.ShopFeatures;
import com.selectcode.pos.shared.money.Amounts;
import com.selectcode.pos.shared.security.CurrentActor;
import com.selectcode.pos.shared.security.Permissions;
import com.selectcode.pos.shared.time.ShopTime;
import com.selectcode.pos.shared.web.ApiException;
import com.selectcode.pos.shop.app.BranchAccess;
import java.math.BigDecimal;
import java.sql.ResultSetMetaData;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs the report catalogue (RPT-02, RPT-04). The caller's access is captured in {@link Access} at request time, so a
 * background export runs with exactly the same rights as the user who asked for it.
 */
@Service
public class ReportService {

	/** What the requesting user may see: cost columns, personal details and which branches. */
	public record Access(boolean cost, boolean pii, List<Long> branches) {
	}

	public record ReportInfo(String key, String group, String title, String description, List<String> params) {
	}

	public record ReportColumn(String key, String label, String kind) {
	}

	/** Rows hold display values: money and quantities as strings, dates as ISO strings. */
	public record ReportResult(String key, String group, String title, List<String> headings,
			List<ReportColumn> columns, List<List<Object>> rows, List<Object> totals, int rowCount, boolean truncated) {
	}

	/** Most rows returned on screen; exports have no limit. */
	public static final int SCREEN_ROWS = 2000;

	private static final DateTimeFormatter HEADING_DATE = DateTimeFormatter.ofPattern("d MMM yyyy");

	private final JdbcTemplate jdbc;

	private final BranchAccess branches;

	private final ShopFeatures features;

	private final ShopTime time;

	ReportService(JdbcTemplate jdbc, BranchAccess branches, ShopFeatures features, ShopTime time) {
		this.jdbc = jdbc;
		this.branches = branches;
		this.features = features;
		this.time = time;
	}

	/** The current user's access, for running now or in the background. */
	public Access access() {
		return new Access(CurrentActor.hasPermission(Permissions.COST_VIEW),
				CurrentActor.hasPermission(Permissions.CUSTOMERS_PII), branches.allowedIds());
	}

	/** Reports the current user may run (module switched on; cost reports only with cost.view). */
	public List<ReportInfo> list() {
		Access access = access();
		List<ReportInfo> list = new ArrayList<>();
		for (Def def : ReportCatalog.REPORTS.values()) {
			if (visible(def, access)) {
				list.add(new ReportInfo(def.key(), def.group(), def.title(), def.description(), def.params()));
			}
		}
		return list;
	}

	@Transactional(readOnly = true)
	public ReportResult run(String key, LocalDate from, LocalDate to, Long branchId, Map<String, String> extra,
			Access access, Integer maxRows) {
		Def def = ReportCatalog.REPORTS.get(key);
		if (def == null || !visible(def, access)) {
			throw ApiException.notFound("Report");
		}
		LocalDate end = to == null ? time.today() : to;
		LocalDate start = from == null ? end.withDayOfMonth(1) : from;
		if (start.isAfter(end)) {
			throw ApiException.field("from", "after_to", "The start date is after the end date");
		}
		if (start.plusYears(5).isBefore(end)) {
			throw ApiException.field("from", "too_long", "Choose a period of at most 5 years");
		}
		String branchSql;
		String branchName = "All branches";
		if (branchId != null) {
			if (access.branches() != null && !access.branches().contains(branchId)) {
				throw ApiException.notFound("Branch");
			}
			branchName = jdbc.queryForList("select name from branch where id = ?", String.class, branchId)
				.stream()
				.findFirst()
				.orElseThrow(() -> ApiException.notFound("Branch"));
			branchSql = " and $col = " + branchId;
		}
		else if (access.branches() != null) {
			branchSql = access.branches().isEmpty() ? " and false"
					: " and $col in (" + String.join(",", access.branches().stream().map(String::valueOf).toList()) + ")";
		}
		else {
			branchSql = "";
		}
		Ctx ctx = new Ctx(start, end, branchId, branchSql, extra == null ? Map.of() : extra);
		Sql sql = def.query().apply(ctx);
		List<Col> shown = def.columns().stream().filter(c -> access.cost() || !c.cost()).toList();
		List<List<Object>> rows = new ArrayList<>();
		BigDecimal[] totals = new BigDecimal[shown.size()];
		int limit = maxRows == null ? Integer.MAX_VALUE : maxRows;
		int[] count = { 0 };
		boolean maskPii = def.pii() && !access.pii();
		jdbc.query(sql.sql(), rs -> {
			count[0]++;
			if (rows.size() >= limit) {
				return;
			}
			ResultSetMetaData meta = rs.getMetaData();
			Map<String, Integer> index = new LinkedHashMap<>();
			for (int i = 1; i <= meta.getColumnCount(); i++) {
				index.put(meta.getColumnLabel(i), i);
			}
			List<Object> row = new ArrayList<>();
			for (int i = 0; i < shown.size(); i++) {
				Col col = shown.get(i);
				Integer at = index.get(col.key());
				if (at == null) {
					at = index.get(col.key().toLowerCase());
				}
				Object value = at == null ? null : rs.getObject(at);
				Object display = display(col, value);
				if (maskPii && "cnic".equals(col.key()) && display != null) {
					display = CustomerService.maskCnic(display.toString());
				}
				if (maskPii && "phone".equals(col.key()) && display != null) {
					display = CustomerService.maskPhone(display.toString());
				}
				row.add(display);
				if (col.total() && value instanceof Number n) {
					totals[i] = (totals[i] == null ? BigDecimal.ZERO : totals[i]).add(new BigDecimal(n.toString()));
				}
			}
			rows.add(row);
		}, sql.args().toArray());
		List<Object> totalRow = null;
		if (!rows.isEmpty() && shown.stream().anyMatch(Col::total)) {
			totalRow = new ArrayList<>();
			for (int i = 0; i < shown.size(); i++) {
				totalRow.add(i == 0 ? "Total" : totals[i] == null ? null : display(shown.get(i), totals[i]));
			}
		}
		List<String> headings = new ArrayList<>();
		headings.add(jdbc.queryForObject("select business_name from shop_settings limit 1", String.class));
		headings.add(branchName + " · " + HEADING_DATE.format(start) + " – " + HEADING_DATE.format(end));
		ctx.extra().forEach((k, v) -> headings.add(k + ": " + v));
		return new ReportResult(def.key(), def.group(), def.title(), headings,
				shown.stream().map(c -> new ReportColumn(c.key(), c.label(), c.kind())).toList(), rows, totalRow,
				count[0], count[0] > rows.size());
	}

	/** The result as a printable table for PDF / Excel. */
	public static TableDoc toDoc(ReportResult r) {
		TableDoc.Builder doc = TableDoc.builder(r.title());
		r.headings().forEach(doc::heading);
		r.columns().forEach(c -> doc.column(c.key(), c.label(), c.kind()));
		doc.rows(r.rows());
		if (r.totals() != null) {
			doc.totals(r.totals());
		}
		if (r.truncated()) {
			doc.footer("Showing the first " + r.rows().size() + " of " + r.rowCount() + " rows");
		}
		return doc.build();
	}

	boolean visible(Def def, Access access) {
		if (def.costOnly() && !access.cost()) {
			return false;
		}
		return def.module() == null || features.isOn(def.module());
	}

	private static Object display(Col col, Object value) {
		if (value == null) {
			return null;
		}
		return switch (col.kind()) {
			case "MONEY" -> Amounts.text(new BigDecimal(value.toString()));
			case "QTY" -> Amounts.qtyText(new BigDecimal(value.toString()));
			case "PERCENT" -> new BigDecimal(value.toString()).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
			case "INT" -> ((Number) value).longValue();
			case "DATE" -> value instanceof java.sql.Date d ? d.toLocalDate().toString() : value.toString();
			default -> value.toString();
		};
	}
}
