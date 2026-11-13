package com.selectcode.pos.shared.docs;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * A4 PDFs from a {@link TableDoc} (docs/ARCHITECTURE.md §9): statements, purchase orders, invoices and reports.
 * Landscape when there are many columns. Money is right-aligned with thousands separators.
 */
public final class PdfWriter {

	private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);

	private static final Font HEADING = FontFactory.getFont(FontFactory.HELVETICA, 9, new Color(80, 80, 80));

	private static final Font HEAD_CELL = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);

	private static final Font CELL = FontFactory.getFont(FontFactory.HELVETICA, 8);

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy");

	private PdfWriter() {
	}

	public static byte[] write(TableDoc doc) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		Rectangle size = doc.columns().size() > 7 ? PageSize.A4.rotate() : PageSize.A4;
		Document pdf = new Document(size, 28, 28, 28, 28);
		try {
			com.lowagie.text.pdf.PdfWriter.getInstance(pdf, out);
			pdf.addTitle(doc.title());
			pdf.open();
			pdf.add(new Paragraph(doc.title(), TITLE));
			for (String line : doc.headings()) {
				pdf.add(new Paragraph(line, HEADING));
			}
			pdf.add(new Paragraph(" "));
			if (!doc.columns().isEmpty()) {
				PdfPTable table = new PdfPTable(doc.columns().size());
				table.setWidthPercentage(100);
				table.setHeaderRows(1);
				for (TableDoc.Column column : doc.columns()) {
					PdfPCell cell = new PdfPCell(new Phrase(column.label(), HEAD_CELL));
					cell.setBackgroundColor(new Color(235, 235, 235));
					cell.setHorizontalAlignment(column.numeric() ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
					table.addCell(cell);
				}
				for (List<Object> row : doc.rows()) {
					addRow(table, doc.columns(), row, CELL);
				}
				if (doc.totals() != null) {
					addRow(table, doc.columns(), doc.totals(), HEAD_CELL);
				}
				pdf.add(table);
			}
			for (String line : doc.footer()) {
				pdf.add(new Paragraph(line, HEADING));
			}
			pdf.close();
		}
		catch (DocumentException e) {
			throw new IllegalStateException("PDF could not be made", e);
		}
		return out.toByteArray();
	}

	private static void addRow(PdfPTable table, List<TableDoc.Column> columns, List<Object> row, Font font) {
		for (int i = 0; i < columns.size(); i++) {
			Object value = i < row.size() ? row.get(i) : null;
			PdfPCell cell = new PdfPCell(new Phrase(text(columns.get(i), value), font));
			cell.setHorizontalAlignment(columns.get(i).numeric() ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
			table.addCell(cell);
		}
	}

	static String text(TableDoc.Column column, Object value) {
		if (value == null) {
			return "";
		}
		if (value instanceof LocalDate d) {
			return DATE.format(d);
		}
		if ("MONEY".equals(column.kind())) {
			BigDecimal amount = value instanceof BigDecimal b ? b : new BigDecimal(value.toString());
			return String.format("%,.2f", amount);
		}
		if ("QTY".equals(column.kind())) {
			BigDecimal qty = value instanceof BigDecimal b ? b : new BigDecimal(value.toString());
			return qty.stripTrailingZeros().toPlainString();
		}
		return value.toString();
	}
}
