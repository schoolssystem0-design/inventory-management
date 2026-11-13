package com.selectcode.pos.platform.app;

import com.selectcode.pos.catalog.app.ProductService;
import com.selectcode.pos.catalog.app.ProductService.PackUnitInput;
import com.selectcode.pos.catalog.app.ProductService.PartFields;
import com.selectcode.pos.catalog.app.ProductService.PharmaFields;
import com.selectcode.pos.catalog.app.ProductService.ProductInput;
import com.selectcode.pos.catalog.app.VariantService;
import com.selectcode.pos.catalog.app.VariantService.BundleItemInput;
import com.selectcode.pos.catalog.app.VariantService.VariantGridInput;
import com.selectcode.pos.customers.app.CustomerService;
import com.selectcode.pos.customers.app.CustomerService.CustomerInput;
import com.selectcode.pos.iam.ShopUserPrincipal;
import com.selectcode.pos.purchasing.app.GoodsReceipts;
import com.selectcode.pos.purchasing.app.GoodsReceipts.GrnInput;
import com.selectcode.pos.purchasing.app.GoodsReceipts.GrnLineInput;
import com.selectcode.pos.purchasing.app.PurchaseOrders;
import com.selectcode.pos.purchasing.app.PurchaseOrders.PoInput;
import com.selectcode.pos.purchasing.app.PurchaseOrders.PoLineInput;
import com.selectcode.pos.purchasing.app.SupplierService;
import com.selectcode.pos.purchasing.app.SupplierService.SupplierInput;
import com.selectcode.pos.sales.app.MobileShopService;
import com.selectcode.pos.sales.app.MobileShopService.BuybackInput;
import com.selectcode.pos.sales.app.MobileShopService.WarrantyClaimInput;
import com.selectcode.pos.sales.app.SalesService;
import com.selectcode.pos.sales.app.SalesService.PaymentInput;
import com.selectcode.pos.sales.app.SalesService.SaleInput;
import com.selectcode.pos.sales.app.SalesService.SaleLineInput;
import com.selectcode.pos.sales.app.TillService;
import com.selectcode.pos.sales.app.TillService.CloseTill;
import com.selectcode.pos.sales.app.TillService.OpenTill;
import com.selectcode.pos.shared.files.FilePurpose;
import com.selectcode.pos.shared.files.FileService;
import com.selectcode.pos.shared.security.Permissions;
import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.shared.time.ShopTime;
import com.selectcode.pos.stock.app.StockService.NewSerial;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Sample data for demo shops and QA (OPS-01, QA-TEST-PLAN §3): suppliers with open orders, credit customers, a user
 * for every role, products of the shop type with stock, and days of trading history, all made through the same
 * services the app uses, as the shop's owner. {@link Size#SMALL} is for automated tests, {@link Size#FULL} for the
 * staging demo shops.
 */
@Service
public class DemoSeeder {

	public enum Size {
		SMALL(1, 3), FULL(10, 30);

		final int scale;

		final int days;

		Size(int scale, int days) {
			this.scale = scale;
			this.days = days;
		}
	}

	public record Seeded(int products, int suppliers, int customers, int sales, int users) {
	}

	private static final byte[] PNG = Base64.getDecoder()
		.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

	private final JdbcTemplate jdbc;

	private final ProductService products;

	private final VariantService variants;

	private final SupplierService suppliers;

	private final CustomerService customers;

	private final GoodsReceipts receipts;

	private final PurchaseOrders orders;

	private final TillService tills;

	private final SalesService sales;

	private final MobileShopService mobile;

	private final FileService files;

	private final com.selectcode.pos.stock.app.StockAlerts alerts;

	private final DemoModules modules;

	DemoSeeder(com.selectcode.pos.stock.app.StockAlerts alerts, JdbcTemplate jdbc, ProductService products, VariantService variants, SupplierService suppliers,
			CustomerService customers, GoodsReceipts receipts, PurchaseOrders orders, TillService tills,
			SalesService sales, MobileShopService mobile, FileService files, DemoModules modules) {
		this.modules = modules;
		this.jdbc = jdbc;
		this.products = products;
		this.variants = variants;
		this.suppliers = suppliers;
		this.customers = customers;
		this.receipts = receipts;
		this.orders = orders;
		this.tills = tills;
		this.sales = sales;
		this.mobile = mobile;
		this.files = files;
		this.alerts = alerts;
	}

	/** Seeds a freshly created shop (as its owner). */
	public Seeded seed(long shopId, String typeKey, Size size) {
		return TenantContext.callAsShop(shopId, () -> asOwner(shopId, () -> doSeed(shopId, typeKey, size)));
	}

	private Seeded doSeed(long shopId, String type, Size size) {
		Random random = new Random(shopId);
		long branch = jdbc.queryForObject("select id from branch where is_default", Long.class);
		int users = users();
		List<Long> supplierIds = new ArrayList<>();
		for (String name : List.of("City Distributors", "Prime Traders", "Al-Madina Wholesale", "Star Supplies",
				"Lahore Agency")) {
			supplierIds.add(suppliers.create(new SupplierInput(name, name + " (Pvt) Ltd", "042-3" + (1000000 + random.nextInt(8999999)),
					null, null, "Lahore", 30, null, null, null)).id());
		}
		List<Long> customerIds = new ArrayList<>();
		for (int i = 1; i <= 10; i++) {
			customerIds.add(customers.create(new CustomerInput("Customer " + i, "0300" + (1000000 + random.nextInt(8999999)),
					null, null, "Lahore", "hardware".equals(type) && i <= 3 ? "CONTRACTOR" : "RETAIL", "200000", null, null,
					null)).id());
		}
		// Products and stock for the type.
		List<Long> sellable = new ArrayList<>();
		List<Long> serials = new ArrayList<>();
		switch (type) {
			case "mobile", "electronics" -> {
				int models = "mobile".equals(type) ? 2 * size.scale : size.scale;
				for (int m = 0; m < models; m++) {
					long phone = product(("mobile".equals(type) ? "Phone model " : "LED TV ") + (m + 1), 45000 + m * 1500, 0, "SERIAL",
							null, null, null);
					serials.add(phone);
					List<NewSerial> units = new ArrayList<>();
					for (int u = 0; u < 3 + random.nextInt(3); u++) {
						units.add(new NewSerial(imei(random), null, u % 2 == 0 ? "Black" : "Blue", "128GB",
								u == 0 ? "BOX_OPEN" : "NEW", u == 4 ? "NON_PTA" : "APPROVED"));
					}
					receive(supplierIds.get(m % 5), branch, List.of(new GrnLineInput(phone, null, null, null, null, "40000",
							null, null, null, null, null, null, units)));
				}
				for (int a = 0; a < 5 * size.scale; a++) {
					long acc = product("Accessory " + (a + 1), 500 + a * 20, 0, "STANDARD", null, null, null);
					sellable.add(acc);
					receive(supplierIds.get(a % 5), branch, List.of(line(acc, "50", "300")));
				}
			}
			case "medical" -> {
				long strip = unit("strip");
				long box = unit("box");
				LocalDate today = LocalDate.now(ShopTime.ZONE);
				for (int m = 0; m < 15 * size.scale; m++) {
					long med = productWithUnits("Medicine " + (m + 1) + " 500mg", 30 + m, strip, "BATCH",
							List.of(new PackUnitInput(box, "10", String.valueOf((30 + m) * 10 - 20), null)),
							new PharmaFields("Formula " + (m % 5 + 1), "500mg", "Tablet", "Pharma Co", "10x10",
									m % 7 == 0 ? "RX" : "OTC", "A" + (m % 9), null));
					sellable.add(med);
					receive(supplierIds.get(m % 5), branch, List.of(
							batchLine(med, box, "2", "250", "OLD" + m, today.minusDays(5)),
							batchLine(med, box, "3", "250", "SOON" + m, today.plusDays(45)),
							batchLine(med, box, "5", "260", "NEW" + m, today.plusYears(1))));
				}
			}
			case "super_store" -> {
				long kg = unit("kg");
				long carton = unit("carton");
				long piece = unit("piece");
				for (int i = 0; i < 20 * size.scale; i++) {
					long p = productWithUnits("Grocery item " + (i + 1), 100 + i * 5, piece, "STANDARD",
							i % 4 == 0 ? List.of(new PackUnitInput(carton, "24", String.valueOf((100 + i * 5) * 22), null)) : null,
							null);
					sellable.add(p);
					receive(supplierIds.get(i % 5), branch, List.of(line(p, "60", String.valueOf(80 + i * 4))));
				}
				for (int i = 0; i < 3 * size.scale; i++) {
					long w = productWithUnits("Loose item " + (i + 1) + " (kg)", 180 + i * 10, kg, "STANDARD", null, null);
					jdbc.update("update product set weighed = true where id = ?", w);
					sellable.add(w);
					receive(supplierIds.get(i % 5), branch, List.of(line(w, "50", String.valueOf(140 + i * 8))));
				}
				for (int t = 2; t <= 3; t++) {
					jdbc.update("insert into till (shop_id, branch_id, name) values (?, ?, ?)", TenantContext.requireShopId(),
							branch, "Till " + t);
				}
			}
			case "garments", "cosmetics" -> {
				for (int d = 0; d < 4 * size.scale; d++) {
					long design = product(("garments".equals(type) ? "Kurta design " : "Lipstick ") + (d + 1), 2500 + d * 100, 0,
							"STANDARD", null, null, null);
					var grid = variants.createGrid(design, "garments".equals(type)
							? new VariantGridInput(List.of("S", "M", "L", "XL"), List.of("White", "Black", "Blue"), null, null)
							: new VariantGridInput(null, null, List.of("Red", "Nude", "Pink"), null));
					List<GrnLineInput> lines = new ArrayList<>();
					for (var cell : grid.variants()) {
						sellable.add(cell.productId());
						lines.add(line(cell.productId(), "6", String.valueOf(1500 + d * 50)));
					}
					receive(supplierIds.get(d % 5), branch, lines);
				}
			}
			case "hardware" -> {
				long metre = unit("metre");
				long coil = unit("coil");
				long piece = unit("piece");
				long box = unit("box");
				for (int i = 0; i < 4 * size.scale; i++) {
					long wire = productWithUnits("Wire " + (i + 1) + " (metre)", 95 + i, metre, "STANDARD",
							List.of(new PackUnitInput(coil, "90", String.valueOf((95 + i) * 85), null)), null);
					sellable.add(wire);
					receive(supplierIds.get(i % 5), branch,
							List.of(new GrnLineInput(wire, null, coil, "3", null, String.valueOf(6000 + i * 50), null, null, null,
									null, null, null, null)));
					long screw = productWithUnits("Screw pack " + (i + 1), 15, piece, "STANDARD",
							List.of(new PackUnitInput(box, "100", "1300", null)), null);
					sellable.add(screw);
					receive(supplierIds.get(i % 5), branch, List.of(line(screw, "500", "9")));
				}
			}
			case "fast_food" -> {
				// Ingredients (stock), menu items made from them (recipes), deals and canned drinks.
				long piece = unit("piece");
				long kg = unit("kg");
				long litre = unit("litre");
				Map<String, Long> ing = new LinkedHashMap<>();
				Object[][] ingredients = { { "Burger bun", piece, 25, "400" }, { "Chicken fillet", kg, 900, "40" },
						{ "Beef patty", piece, 120, "200" }, { "Cheese slice", piece, 15, "500" }, { "Lettuce", kg, 200, "10" },
						{ "Potatoes", kg, 120, "80" }, { "Cooking oil", litre, 550, "60" }, { "Pizza dough ball", piece, 60, "150" },
						{ "Mozzarella", kg, 1800, "25" }, { "Pizza sauce", kg, 400, "15" }, { "Shawarma bread", piece, 15, "400" },
						{ "Shawarma chicken", kg, 950, "30" }, { "Garlic sauce", kg, 500, "10" } };
				for (int i = 0; i < ingredients.length; i++) {
					Object[] g = ingredients[i];
					long id = productWithUnits((String) g[0], (Integer) g[2], (Long) g[1], "STANDARD", null, null, null,
							(Integer) g[2]);
					ing.put((String) g[0], id);
					receive(supplierIds.get(i % 5), branch, List.of(line(id, (String) g[3], String.valueOf(g[2]))));
				}
				long cola = productWithUnits("Cola can 345ml", 120, piece, "STANDARD", null, null, null, 80);
				long water = productWithUnits("Mineral water 500ml", 70, piece, "STANDARD", null, null, null, 40);
				receive(supplierIds.get(0), branch, List.of(line(cola, "240", "80"), line(water, "120", "40")));
				Map<String, Long> menu = new LinkedHashMap<>();
				Object[][] recipes = {
						{ "Zinger burger", 550, new Object[][] { { "Burger bun", "1" }, { "Chicken fillet", "0.15" },
								{ "Cheese slice", "1" }, { "Lettuce", "0.02" }, { "Cooking oil", "0.03" } } },
						{ "Beef burger", 650, new Object[][] { { "Burger bun", "1" }, { "Beef patty", "1" },
								{ "Cheese slice", "1" }, { "Lettuce", "0.02" } } },
						{ "Regular fries", 250, new Object[][] { { "Potatoes", "0.2" }, { "Cooking oil", "0.03" } } },
						{ "Chicken shawarma", 350, new Object[][] { { "Shawarma bread", "1" }, { "Shawarma chicken", "0.12" },
								{ "Garlic sauce", "0.03" } } },
						{ "Medium pizza", 1200, new Object[][] { { "Pizza dough ball", "1" }, { "Mozzarella", "0.15" },
								{ "Pizza sauce", "0.08" } } } };
				for (Object[] r : recipes) {
					long id = productWithUnits((String) r[0], (Integer) r[1], piece, "RECIPE", null, null, null, 0);
					List<BundleItemInput> items = new ArrayList<>();
					for (Object[] g : (Object[][]) r[2]) {
						items.add(new BundleItemInput(ing.get((String) g[0]), (String) g[1]));
					}
					variants.setBundle(id, items);
					menu.put((String) r[0], id);
					sellable.add(id);
				}
				long zingerDeal = product("Zinger deal (burger, fries, cola)", 850, 0, "BUNDLE", null, null, null);
				variants.setBundle(zingerDeal, List.of(new BundleItemInput(menu.get("Zinger burger"), "1"),
						new BundleItemInput(menu.get("Regular fries"), "1"), new BundleItemInput(cola, "1")));
				long pizzaDeal = product("Family pizza deal (2 pizzas, 2 colas)", 2400, 0, "BUNDLE", null, null, null);
				variants.setBundle(pizzaDeal, List.of(new BundleItemInput(menu.get("Medium pizza"), "2"),
						new BundleItemInput(cola, "2")));
				sellable.add(zingerDeal);
				sellable.add(pizzaDeal);
				sellable.add(cola);
				sellable.add(water);
				for (int t = 2; t <= 2; t++) {
					jdbc.update("insert into till (shop_id, branch_id, name) values (?, ?, ?)", TenantContext.requireShopId(),
							branch, "Counter " + t);
				}
			}
			case "books" -> {
				List<Long> books = new ArrayList<>();
				for (int i = 0; i < 6 * size.scale; i++) {
					long book = product("Class " + (i % 6 + 1) + " book " + (i + 1), 400 + i * 10, 0, "STANDARD", null, null,
							null);
					books.add(book);
					sellable.add(book);
					receive(supplierIds.get(i % 5), branch, List.of(line(book, "40", String.valueOf(280 + i * 7))));
				}
				long set = product("Class 1 book set", 1100, 0, "BUNDLE", null, null, null);
				variants.setBundle(set, books.subList(0, Math.min(3, books.size())).stream()
					.map(b -> new BundleItemInput(b, "1"))
					.toList());
				sellable.add(set);
			}
			default -> {
				for (int i = 0; i < 10 * size.scale; i++) {
					long p = product(("auto_parts".equals(type) ? "Brake pad " : "Item ") + (i + 1), 300 + i * 15, 0, "STANDARD",
							null, "auto_parts".equals(type) ? new PartFields("OEM-" + (1000 + i), List.of("ALT-" + (2000 + i)))
									: null, null);
					sellable.add(p);
					receive(supplierIds.get(i % 5), branch, List.of(line(p, "40", String.valueOf(200 + i * 10))));
				}
			}
		}
		// Open purchase orders, one per supplier, for items bought into stock (not deals, menu items or services;
		// a fast-food shop buys its ingredients).
		List<Long> buyable = new ArrayList<>();
		for (Long p : sellable) {
			if (List.of("STANDARD", "BATCH", "SERIAL")
				.contains(jdbc.queryForObject("select kind from product where id = ?", String.class, p))) {
				buyable.add(p);
			}
		}
		if ("fast_food".equals(type)) {
			buyable = jdbc.queryForList("select id from product where kind = 'STANDARD' order by id", Long.class);
		}
		if (!buyable.isEmpty()) {
			for (int s = 0; s < supplierIds.size(); s++) {
				long p = buyable.get(s % buyable.size());
				orders.create(new PoInput(supplierIds.get(s), branch, LocalDate.now(ShopTime.ZONE).plusDays(5), null,
						List.of(new PoLineInput(p, null, "10", null))));
			}
		}
		// Trading history.
		long tillId = jdbc.queryForObject("select min(id) from till where branch_id = ?", Long.class, branch);
		int saleCount = 0;
		for (int d = size.days; d >= 1; d--) {
			LocalDate day = LocalDate.now(ShopTime.ZONE).minusDays(d);
			int[] made = { 0 };
			ShopTime.asOf(day, () -> {
				long session = tills.open(new OpenTill(tillId, "5000")).id();
				for (int k = 0; k < 2 + random.nextInt(3); k++) {
					if (sellOne(session, sellable, serials, customerIds, random)) {
						made[0]++;
					}
				}
				tills.close(session, new CloseTill(tills.expectedCash(session).toPlainString(), Map.of(), "Demo day"));
			});
			saleCount += made[0];
		}
		if ("mobile".equals(type) && !serials.isEmpty()) {
			long session = tills.open(new OpenTill(tillId, "300000")).id();
			for (int b = 0; b < 2; b++) {
				long cnic = files.upload(FilePurpose.CNIC_PHOTO, "cnic.png", PNG).id();
				long photo = files.upload(FilePurpose.DEVICE_PHOTO, "phone.png", PNG).id();
				mobile.buy(new BuybackInput(session, serials.get(b % serials.size()),
						new NewSerial(imei(random), null, "Grey", "64GB", "USED", "APPROVED"), "Seller " + (b + 1),
						"35202-" + (1000000 + random.nextInt(8999999)) + "-1", "0301" + (1000000 + random.nextInt(8999999)),
						null, cnic, photo, "30000", "CASH", "Demo buyback"));
			}
			String sold = jdbc.queryForList("select serial from serial_unit where status = 'SOLD' limit 1", String.class)
				.stream()
				.findFirst()
				.orElse(null);
			if (sold != null) {
				mobile.claim(new WarrantyClaimInput(sold, null, null, null, null, "Battery drains fast"));
			}
			tills.close(session, new CloseTill(tills.expectedCash(session).toPlainString(), Map.of(), "Demo buybacks"));
		}
		modules.seed(branch, tillId, sellable, serials, customerIds, random);
		alerts.refresh();
		return new Seeded(jdbc.queryForObject("select count(*) from product", Integer.class), supplierIds.size(),
				customerIds.size(), saleCount, users);
	}

	private boolean sellOne(long session, List<Long> sellable, List<Long> serials, List<Long> customerIds,
			Random random) {
		try {
			List<SaleLineInput> lines = new ArrayList<>();
			if (!serials.isEmpty() && random.nextInt(3) == 0) {
				long phone = serials.get(random.nextInt(serials.size()));
				String imei = jdbc.queryForList(
						"select serial from serial_unit where product_id = ? and status = 'IN_STOCK' limit 1", String.class, phone)
					.stream()
					.findFirst()
					.orElse(null);
				if (imei != null) {
					lines.add(new SaleLineInput(phone, null, null, null, null, null, null, List.of(imei)));
				}
			}
			if (!sellable.isEmpty()) {
				for (int i = 0; i < 1 + random.nextInt(3); i++) {
					lines.add(new SaleLineInput(sellable.get(random.nextInt(sellable.size())), null, "1", null, null, null,
							null, null));
				}
			}
			if (lines.isEmpty()) {
				return false;
			}
			boolean credit = random.nextInt(5) == 0;
			Long customer = credit ? customerIds.get(random.nextInt(customerIds.size())) : null;
			List<PaymentInput> pay = credit ? List.of(new PaymentInput("ACCOUNT", null, null))
					: List.of(new PaymentInput(random.nextBoolean() ? "CASH" : "CARD", "9999999", null));
			if (!credit) {
				// Pay exactly: work out the total with a dry quote of list prices.
				BigDecimal total = BigDecimal.ZERO;
				for (SaleLineInput l : lines) {
					BigDecimal price = jdbc.queryForObject("select price from product where id = ?", BigDecimal.class,
							l.productId());
					total = total.add(price.multiply(new BigDecimal(l.qty() == null ? "1" : l.qty())));
				}
				pay = List.of(new PaymentInput("CASH", total.toPlainString(), null));
			}
			sales.post(new SaleInput(session, customer, null, null, null, null, lines, null, null, pay, null, null, null,
					modules.prescriptionFor(lines.stream().map(SaleLineInput::productId).toList(), random)),
					UUID.randomUUID().toString(), null);
			return true;
		}
		catch (RuntimeException e) {
			return false; // e.g. an item ran out of stock: skip this bill
		}
	}

	private int users() {
		int n = 0;
		for (Map<String, Object> role : jdbc.queryForList("select id, system_key from role where system_key <> 'OWNER'")) {
			String key = ((String) role.get("system_key")).toLowerCase();
			jdbc.update("""
					insert into app_user (shop_id, username, display_name, role_id, password_hash, must_change_password)
					values (?, ?, ?, ?, ?, true) on conflict do nothing""", TenantContext.requireShopId(), key,
					"Demo " + key.replace('_', ' '), role.get("id"), "{noop}" + UUID.randomUUID());
			n++;
		}
		return n;
	}

	private long product(String name, int price, int cost, String kind, List<PackUnitInput> packs, PartFields part,
			PharmaFields pharma) {
		long unit = jdbc.queryForObject("select min(id) from unit", Long.class);
		return productWithUnits(name, price, unit, kind, packs, pharma, part, cost);
	}

	private long productWithUnits(String name, int price, long unitId, String kind, List<PackUnitInput> packs,
			PharmaFields pharma) {
		return productWithUnits(name, price, unitId, kind, packs, pharma, null, 0);
	}

	private long productWithUnits(String name, int price, long unitId, String kind, List<PackUnitInput> packs,
			PharmaFields pharma, PartFields part, int cost) {
		return products.create(new ProductInput(null, name, null, null, null, unitId, kind, String.valueOf(cost),
				String.valueOf(price), null, null, null, "5", "20", null, null, 30, null, null, packs, pharma, part, null,
				null, null, null)).id();
	}

	private long unit(String name) {
		List<Long> ids = jdbc.queryForList("select id from unit where lower(name) = ?", Long.class, name.toLowerCase());
		if (!ids.isEmpty()) {
			return ids.getFirst();
		}
		return jdbc.queryForObject("insert into unit (shop_id, name, decimals_allowed) values (?, ?, ?) returning id",
				Long.class, TenantContext.requireShopId(), name, List.of("kg", "metre", "litre", "foot").contains(name));
	}

	private void receive(long supplier, long branch, List<GrnLineInput> lines) {
		receipts.receive(new GrnInput(supplier, branch, null, "DEMO-" + UUID.randomUUID().toString().substring(0, 6), null,
				null, null, null, null, null, null, null, lines), UUID.randomUUID().toString());
	}

	private static GrnLineInput line(long productId, String qty, String cost) {
		return new GrnLineInput(productId, null, null, qty, null, cost, null, null, null, null, null, null, null);
	}

	private static GrnLineInput batchLine(long productId, long unitId, String qty, String cost, String batch,
			LocalDate expiry) {
		return new GrnLineInput(productId, null, unitId, qty, null, cost, null, batch, expiry, null, null, null, null);
	}

	private static String imei(Random random) {
		StringBuilder s = new StringBuilder("35");
		for (int i = 0; i < 13; i++) {
			s.append(random.nextInt(10));
		}
		return s.toString();
	}

	/** Runs as the shop's owner with every permission (services check permissions as they do for requests). */
	private <T> T asOwner(long shopId, java.util.function.Supplier<T> work) {
		Map<String, Object> owner = jdbc.queryForMap(
				"select id, username, display_name from app_user where is_owner and shop_id = ?", shopId);
		Map<String, Object> shop = jdbc.queryForMap("select code, name from shop where id = ?", shopId);
		ShopUserPrincipal principal = new ShopUserPrincipal(((Number) owner.get("id")).longValue(), shopId,
				(String) shop.get("code"), (String) shop.get("name"), (String) owner.get("username"),
				(String) owner.get("display_name"));
		List<GrantedAuthority> authorities = new ArrayList<>();
		authorities.add(new SimpleGrantedAuthority("ROLE_SHOP_USER"));
		Permissions.all().forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
		var previous = SecurityContextHolder.getContext();
		var context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities));
		SecurityContextHolder.setContext(context);
		try {
			return work.get();
		}
		finally {
			SecurityContextHolder.setContext(previous);
		}
	}
}
