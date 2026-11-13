package com.selectcode.pos.platform.app;

import com.selectcode.pos.sales.app.DeliveryService;
import com.selectcode.pos.sales.app.DeliveryService.ChallanInput;
import com.selectcode.pos.sales.app.DeliveryService.ChallanLineInput;
import com.selectcode.pos.sales.app.DeliveryService.ConvertChallan;
import com.selectcode.pos.sales.app.DeliveryService.DeliveryInput;
import com.selectcode.pos.sales.app.DeliveryService.DeliveryUpdate;
import com.selectcode.pos.sales.app.InstallmentService;
import com.selectcode.pos.sales.app.InstallmentService.InstallmentPayment;
import com.selectcode.pos.sales.app.InstallmentService.InstallmentPlanInput;
import com.selectcode.pos.sales.app.LoyaltyService;
import com.selectcode.pos.sales.app.LoyaltyService.AdjustInput;
import com.selectcode.pos.sales.app.PrescriptionService.PrescriptionInput;
import com.selectcode.pos.sales.app.PromotionService;
import com.selectcode.pos.sales.app.PromotionService.PromotionInput;
import com.selectcode.pos.sales.app.RepairService;
import com.selectcode.pos.sales.app.RepairService.DeliverInput;
import com.selectcode.pos.sales.app.RepairService.PartInput;
import com.selectcode.pos.sales.app.RepairService.RepairInput;
import com.selectcode.pos.sales.app.RepairService.RepairUpdate;
import com.selectcode.pos.sales.app.SalesService;
import com.selectcode.pos.sales.app.SalesService.PaymentInput;
import com.selectcode.pos.sales.app.SalesService.SaleInput;
import com.selectcode.pos.sales.app.SalesService.SaleLineInput;
import com.selectcode.pos.sales.app.TillService;
import com.selectcode.pos.sales.app.TillService.CloseTill;
import com.selectcode.pos.sales.app.TillService.OpenTill;
import com.selectcode.pos.shared.features.ModuleKey;
import com.selectcode.pos.shared.features.ShopFeatures;
import com.selectcode.pos.shared.time.ShopTime;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Sample data for the Phase 2 modules a demo shop has switched on (offers, loyalty, scale codes, repairs,
 * installments, prescriptions, deliveries and challans), so every screen has something to show. Each part is
 * skipped quietly if it cannot be made (e.g. stock ran out in the random history).
 */
@Service
class DemoModules {

	private static final Logger log = LoggerFactory.getLogger(DemoModules.class);

	private final JdbcTemplate jdbc;

	private final ShopFeatures features;

	private final SalesService sales;

	private final TillService tills;

	private final PromotionService promotions;

	private final LoyaltyService loyalty;

	private final RepairService repairs;

	private final InstallmentService installments;

	private final DeliveryService deliveries;

	private final com.selectcode.pos.sales.app.KitchenService kitchen;

	DemoModules(JdbcTemplate jdbc, ShopFeatures features, SalesService sales, TillService tills,
			PromotionService promotions, LoyaltyService loyalty, RepairService repairs, InstallmentService installments,
			DeliveryService deliveries, com.selectcode.pos.sales.app.KitchenService kitchen) {
		this.kitchen = kitchen;
		this.jdbc = jdbc;
		this.features = features;
		this.sales = sales;
		this.tills = tills;
		this.promotions = promotions;
		this.loyalty = loyalty;
		this.repairs = repairs;
		this.installments = installments;
		this.deliveries = deliveries;
	}

	/** A prescription for demo bills that contain RX or controlled medicines (null otherwise). */
	PrescriptionInput prescriptionFor(List<Long> productIds, Random random) {
		if (!features.isOn(ModuleKey.PRESCRIPTIONS) || productIds.isEmpty()) {
			return null;
		}
		Integer n = jdbc.queryForObject(
				"select count(*) from product_pharma where schedule <> 'OTC' and product_id = any(?)", Integer.class,
				(Object) productIds.toArray(Long[]::new));
		if (n == null || n == 0) {
			return null;
		}
		String[] patients = { "Ayesha Bibi", "Muhammad Aslam", "Hina Tariq", "Zahid Hussain", "Rabia Khan" };
		String[] doctors = { "Dr. Ahmed Raza", "Dr. Sadia Malik", "Dr. Imran Qureshi" };
		return new PrescriptionInput(patients[random.nextInt(patients.length)], String.valueOf(20 + random.nextInt(50)),
				null, doctors[random.nextInt(doctors.length)], "PMC-" + (10000 + random.nextInt(89999)),
				LocalDate.now(ShopTime.ZONE), null, null);
	}

	void seed(long branch, long tillId, List<Long> sellable, List<Long> serials, List<Long> customerIds, Random random) {
		LocalDate today = LocalDate.now(ShopTime.ZONE);
		if (features.isOn(ModuleKey.INSTALLMENTS) && !customerIds.isEmpty()) {
			// An older plan, so the overdue list has something in it.
			ShopTime.asOf(today.minusDays(45), () -> quietly("installments (old)", () -> {
				long session = tills.open(new OpenTill(tillId, "5000")).id();
				installmentSale(session, sellable, serials, customerIds.get(1 % customerIds.size()), today.minusDays(30),
						false);
				tills.close(session, new CloseTill(tills.expectedCash(session).toPlainString(), Map.of(), "Demo"));
			}));
		}
		long session = tills.open(new OpenTill(tillId, "50000")).id();
		if (features.isOn(ModuleKey.SCALE_BARCODES)) {
			quietly("scale codes", () -> {
				int code = 101;
				for (Long id : jdbc.queryForList("select id from product where weighed order by id", Long.class)) {
					jdbc.update("update product set scale_code = ? where id = ?", String.format("%05d", code++), id);
				}
			});
		}
		if (features.isOn(ModuleKey.PROMOTIONS) && sellable.size() >= 2) {
			quietly("offers", () -> {
				promotions.create(new PromotionInput("Buy 2 get 1 free", "BUY_GET", null, "2", "1", null, "PRODUCTS", null,
						List.of(sellable.get(0)), null, null, null, null, null, null, null, true));
				BigDecimal price = jdbc.queryForObject("select price from product where id = ?", BigDecimal.class,
						sellable.get(1));
				promotions.create(new PromotionInput("3 for a special price", "MULTI_PRICE",
						price.multiply(BigDecimal.valueOf(3)).subtract(BigDecimal.valueOf(30)).toPlainString(), "3", null, null,
						"PRODUCTS", null, List.of(sellable.get(1)), null, null, null, null, null, null, null, true));
				promotions.create(new PromotionInput("Weekend 5% off over Rs 3,000", "BILL_PERCENT", "5", null, null, "3000",
						null, null, null, null, null, List.of(6, 7), null, null, null, null, true));
				promotions.create(new PromotionInput("Welcome coupon", "PERCENT", "10", null, null, null, "ALL", null, null,
						null, today.plusMonths(1), null, null, null, "WELCOME10", 100, true));
				List<Long> loose = jdbc.queryForList("select id from product where weighed order by id", Long.class);
				if (!loose.isEmpty()) {
					promotions.create(new PromotionInput("Evening fresh 10% off", "PERCENT", "10", null, null, null,
							"PRODUCTS", null, loose, null, null, null, "17:00", "19:00", null, null, true));
				}
			});
		}
		if (features.isOn(ModuleKey.LOYALTY)) {
			quietly("loyalty", () -> {
				for (int i = 0; i < Math.min(4, customerIds.size()); i++) {
					loyalty.adjust(customerIds.get(i), new AdjustInput(120 + random.nextInt(300), "Welcome points (demo)"));
				}
			});
		}
		if (features.isOn(ModuleKey.REPAIRS)) {
			quietly("repairs", () -> repairs(session, sellable, customerIds));
		}
		if (features.isOn(ModuleKey.INSTALLMENTS) && !customerIds.isEmpty()) {
			quietly("installments", () -> installmentSale(session, sellable, serials, customerIds.get(0),
					today.plusDays(30), true));
		}
		if (features.isOn(ModuleKey.PRESCRIPTIONS)) {
			quietly("controlled medicine", () -> {
				Long med = jdbc.queryForList("""
						select p.id from product p join product_pharma ph on ph.product_id = p.id
						where ph.schedule = 'OTC' and exists (select 1 from product_stock s where s.product_id = p.id and s.qty > 2)
						order by p.id limit 1""", Long.class).stream().findFirst().orElse(null);
				if (med != null) {
					jdbc.update("update product_pharma set schedule = 'CONTROLLED' where product_id = ?", med);
					jdbc.update("update product set name = name || ' (controlled)', updated_at = now() where id = ?", med);
					sell(session, null, List.of(new SaleLineInput(med, null, "1", null, null, null, null, null)), true,
							prescriptionFor(List.of(med), random));
				}
			});
		}
		if (features.isOn(ModuleKey.DELIVERY) && !sellable.isEmpty() && customerIds.size() >= 3) {
			quietly("deliveries", () -> deliveries(session, branch, sellable, customerIds));
		}
		if (features.isOn(ModuleKey.KITCHEN)) {
			quietly("kitchen orders", () -> kitchenOrders(session, customerIds));
		}
		tills.close(session, new CloseTill(tills.expectedCash(session).toPlainString(), Map.of(), "Demo extras"));
	}

	/** Earlier days' orders were served; today a few orders are on the kitchen screen at each stage. */
	private void kitchenOrders(long session, List<Long> customerIds) {
		jdbc.update("""
				update kitchen_ticket set status = 'SERVED', started_at = created_at, ready_at = created_at,
				       served_at = created_at where business_date < ?""", java.sql.Date.valueOf(LocalDate.now(ShopTime.ZONE)));
		List<Long> menu = jdbc.queryForList("select id from product where kind = 'RECIPE' and active order by id", Long.class);
		List<Long> deals = jdbc.queryForList("select id from product where kind = 'BUNDLE' and active order by id", Long.class);
		if (menu.size() < 3 || deals.isEmpty()) {
			return;
		}
		Object[][] orders = { { "DINE_IN", "4", menu.get(0), "No onions", "READY" },
				{ "TAKEAWAY", null, deals.get(0), "Extra spicy", "PREPARING" }, { "DINE_IN", "7", menu.get(1), null, "NEW" },
				{ "DELIVERY", null, menu.get(2), "Sauce on the side", "NEW" } };
		for (Object[] o : orders) {
			long productId = (Long) o[2];
			BigDecimal price = jdbc.queryForObject("select price from product where id = ?", BigDecimal.class, productId);
			var sale = sales.post(new SaleInput(session, "DELIVERY".equals(o[0]) && !customerIds.isEmpty()
					? customerIds.get(0) : null, null, null, null, null,
					List.of(new SaleLineInput(productId, null, "1", null, null, null, null, null, (String) o[3])), null,
					null, List.of(new PaymentInput("CASH", price.toPlainString(), null)), "Demo order", null, null, null,
					(String) o[0], (String) o[1]), UUID.randomUUID().toString(), null);
			Long ticket = jdbc.queryForList("select id from kitchen_ticket where sale_id = ?", Long.class, sale.id())
				.stream().findFirst().orElse(null);
			if (ticket != null && !"NEW".equals(o[4])) {
				kitchen.move(ticket, new com.selectcode.pos.sales.app.KitchenService.StatusInput((String) o[4]));
			}
		}
	}

	private void repairs(long session, List<Long> sellable, List<Long> customerIds) {
		String[][] jobs = { { "Samsung A14", "Screen cracked", "READY" }, { "iPhone 11", "Battery drains fast", "REPAIRING" },
				{ "Infinix Hot 30", "Not charging", "RECEIVED" }, { "Oppo A57", "Speaker not working", "WAITING_PARTS" },
				{ "Vivo Y20", "Water damage", "DELIVERED" } };
		for (int i = 0; i < jobs.length; i++) {
			String[] j = jobs[i];
			long id = repairs.create(new RepairInput(i < customerIds.size() ? customerIds.get(i) : null, "Walk-in " + i,
					"0302" + (1000000 + i * 1111), j[0], i % 2 == 0 ? "35" + (100000000000000L + i * 7919L) : null, j[1],
					"Phone only", "Scratches on back", String.valueOf(1500 + i * 500), i % 2 == 0 ? "500" : null, "CASH",
					session, null, LocalDate.now(ShopTime.ZONE).plusDays(2 - i))).id();
			String status = j[2];
			if ("RECEIVED".equals(status)) {
				continue;
			}
			if (!sellable.isEmpty() && !"WAITING_PARTS".equals(status)) {
				repairs.addPart(id, new PartInput(sellable.get(i % sellable.size()), "1", null));
			}
			String target = "DELIVERED".equals(status) ? "READY" : status;
			repairs.update(id, new RepairUpdate(target, "Checked: " + j[1].toLowerCase(), "800", null, null, null,
					"Customer informed"));
			if ("DELIVERED".equals(status)) {
				BigDecimal balance = new BigDecimal(repairs.get(id).balance());
				repairs.deliver(id, new DeliverInput(session, balance.signum() > 0
						? List.of(new PaymentInput("CASH", balance.toPlainString(), null)) : List.of()),
						UUID.randomUUID().toString(), null);
			}
		}
	}

	private void installmentSale(long session, List<Long> sellable, List<Long> serials, long customer,
			LocalDate firstDue, boolean payFirst) {
		List<SaleLineInput> lines = new ArrayList<>();
		BigDecimal total = BigDecimal.ZERO;
		for (Long phone : serials) {
			String imei = jdbc.queryForList(
					"select serial from serial_unit where product_id = ? and status = 'IN_STOCK' limit 1", String.class, phone)
				.stream().findFirst().orElse(null);
			if (imei != null) {
				lines.add(new SaleLineInput(phone, null, null, null, null, null, null, List.of(imei)));
				total = jdbc.queryForObject("select price from product where id = ?", BigDecimal.class, phone);
				break;
			}
		}
		if (lines.isEmpty() && !sellable.isEmpty()) {
			lines.add(new SaleLineInput(sellable.getFirst(), null, "5", null, null, null, null, null));
			total = jdbc.queryForObject("select price from product where id = ?", BigDecimal.class, sellable.getFirst())
				.multiply(BigDecimal.valueOf(5));
		}
		BigDecimal down = total.multiply(new BigDecimal("0.2")).setScale(0, java.math.RoundingMode.DOWN);
		long saleId = sell(session, customer, lines, false, null,
				List.of(new PaymentInput("CASH", down.toPlainString(), null), new PaymentInput("ACCOUNT", null, null)));
		var plan = installments.create(new InstallmentPlanInput(saleId, 6, "12", firstDue, 5, "50", "Guarantor of demo",
				"35202-1234567-" + (customer % 10), "0300-7654321", "Model Town, Lahore", "Demo plan"));
		if (payFirst) {
			installments.receive(plan.id(), new InstallmentPayment(plan.dues().getFirst().amount(), "CASH", null, session),
					UUID.randomUUID().toString());
		}
	}

	private void deliveries(long session, long branch, List<Long> sellable, List<Long> customerIds) {
		long sold = sell(session, customerIds.get(2),
				List.of(new SaleLineInput(sellable.getFirst(), null, "2", null, null, null, null, null)), true, null);
		deliveries.create(new DeliveryInput(sold, null, null, null, "House 14, Street 7, Johar Town",
				LocalDate.now(ShopTime.ZONE).plusDays(1), "Rafiq", "LES-4521", features.isOn(ModuleKey.SERIALS),
				"Call before coming"));
		var open = deliveries.createChallan(new ChallanInput(branch, customerIds.get(1), null, null, "Site: Plot 22, DHA Phase 6",
				List.of(new ChallanLineInput(sellable.get(sellable.size() > 1 ? 1 : 0), null, "3", null)), "Send with the driver"));
		var delivery = deliveries.create(new DeliveryInput(null, open.id(), null, null, null,
				LocalDate.now(ShopTime.ZONE), "Shahid", "LEB-1180", false, null));
		deliveries.update(delivery.id(), new DeliveryUpdate("ON_THE_WAY", null, null, null, null, null));
		var billed = deliveries.createChallan(new ChallanInput(branch, customerIds.get(0), null, null, "Shop 3, Main Market",
				List.of(new ChallanLineInput(sellable.getFirst(), null, "2", null)), null));
		deliveries.convert(billed.id(), new ConvertChallan(session, List.of(new PaymentInput("ACCOUNT", null, null))),
				UUID.randomUUID().toString(), null);
	}

	private long sell(long session, Long customer, List<SaleLineInput> lines, boolean payExact,
			PrescriptionInput prescription) {
		BigDecimal total = BigDecimal.ZERO;
		for (SaleLineInput l : lines) {
			total = total.add(jdbc.queryForObject("select price from product where id = ?", BigDecimal.class, l.productId())
				.multiply(new BigDecimal(l.qty() == null ? "1" : l.qty())));
		}
		return sell(session, customer, lines, payExact, prescription,
				List.of(new PaymentInput("CASH", total.toPlainString(), null)));
	}

	private long sell(long session, Long customer, List<SaleLineInput> lines, boolean payExact,
			PrescriptionInput prescription, List<PaymentInput> payments) {
		return sales.post(new SaleInput(session, customer, null, null, null, null, lines, null, null, payments,
				"Demo", null, null, prescription), UUID.randomUUID().toString(), null).id();
	}

	private static void quietly(String what, Runnable work) {
		try {
			work.run();
		}
		catch (RuntimeException e) {
			log.info("demo {} skipped: {}", what, e.getMessage());
		}
	}
}
