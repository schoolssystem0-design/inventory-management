package com.selectcode.pos.catalog.app;

import com.selectcode.pos.shared.money.Amounts;
import com.selectcode.pos.shared.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Data for printing barcode labels (CAT-07). The app draws the barcodes (JsBarcode) in the format given here. */
@Service
public class LabelService {

	public record LabelRequestItem(Long productId, Integer copies) {
	}

	public record Label(long productId, String name, String urduName, String sku, String code, String format,
			String price, int copies) {
	}

	public static final int MAX_LABELS = 2000;

	private final JdbcTemplate jdbc;

	LabelService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public List<Label> labels(List<LabelRequestItem> items) {
		if (items == null || items.isEmpty()) {
			throw ApiException.rule("no_products", "Choose at least one product");
		}
		int total = 0;
		List<Label> labels = new ArrayList<>();
		for (LabelRequestItem item : items) {
			if (item.productId() == null) {
				continue;
			}
			int copies = item.copies() == null ? 1 : item.copies();
			if (copies < 1 || copies > 500) {
				throw ApiException.field("copies", "out_of_range", "Print 1 to 500 copies of each product");
			}
			total += copies;
			List<Map<String, Object>> rows = jdbc.queryForList("""
					select p.id, p.name, p.urdu_name, p.sku, p.price,
					       (select barcode from product_barcode b where b.product_id = p.id order by b.id limit 1) as barcode
					from product p where p.id = ?""", item.productId());
			if (rows.isEmpty()) {
				throw ApiException.notFound("Product");
			}
			Map<String, Object> r = rows.getFirst();
			String code = r.get("barcode") == null ? (String) r.get("sku") : (String) r.get("barcode");
			labels.add(new Label(((Number) r.get("id")).longValue(), (String) r.get("name"), (String) r.get("urdu_name"),
					(String) r.get("sku"), code, BarcodeFormats.formatOf(code),
					Amounts.text((java.math.BigDecimal) r.get("price")), copies));
		}
		if (total > MAX_LABELS) {
			throw ApiException.rule("too_many_labels", "Print at most " + MAX_LABELS + " labels at a time");
		}
		return labels;
	}
}
