package com.selectcode.pos.sales.app;

import com.selectcode.pos.catalog.app.ProductCatalog;
import com.selectcode.pos.catalog.app.ProductCatalog.ProductRef;
import com.selectcode.pos.customers.app.CustomerService;
import com.selectcode.pos.shared.audit.AuditService;
import com.selectcode.pos.shared.money.Amounts;
import com.selectcode.pos.shared.security.CurrentActor;
import com.selectcode.pos.shared.security.Permissions;
import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.shared.time.ShopTime;
import com.selectcode.pos.shared.web.ApiException;
import com.selectcode.pos.shared.web.PageResponse;
import com.selectcode.pos.shop.app.BranchAccess;
import com.selectcode.pos.shop.app.ShopConfigService;
import com.selectcode.pos.stock.app.StockService;
import com.selectcode.pos.stock.app.StockService.DocRef;
import com.selectcode.pos.stock.app.StockService.Move;
import com.selectcode.pos.stock.app.StockService.MoveType;
import com.selectcode.pos.stock.app.StockService.NewSerial;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mobile and electronics shops: buying used phones from walk-in sellers (MOB-04) and shop warranty claims (MOB-06).
 */
@Service
public class MobileShopService {

	/**
	 * @param cnicFileId photo of the seller's CNIC, {@code itemFileId} photo of the phone (uploaded first through
	 *     /files with purpose CNIC / DEVICE)
	 */
	public record BuybackInput(Long tillSessionId, Long productId, NewSerial unit, String sellerName,
			String sellerCnic, String sellerPhone, String sellerAddress, Long cnicFileId, Long itemFileId,
			String price, String method, String notes) {
	}

	/** The purchase slip the seller signs (thumb or signature). */
	public record BuybackView(long id, String number, long productId, String productName, String serial,
			String serial2, String condition, String sellerName, String sellerCnic, String sellerPhone,
			String sellerAddress, Long cnicFileId, Long itemFileId, String price, String method, String notes,
			String branchName, String actorName, LocalDate date, Instant at) {
	}

	public record WarrantyClaimInput(String serial, Long saleId, Long productId, String customerName,
			String customerPhone, String issue) {
	}

	public record WarrantyUpdate(String status, String resolution) {
	}

	public record WarrantyClaimView(long id, String number, Long serialUnitId, String serial, long productId,
			String productName, Long saleId, String saleNumber, String customerName, String customerPhone,
			String issue, String status, String resolution, boolean inWarranty, LocalDate warrantyUntil,
			String actorName, Instant at, Instant closedAt) {
	}

	private final JdbcTemplate jdbc;

	private final StockService stock;

	private final ProductCatalog catalog;

	private final TillService tills;

	private final BranchAccess branches;

	private final ShopConfigService numbering;

	private final AuditService audit;

	private final ShopTime time;

	MobileShopService(JdbcTemplate jdbc, StockService stock, ProductCatalog catalog, TillService tills,
			BranchAccess branches, ShopConfigService numbering, AuditService audit, ShopTime time) {
		this.jdbc = jdbc;
		this.stock = stock;
		this.catalog = catalog;
		this.tills = tills;
		this.branches = branches;
		this.numbering = numbering;
		this.audit = audit;
		this.time = time;
	}

	// ------------------------------------------------------------------ buyback

	@Transactional
	public BuybackView buy(BuybackInput in) {
		String name = in.sellerName() == null ? "" : in.sellerName().trim();
		if (name.isEmpty()) {
			throw ApiException.field("sellerName", "required", "Enter the seller's name");
		}
		String cnicDigits = in.sellerCnic() == null ? "" : in.sellerCnic().replaceAll("[^0-9]", "");
		if (cnicDigits.length() != 13) {
			throw ApiException.field("sellerCnic", "required", "Enter the seller's CNIC (13 digits)");
		}
		String cnic = cnicDigits.substring(0, 5) + "-" + cnicDigits.substring(5, 12) + "-" + cnicDigits.substring(12);
		if (in.cnicFileId() == null) {
			throw ApiException.field("cnicFileId", "required", "Take a photo of the seller's CNIC");
		}
		if (in.itemFileId() == null) {
			throw ApiException.field("itemFileId", "required", "Take a photo of the phone");
		}
		for (Long file : List.of(in.cnicFileId(), in.itemFileId())) {
			Integer ok = jdbc.queryForObject("select count(*) from stored_file where id = ?", Integer.class, file);
			if (ok == null || ok == 0) {
				throw ApiException.notFound("Photo");
			}
		}
		if (in.productId() == null) {
			throw ApiException.field("productId", "required", "Choose the model");
		}
		ProductRef product = catalog.require(in.productId());
		if (!product.serial()) {
			throw ApiException.rule("serial_required", "Used phones are bought as IMEI items; choose an IMEI model");
		}
		if (in.unit() == null) {
			throw ApiException.field("unit.serial", "required", "Scan the IMEI");
		}
		BigDecimal price = Amounts.requireMoney(in.price(), "price");
		if (price.signum() <= 0) {
			throw ApiException.field("price", "invalid", "Enter the price paid");
		}
		String method = in.method() == null || in.method().isBlank() ? "CASH" : in.method().trim().toUpperCase();
		if (!Set.of("CASH", "BANK", "JAZZCASH", "EASYPAISA").contains(method)) {
			throw ApiException.field("method", "invalid", "Pay by cash, bank or a wallet");
		}
		Map<String, Object> session = tills.requireOpenSession(in.tillSessionId(), null);
		long branchId = ((Number) session.get("branch_id")).longValue();
		long sessionId = ((Number) session.get("id")).longValue();
		if ("CASH".equals(method) && tills.expectedCash(sessionId).compareTo(price) < 0) {
			throw ApiException.rule("not_enough_cash", "There is not enough cash in the till to pay the seller");
		}
		String number = numbering.nextNumber("BUYBACK", branchId);
		NewSerial unit = new NewSerial(in.unit().serial(), in.unit().serial2(), in.unit().colour(), in.unit().storage(),
				in.unit().condition() == null || in.unit().condition().isBlank() ? "USED" : in.unit().condition(),
				in.unit().ptaStatus());
		long id = jdbc.queryForObject("""
				insert into buyback (shop_id, number, branch_id, till_session_id, product_id, seller_name, seller_cnic,
				                     seller_phone, seller_address, cnic_file_id, item_file_id, price, method_key, notes,
				                     created_by, actor_name, business_date)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class,
				TenantContext.requireShopId(), number, branchId, sessionId, product.id(), name, cnic,
				SalesService.trim(in.sellerPhone(), 30), SalesService.trim(in.sellerAddress(), 300), in.cnicFileId(),
				in.itemFileId(), price, method, SalesService.trim(in.notes(), 500), TillService.userId(),
				CurrentActor.name(), Date.valueOf(time.today()));
		stock.averageIn(product.id(), BigDecimal.ONE, price);
		List<Long> serialIds = stock.createSerials(product.id(), branchId, List.of(unit), price, "BUYBACK", null, null, id);
		stock.apply(MoveType.BUYBACK, new DocRef("BUYBACK", id, number),
				List.of(new Move(product.id(), branchId, null, serialIds.getFirst(), BigDecimal.ONE, price, "Used")));
		audit.record("BUYBACK", "buyback", id, null, Map.of("number", number, "price", price.toPlainString()));
		return buyback(id);
	}

	@Transactional(readOnly = true)
	public BuybackView buyback(long id) {
		boolean pii = CurrentActor.hasPermission(Permissions.CUSTOMERS_PII);
		boolean cost = CurrentActor.hasPermission(Permissions.COST_VIEW)
				|| CurrentActor.hasPermission(Permissions.BUYBACK_MANAGE);
		return jdbc.query("""
				select bb.*, p.name as product_name, s.serial, s.serial2, s.condition, b.name as branch_name
				from buyback bb join product p on p.id = bb.product_id join branch b on b.id = bb.branch_id
				left join serial_unit s on s.buyback_id = bb.id where bb.id = ?""" + branches.filter("bb.branch_id"),
				(rs, i) -> new BuybackView(rs.getLong("id"), rs.getString("number"), rs.getLong("product_id"),
						rs.getString("product_name"), rs.getString("serial"), rs.getString("serial2"),
						rs.getString("condition"), rs.getString("seller_name"),
						pii ? rs.getString("seller_cnic") : CustomerService.maskCnic(rs.getString("seller_cnic")),
						pii ? rs.getString("seller_phone") : CustomerService.maskPhone(rs.getString("seller_phone")),
						pii ? rs.getString("seller_address") : null, pii ? (Long) rs.getObject("cnic_file_id") : null,
						(Long) rs.getObject("item_file_id"), cost ? Amounts.text(rs.getBigDecimal("price")) : null,
						rs.getString("method_key"), rs.getString("notes"), rs.getString("branch_name"),
						rs.getString("actor_name"), rs.getObject("business_date", LocalDate.class),
						rs.getTimestamp("at").toInstant()),
				id).stream().findFirst().orElseThrow(() -> ApiException.notFound("Purchase from customer"));
	}

	@Transactional(readOnly = true)
	public PageResponse<BuybackView> buybacks(LocalDate from, LocalDate to, int page, int size) {
		StringBuilder where = new StringBuilder(" where 1=1" + branches.filter("branch_id"));
		List<Object> args = new ArrayList<>();
		if (from != null) {
			where.append(" and business_date >= ?");
			args.add(Date.valueOf(from));
		}
		if (to != null) {
			where.append(" and business_date <= ?");
			args.add(Date.valueOf(to));
		}
		long total = jdbc.queryForObject("select count(*) from buyback" + where, Long.class, args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(size);
		pageArgs.add((long) page * size);
		List<Long> ids = jdbc.queryForList("select id from buyback" + where + " order by id desc limit ? offset ?",
				Long.class, pageArgs.toArray());
		return new PageResponse<>(ids.stream().map(this::buyback).toList(), page, size, total);
	}

	// ------------------------------------------------------------------ warranty

	@Transactional
	public WarrantyClaimView claim(WarrantyClaimInput in) {
		if (in.issue() == null || in.issue().isBlank()) {
			throw ApiException.field("issue", "required", "Describe the fault");
		}
		Long serialId = null;
		Long saleId = in.saleId();
		Long productId = in.productId();
		Long customerId = null;
		String customerName = SalesService.trim(in.customerName(), 200);
		String customerPhone = SalesService.trim(in.customerPhone(), 30);
		LocalDate warrantyUntil = null;
		if (in.serial() != null && !in.serial().isBlank()) {
			Map<String, Object> unit = jdbc.queryForList("""
					select * from serial_unit where status <> 'VOID' and (upper(serial) = ? or upper(serial2) = ?)""",
					in.serial().trim().toUpperCase(), in.serial().trim().toUpperCase()).stream().findFirst()
				.orElseThrow(() -> ApiException.notFound("IMEI/serial " + in.serial().trim()));
			serialId = ((Number) unit.get("id")).longValue();
			productId = ((Number) unit.get("product_id")).longValue();
			if (unit.get("sale_id") != null) {
				saleId = ((Number) unit.get("sale_id")).longValue();
			}
			customerId = unit.get("customer_id") == null ? null : Long.valueOf(((Number) unit.get("customer_id")).longValue());
			warrantyUntil = unit.get("warranty_until") == null ? null : ((Date) unit.get("warranty_until")).toLocalDate();
		}
		if (saleId != null) {
			Map<String, Object> sale = jdbc.queryForList("select * from sale where id = ?", saleId)
				.stream()
				.findFirst()
				.orElseThrow(() -> ApiException.notFound("Sale"));
			if (customerId == null && sale.get("customer_id") != null) {
				customerId = ((Number) sale.get("customer_id")).longValue();
			}
			customerName = customerName != null ? customerName : (String) sale.get("customer_name");
			customerPhone = customerPhone != null ? customerPhone : (String) sale.get("customer_phone");
			if (warrantyUntil == null && productId != null) {
				ProductRef p = catalog.require(productId);
				LocalDate sold = ((Date) sale.get("business_date")).toLocalDate();
				warrantyUntil = p.warrantyDays() > 0 ? sold.plusDays(p.warrantyDays()) : null;
			}
		}
		if (productId == null) {
			throw ApiException.field("productId", "required", "Scan the IMEI or choose the product");
		}
		catalog.require(productId);
		boolean inWarranty = warrantyUntil != null && !warrantyUntil.isBefore(time.today());
		String number = numbering.nextNumber("WARRANTY_CLAIM", null);
		long id = jdbc.queryForObject("""
				insert into warranty_claim (shop_id, number, serial_unit_id, sale_id, product_id, customer_id, customer_name,
				                            customer_phone, issue, in_warranty, created_by, actor_name)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, TenantContext.requireShopId(),
				number, serialId, saleId, productId, customerId, customerName, customerPhone,
				in.issue().trim().length() > 500 ? in.issue().trim().substring(0, 500) : in.issue().trim(), inWarranty,
				TillService.userId(), CurrentActor.name());
		audit.record("WARRANTY_CLAIM", "warranty_claim", id, null, Map.of("number", number, "inWarranty", inWarranty));
		return warranty(id);
	}

	@Transactional
	public WarrantyClaimView updateClaim(long id, WarrantyUpdate in) {
		warranty(id);
		String status = in.status() == null ? null : in.status().trim().toUpperCase();
		if (status != null && !Set.of("OPEN", "SENT", "REPAIRED", "REPLACED", "REJECTED", "CLOSED").contains(status)) {
			throw ApiException.field("status", "invalid", "Status is open, sent, repaired, replaced, rejected or closed");
		}
		jdbc.update("""
				update warranty_claim set status = coalesce(?, status), resolution = coalesce(?, resolution),
				       closed_at = case when ? in ('CLOSED', 'REJECTED', 'REPLACED') then now() else closed_at end,
				       version = version + 1 where id = ?""", status, SalesService.trim(in.resolution(), 500), status, id);
		audit.record("WARRANTY_UPDATED", "warranty_claim", id, null, Map.of("status", String.valueOf(status)));
		return warranty(id);
	}

	@Transactional(readOnly = true)
	public WarrantyClaimView warranty(long id) {
		return jdbc.query("""
				select w.*, p.name as product_name, s.serial, s.warranty_until, sl.number as sale_number
				from warranty_claim w join product p on p.id = w.product_id left join serial_unit s on s.id = w.serial_unit_id
				left join sale sl on sl.id = w.sale_id where w.id = ?""",
				(rs, i) -> new WarrantyClaimView(rs.getLong("id"), rs.getString("number"),
						(Long) rs.getObject("serial_unit_id"), rs.getString("serial"), rs.getLong("product_id"),
						rs.getString("product_name"), (Long) rs.getObject("sale_id"), rs.getString("sale_number"),
						rs.getString("customer_name"), rs.getString("customer_phone"), rs.getString("issue"),
						rs.getString("status"), rs.getString("resolution"), rs.getBoolean("in_warranty"),
						rs.getObject("warranty_until", LocalDate.class), rs.getString("actor_name"),
						rs.getTimestamp("at").toInstant(),
						rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toInstant()),
				id).stream().findFirst().orElseThrow(() -> ApiException.notFound("Warranty claim"));
	}

	@Transactional(readOnly = true)
	public PageResponse<WarrantyClaimView> warrantyClaims(String status, int page, int size) {
		String where = status == null || status.isBlank() ? "" : " where status = ?";
		Object[] args = status == null || status.isBlank() ? new Object[0] : new Object[] { status.trim().toUpperCase() };
		long total = jdbc.queryForObject("select count(*) from warranty_claim" + where, Long.class, args);
		List<Object> pageArgs = new ArrayList<>(List.of(args));
		pageArgs.add(size);
		pageArgs.add((long) page * size);
		List<Long> ids = jdbc.queryForList("select id from warranty_claim" + where + " order by id desc limit ? offset ?",
				Long.class, pageArgs.toArray());
		return new PageResponse<>(ids.stream().map(this::warranty).toList(), page, size, total);
	}

	@SuppressWarnings("unused")
	private static Timestamp ts(Instant at) {
		return Timestamp.from(at);
	}
}
