package com.selectcode.pos.shared.docs;

import java.util.ArrayList;
import java.util.List;

/**
 * A printable table (reports, statements, registers): a title, heading lines (shop, filters, period), columns and
 * rows. {@link PdfWriter} and {@link ExcelWriter} render it, so every report prints and exports the same way
 * (RPT-02: filters in the heading).
 *
 * @param headings lines under the title, e.g. the shop name and "Branch: Main shop · 1–30 Nov 2026"
 * @param footer lines after the table, e.g. totals or signatures
 */
public record TableDoc(String title, List<String> headings, List<Column> columns, List<List<Object>> rows,
		List<Object> totals, List<String> footer) {

	/**
	 * @param kind TEXT, MONEY (string "1250.00" or BigDecimal), QTY, INT, DATE, PERCENT
	 */
	public record Column(String key, String label, String kind) {

		public boolean numeric() {
			return !"TEXT".equals(kind) && !"DATE".equals(kind);
		}
	}

	public static Builder builder(String title) {
		return new Builder(title);
	}

	public static final class Builder {

		private final String title;

		private final List<String> headings = new ArrayList<>();

		private final List<Column> columns = new ArrayList<>();

		private final List<List<Object>> rows = new ArrayList<>();

		private List<Object> totals;

		private final List<String> footer = new ArrayList<>();

		private Builder(String title) {
			this.title = title;
		}

		public Builder heading(String line) {
			if (line != null && !line.isBlank()) {
				headings.add(line);
			}
			return this;
		}

		public Builder column(String key, String label, String kind) {
			columns.add(new Column(key, label, kind));
			return this;
		}

		public Builder row(List<Object> row) {
			rows.add(row);
			return this;
		}

		public Builder rows(List<List<Object>> all) {
			rows.addAll(all);
			return this;
		}

		public Builder totals(List<Object> row) {
			totals = row;
			return this;
		}

		public Builder footer(String line) {
			footer.add(line);
			return this;
		}

		public TableDoc build() {
			return new TableDoc(title, List.copyOf(headings), List.copyOf(columns), rows, totals, List.copyOf(footer));
		}
	}
}
