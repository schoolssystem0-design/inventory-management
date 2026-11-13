package com.selectcode.pos.catalog.app;

import com.selectcode.pos.catalog.app.ProductService.BookFields;
import com.selectcode.pos.catalog.app.ProductService.PartFields;
import com.selectcode.pos.catalog.app.ProductService.PharmaFields;
import com.selectcode.pos.catalog.app.ProductService.ProductInput;
import com.selectcode.pos.shared.audit.AuditService;
import com.selectcode.pos.shared.features.ModuleKey;
import com.selectcode.pos.shared.features.PlanLimits;
import com.selectcode.pos.shared.features.ShopFeatures;
import com.selectcode.pos.shared.money.Amounts;
import com.selectcode.pos.shared.security.CurrentActor;
import com.selectcode.pos.shared.security.Permissions;
import com.selectcode.pos.shared.web.ApiException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Excel import and export of products (CAT-06, INT-05). Import is two steps: a dry run that lists every row error
 * without saving anything, then a commit that saves all rows in one transaction (or none if any row is wrong).
 * Rows are matched to existing products by SKU (update) or created. Missing categories, brands and units are created.
 */
@Service
public class ProductImportService {

	public static final int MAX_ROWS = 50_000;

	public record RowError(int row, String column, String message) {
	}

	public record ImportResult(boolean committed, int rows, int toCreate, int toUpdate, List<RowError> errors,
			List<String> newCategories, List<String> newBrands, List<String> newUnits, List<String> ignoredColumns) {
	}

	/** Column header → key. Headers are matched case-insensitively, ignoring spaces, dashes and underscores. */
	private static final Map<String, String> COLUMNS = new LinkedHashMap<>();

	static {
		COLUMNS.put("sku", "sku");
		COLUMNS.put("name", "name");
		COLUMNS.put("productname", "name");
		COLUMNS.put("urduname", "urdu_name");
		COLUMNS.put("barcode", "barcode");
		COLUMNS.put("barcodes", "barcode");
		COLUMNS.put("category", "category");
		COLUMNS.put("brand", "brand");
		COLUMNS.put("company", "brand");
		COLUMNS.put("unit", "unit");
		COLUMNS.put("cost", "cost");
		COLUMNS.put("costprice", "cost");
		COLUMNS.put("purchaseprice", "cost");
		COLUMNS.put("price", "price");
		COLUMNS.put("saleprice", "price");
		COLUMNS.put("retailprice", "price");
		COLUMNS.put("wholesaleprice", "wholesale_price");
		COLUMNS.put("minprice", "min_price");
		COLUMNS.put("minimumprice", "min_price");
		COLUMNS.put("taxrate", "tax_rate");
		COLUMNS.put("tax", "tax_rate");
		COLUMNS.put("reorderlevel", "reorder_level");
		COLUMNS.put("reorderqty", "reorder_qty");
		COLUMNS.put("description", "description");
		COLUMNS.put("formula", "formula");
		COLUMNS.put("generic", "formula");
		COLUMNS.put("strength", "strength");
		COLUMNS.put("form", "dosage_form");
		COLUMNS.put("dosageform", "dosage_form");
		COLUMNS.put("manufacturer", "pharma_company");
		COLUMNS.put("packsize", "pack_size");
		COLUMNS.put("schedule", "schedule");
		COLUMNS.put("rack", "rack");
		COLUMNS.put("registrationno", "registration_no");
		COLUMNS.put("oemnumber", "oem_number");
		COLUMNS.put("partnumber", "oem_number");
		COLUMNS.put("alternatenumbers", "alternate_numbers");
		COLUMNS.put("isbn", "isbn");
		COLUMNS.put("author", "author");
		COLUMNS.put("publisher", "publisher");
		COLUMNS.put("edition", "edition");
		COLUMNS.put("class", "class_name");
	}

	private static final Set<String> PHARMA = Set.of("formula", "strength", "dosage_form", "pharma_company", "pack_size",
			"schedule", "rack", "registration_no");

	private static final Set<String> PART = Set.of("oem_number", "alternate_numbers");

	private static final Set<String> BOOK = Set.of("isbn", "author", "publisher", "edition", "class_name");

	private final ProductService products;

	private final TaxonomyService taxonomy;

	private final JdbcTemplate jdbc;

	private final ShopFeatures features;

	private final PlanLimits limits;

	private final AuditService audit;

	ProductImportService(ProductService products, TaxonomyService taxonomy, JdbcTemplate jdbc, ShopFeatures features,
			PlanLimits limits, AuditService audit) {
		this.products = products;
		this.taxonomy = taxonomy;
		this.jdbc = jdbc;
		this.features = features;
		this.limits = limits;
		this.audit = audit;
	}

	// ------------------------------------------------------------------ template and export

	/** The column headers for this shop's modules, in template order. */
	public List<String> headers() {
		List<String> headers = new ArrayList<>(List.of("SKU", "Name", "Urdu name", "Barcode", "Category", "Brand", "Unit",
				"Cost price", "Price", "Wholesale price", "Min price", "Tax rate", "Reorder level", "Reorder qty",
				"Description"));
		if (features.isOn(ModuleKey.FORMULA)) {
			headers.addAll(List.of("Formula", "Strength", "Form", "Manufacturer", "Pack size", "Schedule", "Rack",
					"Registration no"));
		}
		if (features.isOn(ModuleKey.PART_NUMBERS)) {
			headers.addAll(List.of("OEM number", "Alternate numbers"));
		}
		if (features.isOn(ModuleKey.BUNDLES)) {
			headers.addAll(List.of("ISBN", "Author", "Publisher", "Edition", "Class"));
		}
		return headers;
	}

	@Transactional(readOnly = true)
	public byte[] template() {
		try (XSSFWorkbook book = new XSSFWorkbook()) {
			Sheet sheet = book.createSheet("Products");
			List<String> headers = headers();
			Row head = sheet.createRow(0);
			for (int i = 0; i < headers.size(); i++) {
				head.createCell(i).setCellValue(headers.get(i));
				sheet.setColumnWidth(i, 18 * 256);
			}
			Map<String, String> example = new HashMap<>(Map.of("Name", "Example product", "Barcode", "8961234567890",
					"Category", "General", "Unit", "piece", "Cost price", "100", "Price", "150", "Reorder level", "5"));
			example.putAll(Map.of("Formula", "Paracetamol", "Strength", "500 mg", "Form", "Tablet", "Schedule", "OTC",
					"Pack size", "20 x 10", "OEM number", "12345-ABC", "ISBN", "9789690000000"));
			Row row = sheet.createRow(1);
			for (int i = 0; i < headers.size(); i++) {
				row.createCell(i).setCellValue(example.getOrDefault(headers.get(i), ""));
			}
			Sheet help = book.createSheet("How to fill");
			String[] lines = { "One product per row. Name and Price are required.",
					"SKU: leave empty to get one automatically. A SKU that already exists updates that product.",
					"Barcode: several barcodes can be separated by commas.",
					"Category, Brand and Unit: written by name; missing ones are created.",
					"Amounts like 1500 or 1500.50; no currency signs.",
					"Upload the file, check the list of problems, fix them in Excel and upload again." };
			for (int i = 0; i < lines.length; i++) {
				help.createRow(i).createCell(0).setCellValue(lines[i]);
			}
			return write(book);
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	@Transactional(readOnly = true)
	public byte[] export() {
		boolean showCost = CurrentActor.hasPermission(Permissions.COST_VIEW);
		List<String> headers = new ArrayList<>(headers());
		if (!showCost) {
			headers.remove("Cost price");
		}
		headers.add("Active");
		try (SXSSFWorkbook book = new SXSSFWorkbook(200)) {
			Sheet sheet = book.createSheet("Products");
			Row head = sheet.createRow(0);
			for (int i = 0; i < headers.size(); i++) {
				head.createCell(i).setCellValue(headers.get(i));
			}
			int[] rowNo = { 1 };
			jdbc.query("""
					select p.*, c.name as category, b.name as brand, u.name as unit,
					    (select string_agg(barcode, ',' order by id) from product_barcode pb where pb.product_id = p.id) as barcodes,
					    f.formula, f.strength, f.dosage_form, f.company as manufacturer, f.pack_size, f.schedule, f.rack,
					    f.registration_no, pp.oem_number, array_to_string(pp.alternate_numbers, ',') as alternates,
					    bk.isbn, bk.author, bk.publisher, bk.edition, bk.class_name
					from product p
					left join category c on c.id = p.category_id
					left join brand b on b.id = p.brand_id
					join unit u on u.id = p.unit_id
					left join product_pharma f on f.product_id = p.id
					left join product_part pp on pp.product_id = p.id
					left join product_book bk on bk.product_id = p.id
					order by p.name""", (RowCallbackHandler) rs -> {
				Map<String, String> v = new HashMap<>();
				v.put("SKU", rs.getString("sku"));
				v.put("Name", rs.getString("name"));
				v.put("Urdu name", rs.getString("urdu_name"));
				v.put("Barcode", rs.getString("barcodes"));
				v.put("Category", rs.getString("category"));
				v.put("Brand", rs.getString("brand"));
				v.put("Unit", rs.getString("unit"));
				v.put("Cost price", Amounts.text(rs.getBigDecimal("cost_avg")));
				v.put("Price", Amounts.text(rs.getBigDecimal("price")));
				v.put("Wholesale price", Amounts.text(rs.getBigDecimal("wholesale_price")));
				v.put("Min price", Amounts.text(rs.getBigDecimal("min_price")));
				v.put("Tax rate", Amounts.percentText(rs.getBigDecimal("tax_rate")));
				v.put("Reorder level", Amounts.qtyText(rs.getBigDecimal("reorder_level")));
				v.put("Reorder qty", Amounts.qtyText(rs.getBigDecimal("reorder_qty")));
				v.put("Description", rs.getString("description"));
				v.put("Formula", rs.getString("formula"));
				v.put("Strength", rs.getString("strength"));
				v.put("Form", rs.getString("dosage_form"));
				v.put("Manufacturer", rs.getString("manufacturer"));
				v.put("Pack size", rs.getString("pack_size"));
				v.put("Schedule", rs.getString("schedule"));
				v.put("Rack", rs.getString("rack"));
				v.put("Registration no", rs.getString("registration_no"));
				v.put("OEM number", rs.getString("oem_number"));
				v.put("Alternate numbers", rs.getString("alternates"));
				v.put("ISBN", rs.getString("isbn"));
				v.put("Author", rs.getString("author"));
				v.put("Publisher", rs.getString("publisher"));
				v.put("Edition", rs.getString("edition"));
				v.put("Class", rs.getString("class_name"));
				v.put("Active", rs.getBoolean("active") ? "Yes" : "No");
				Row row = sheet.createRow(rowNo[0]++);
				for (int i = 0; i < headers.size(); i++) {
					row.createCell(i).setCellValue(safeCell(v.get(headers.get(i))));
				}
			});
			return write(book);
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	// ------------------------------------------------------------------ import

	private record ParsedRow(int rowNumber, Map<String, String> values) {
	}

	/** Dry run when {@code commit} is false: nothing is saved. */
	@Transactional
	public ImportResult importFile(byte[] content, boolean commit) {
		List<String> ignored = new ArrayList<>();
		List<ParsedRow> rows = parse(content, ignored);
		List<RowError> errors = new ArrayList<>();
		Map<String, Long> existingSkus = new HashMap<>();
		jdbc.query("select id, lower(sku) as sku from product", rs -> {
			existingSkus.put(rs.getString("sku"), rs.getLong("id"));
		});
		Map<String, Long> barcodeOwners = new HashMap<>();
		jdbc.query("select barcode, product_id from product_barcode union all "
				+ "select barcode, product_id from product_unit where barcode is not null", rs -> {
					barcodeOwners.put(rs.getString(1), rs.getLong(2));
				});
		Set<String> categories = lowerNames("category");
		Set<String> brands = lowerNames("brand");
		Set<String> units = lowerNames("unit");
		Set<String> newCategories = new java.util.TreeSet<>();
		Set<String> newBrands = new java.util.TreeSet<>();
		Set<String> newUnits = new java.util.TreeSet<>();
		Set<String> skusInFile = new HashSet<>();
		Map<String, Integer> barcodesInFile = new HashMap<>();
		int toCreate = 0;
		int toUpdate = 0;
		List<Long> targets = new ArrayList<>();
		for (ParsedRow row : rows) {
			Map<String, String> v = row.values();
			int n = row.rowNumber();
			int before = errors.size();
			String name = v.get("name");
			if (name == null || name.isBlank()) {
				errors.add(new RowError(n, "Name", "Name is required"));
			}
			else if (name.length() > 200) {
				errors.add(new RowError(n, "Name", "Name is longer than 200 characters"));
			}
			String sku = v.get("sku");
			Long found = null;
			if (sku != null && !sku.isBlank()) {
				if (!sku.trim().matches("[A-Za-z0-9._/-]{1,40}")) {
					errors.add(new RowError(n, "SKU", "SKU may only contain letters, numbers, dots, dashes or slashes"));
				}
				else if (!skusInFile.add(sku.trim().toLowerCase())) {
					errors.add(new RowError(n, "SKU", "SKU " + sku + " appears twice in the file"));
				}
				found = existingSkus.get(sku.trim().toLowerCase());
			}
			final Long target = found;
			check(errors, n, "Price", () -> {
				if (target == null && (v.get("price") == null || v.get("price").isBlank())) {
					throw ApiException.field("price", "required", "Price is required");
				}
				Amounts.parseMoney(v.get("price"), "price");
			});
			check(errors, n, "Cost price", () -> Amounts.parseMoney(v.get("cost"), "cost"));
			check(errors, n, "Wholesale price", () -> Amounts.parseMoney(v.get("wholesale_price"), "wholesale_price"));
			check(errors, n, "Min price", () -> Amounts.parseMoney(v.get("min_price"), "min_price"));
			check(errors, n, "Tax rate", () -> Amounts.parsePercent(v.get("tax_rate"), "tax_rate"));
			check(errors, n, "Reorder level", () -> Amounts.parseQty(v.get("reorder_level"), "reorder_level"));
			check(errors, n, "Reorder qty", () -> Amounts.parseQty(v.get("reorder_qty"), "reorder_qty"));
			if (v.get("cost") != null && !v.get("cost").isBlank() && !CurrentActor.hasPermission(Permissions.COST_VIEW)) {
				errors.add(new RowError(n, "Cost price", "You cannot set cost prices; remove this column"));
			}
			List<String> codes = split(v.get("barcode"));
			for (String code : codes) {
				if (!BarcodeFormats.acceptable(code)) {
					errors.add(new RowError(n, "Barcode", "Barcode \"" + code + "\" is not valid"));
					continue;
				}
				Integer other = barcodesInFile.putIfAbsent(code, n);
				if (other != null) {
					errors.add(new RowError(n, "Barcode", "Barcode " + code + " is also on row " + other));
				}
				Long owner = barcodeOwners.get(code);
				if (owner != null && !owner.equals(target)) {
					errors.add(new RowError(n, "Barcode", "Barcode " + code + " already belongs to another product"));
				}
			}
			String schedule = v.get("schedule");
			if (schedule != null && !schedule.isBlank()
					&& !Set.of("OTC", "RX", "CONTROLLED").contains(schedule.trim().toUpperCase())) {
				errors.add(new RowError(n, "Schedule", "Use OTC, RX or CONTROLLED"));
			}
			for (String[] taxo : new String[][] { { "category", "Category" }, { "brand", "Brand" }, { "unit", "Unit" } }) {
				String value = v.get(taxo[0]);
				if (value != null && value.trim().length() > (taxo[0].equals("unit") ? 30 : 100)) {
					errors.add(new RowError(n, taxo[1], taxo[1] + " name is too long"));
				}
			}
			if (target == null && (v.get("unit") == null || v.get("unit").isBlank()) && units.isEmpty()) {
				errors.add(new RowError(n, "Unit", "Unit is required"));
			}
			if (errors.size() == before) {
				note(v.get("category"), categories, newCategories);
				note(v.get("brand"), brands, newBrands);
				note(v.get("unit"), units, newUnits);
				if (target == null) {
					toCreate++;
				}
				else {
					toUpdate++;
				}
				targets.add(target);
			}
			if (errors.size() > 500) {
				errors.add(new RowError(n, "", "Too many problems: showing the first 500"));
				break;
			}
		}
		if (toCreate > 0) {
			try {
				Long active = jdbc.queryForObject("select count(*) from product where active", Long.class);
				limits.check(PlanLimits.Limit.PRODUCTS, active == null ? 0 : active, toCreate);
			}
			catch (ApiException limit) {
				errors.add(new RowError(0, "", limit.getMessage()));
			}
		}
		boolean doCommit = commit && errors.isEmpty();
		if (commit && !errors.isEmpty()) {
			throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT, "import_has_errors",
					"The file has " + errors.size() + " problem(s). Nothing was saved.", Map.of("errors", errors));
		}
		if (doCommit) {
			int index = 0;
			for (ParsedRow row : rows) {
				Long target = targets.get(index++);
				try {
					products.saveImported(toInput(row.values(), target != null), target);
				}
				catch (ApiException e) {
					throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT, "import_has_errors",
							"Row " + row.rowNumber() + ": " + e.getMessage() + ". Nothing was saved.",
							Map.of("errors", List.of(new RowError(row.rowNumber(), "", e.getMessage()))));
				}
			}
			audit.record("PRODUCTS_IMPORTED", "product", null, null,
					Map.of("rows", rows.size(), "created", toCreate, "updated", toUpdate));
		}
		return new ImportResult(doCommit, rows.size(), toCreate, toUpdate, errors, List.copyOf(newCategories),
				List.copyOf(newBrands), List.copyOf(newUnits), ignored);
	}

	private ProductInput toInput(Map<String, String> v, boolean existing) {
		boolean pharma = features.isOn(ModuleKey.FORMULA) && PHARMA.stream().anyMatch(k -> present(v.get(k)));
		boolean part = features.isOn(ModuleKey.PART_NUMBERS) && PART.stream().anyMatch(k -> present(v.get(k)));
		boolean book = features.isOn(ModuleKey.BUNDLES) && BOOK.stream().anyMatch(k -> present(v.get(k)));
		Long unitId = present(v.get("unit")) ? taxonomy.unitIdFor(v.get("unit"))
				: (existing ? null : jdbc.queryForObject("select min(id) from unit", Long.class));
		return new ProductInput(blankToNull(v.get("sku")), v.get("name"), v.get("urdu_name"),
				present(v.get("category")) ? taxonomy.categoryIdFor(v.get("category")) : null,
				present(v.get("brand")) ? taxonomy.brandIdFor(v.get("brand")) : null, unitId, null,
				blankToNull(v.get("cost")), blankToNull(v.get("price")), blankToNull(v.get("wholesale_price")),
				blankToNull(v.get("min_price")), blankToNull(v.get("tax_rate")), blankToNull(v.get("reorder_level")),
				blankToNull(v.get("reorder_qty")), null, null, null, v.get("description"),
				present(v.get("barcode")) ? split(v.get("barcode")) : (existing ? null : List.of()), null,
				pharma ? new PharmaFields(v.get("formula"), v.get("strength"), v.get("dosage_form"),
						v.get("pharma_company"), v.get("pack_size"), v.get("schedule"), v.get("rack"),
						v.get("registration_no")) : null,
				part ? new PartFields(v.get("oem_number"), split(v.get("alternate_numbers"))) : null,
				book ? new BookFields(v.get("isbn"), v.get("author"), v.get("publisher"), v.get("edition"),
						v.get("class_name")) : null,
				"Excel import", null, null);
	}

	private List<ParsedRow> parse(byte[] content, List<String> ignored) {
		if (content == null || content.length == 0) {
			throw ApiException.rule("file_empty", "The file is empty");
		}
		boolean xlsx = content.length > 4 && content[0] == 'P' && content[1] == 'K';
		List<List<String>> table = xlsx ? readXlsx(content) : readCsv(content);
		if (table.isEmpty()) {
			throw ApiException.rule("file_empty", "The file has no rows");
		}
		List<String> header = table.getFirst();
		Map<Integer, String> keys = new LinkedHashMap<>();
		for (int i = 0; i < header.size(); i++) {
			String raw = header.get(i) == null ? "" : header.get(i);
			String normal = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
			String key = COLUMNS.get(normal);
			if (key == null) {
				if (!raw.isBlank()) {
					ignored.add(raw.trim());
				}
				continue;
			}
			if ((PHARMA.contains(key) && !features.isOn(ModuleKey.FORMULA))
					|| (PART.contains(key) && !features.isOn(ModuleKey.PART_NUMBERS))
					|| (BOOK.contains(key) && !features.isOn(ModuleKey.BUNDLES))) {
				ignored.add(raw.trim());
				continue;
			}
			keys.put(i, key);
		}
		if (!keys.containsValue("name")) {
			throw ApiException.rule("missing_columns", "The first row must have the column headers (at least Name and Price)."
					+ " Download the template to see them.");
		}
		List<ParsedRow> rows = new ArrayList<>();
		for (int r = 1; r < table.size(); r++) {
			List<String> cells = table.get(r);
			Map<String, String> values = new HashMap<>();
			boolean any = false;
			for (Map.Entry<Integer, String> e : keys.entrySet()) {
				String value = e.getKey() < cells.size() ? cells.get(e.getKey()) : null;
				if (value != null && !value.isBlank()) {
					values.put(e.getValue(), value.trim());
					any = true;
				}
			}
			if (any) {
				rows.add(new ParsedRow(r + 1, values));
			}
			if (rows.size() > MAX_ROWS) {
				throw ApiException.rule("too_many_rows", "Import at most " + MAX_ROWS + " rows at a time");
			}
		}
		if (rows.isEmpty()) {
			throw ApiException.rule("file_empty", "The file has headers but no products");
		}
		return rows;
	}

	private static List<List<String>> readXlsx(byte[] content) {
		DataFormatter formatter = new DataFormatter();
		try (Workbook book = new XSSFWorkbook(new ByteArrayInputStream(content))) {
			Sheet sheet = book.getSheetAt(0);
			List<List<String>> table = new ArrayList<>();
			for (Row row : sheet) {
				List<String> cells = new ArrayList<>();
				for (int c = 0; c < Math.max(0, row.getLastCellNum()); c++) {
					Cell cell = row.getCell(c);
					if (cell == null) {
						cells.add(null);
					}
					else if (cell.getCellType() == CellType.FORMULA) {
						// Formulas are never evaluated (formula injection, SECURITY.md); use the cached result text.
						cells.add(formatter.formatCellValue(cell, null));
					}
					else {
						cells.add(formatter.formatCellValue(cell));
					}
				}
				while (table.size() < row.getRowNum()) {
					table.add(List.of());
				}
				table.add(cells);
			}
			return table;
		}
		catch (IOException | RuntimeException e) {
			throw ApiException.rule("file_unreadable", "This is not a readable Excel (.xlsx) or CSV file");
		}
	}

	private static List<List<String>> readCsv(byte[] content) {
		String text = new String(content, java.nio.charset.StandardCharsets.UTF_8).replace("﻿", "");
		List<List<String>> table = new ArrayList<>();
		for (String line : text.split("\r?\n")) {
			List<String> cells = new ArrayList<>();
			StringBuilder cell = new StringBuilder();
			boolean quoted = false;
			for (int i = 0; i < line.length(); i++) {
				char ch = line.charAt(i);
				if (ch == '"') {
					if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
						cell.append('"');
						i++;
					}
					else {
						quoted = !quoted;
					}
				}
				else if (ch == ',' && !quoted) {
					cells.add(cell.toString());
					cell.setLength(0);
				}
				else {
					cell.append(ch);
				}
			}
			cells.add(cell.toString());
			table.add(cells);
		}
		return table;
	}

	private interface Check {

		void run();
	}

	private static void check(List<RowError> errors, int row, String column, Check check) {
		try {
			check.run();
		}
		catch (ApiException e) {
			errors.add(new RowError(row, column, e.getMessage()));
		}
	}

	private Set<String> lowerNames(String table) {
		return new HashSet<>(jdbc.queryForList("select lower(name) from " + table, String.class));
	}

	private static void note(String value, Set<String> existing, Set<String> created) {
		if (value != null && !value.isBlank() && !existing.contains(value.trim().toLowerCase())) {
			created.add(value.trim());
		}
	}

	private static List<String> split(String value) {
		if (value == null || value.isBlank()) {
			return List.of();
		}
		return Arrays.stream(value.split("[,;|]")).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
	}

	private static boolean present(String v) {
		return v != null && !v.isBlank();
	}

	private static String blankToNull(String v) {
		return present(v) ? v.trim() : null;
	}

	/** Cells starting with = + - @ are prefixed with ' so spreadsheet apps never run them as formulas. */
	static String safeCell(String value) {
		if (value == null) {
			return "";
		}
		return !value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0 && !value.matches("-?\\d+(\\.\\d+)?")
				? "'" + value : value;
	}

	private static byte[] write(Workbook book) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		book.write(out);
		return out.toByteArray();
	}
}
