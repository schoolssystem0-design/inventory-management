package com.selectcode.pos.shared.docs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/**
 * Excel (.xlsx) from one or more {@link TableDoc}s, one sheet each. Uses the streaming workbook so 50,000-row exports
 * stay small in memory. Text cells that start with = + - @ are prefixed with ' so spreadsheets never run them as
 * formulas (formula injection).
 */
public final class ExcelWriter {

	private ExcelWriter() {
	}

	public static byte[] write(TableDoc doc) {
		return write(List.of(doc));
	}

	public static byte[] write(List<TableDoc> docs) {
		try (SXSSFWorkbook workbook = new SXSSFWorkbook(200)) {
			DataFormat format = workbook.createDataFormat();
			CellStyle money = workbook.createCellStyle();
			money.setDataFormat(format.getFormat("#,##0.00"));
			CellStyle qty = workbook.createCellStyle();
			qty.setDataFormat(format.getFormat("#,##0.###"));
			CellStyle date = workbook.createCellStyle();
			date.setDataFormat(format.getFormat("dd-mmm-yyyy"));
			CellStyle bold = workbook.createCellStyle();
			Font font = workbook.createFont();
			font.setBold(true);
			bold.setFont(font);
			for (TableDoc doc : docs) {
				Sheet sheet = workbook.createSheet(sheetName(doc.title()));
				int r = 0;
				Row title = sheet.createRow(r++);
				title.createCell(0).setCellValue(safe(doc.title()));
				title.getCell(0).setCellStyle(bold);
				for (String line : doc.headings()) {
					sheet.createRow(r++).createCell(0).setCellValue(safe(line));
				}
				r++;
				Row head = sheet.createRow(r++);
				for (int c = 0; c < doc.columns().size(); c++) {
					Cell cell = head.createCell(c);
					cell.setCellValue(doc.columns().get(c).label());
					cell.setCellStyle(bold);
				}
				for (List<Object> values : doc.rows()) {
					write(sheet.createRow(r++), doc.columns(), values, money, qty, date);
				}
				if (doc.totals() != null) {
					Row totals = sheet.createRow(r++);
					write(totals, doc.columns(), doc.totals(), money, qty, date);
				}
				for (String line : doc.footer()) {
					sheet.createRow(r++).createCell(0).setCellValue(safe(line));
				}
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			workbook.write(out);
			workbook.close();
			return out.toByteArray();
		}
		catch (IOException e) {
			throw new IllegalStateException("Excel file could not be made", e);
		}
	}

	private static void write(Row row, List<TableDoc.Column> columns, List<Object> values, CellStyle money,
			CellStyle qty, CellStyle date) {
		for (int c = 0; c < columns.size() && c < values.size(); c++) {
			Object value = values.get(c);
			if (value == null) {
				continue;
			}
			Cell cell = row.createCell(c);
			String kind = columns.get(c).kind();
			if (value instanceof LocalDate d) {
				cell.setCellValue(d);
				cell.setCellStyle(date);
			}
			else if (value instanceof Number || (columns.get(c).numeric() && isNumber(value.toString()))) {
				cell.setCellValue(new BigDecimal(value.toString()).doubleValue());
				if ("MONEY".equals(kind)) {
					cell.setCellStyle(money);
				}
				else if ("QTY".equals(kind)) {
					cell.setCellStyle(qty);
				}
			}
			else {
				cell.setCellValue(safe(value.toString()));
			}
		}
	}

	private static boolean isNumber(String text) {
		try {
			new BigDecimal(text);
			return true;
		}
		catch (NumberFormatException e) {
			return false;
		}
	}

	static String safe(String text) {
		return !text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0 ? "'" + text : text;
	}

	private static String sheetName(String title) {
		String name = title.replaceAll("[\\\\/*?:\\[\\]]", " ").trim();
		return name.length() > 31 ? name.substring(0, 31) : name.isEmpty() ? "Sheet" : name;
	}
}
