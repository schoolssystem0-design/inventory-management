package com.selectcode.pos.sales.app;

import com.selectcode.pos.shared.features.ModuleKey;
import com.selectcode.pos.shared.features.ShopFeatures;
import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.shared.time.ShopTime;
import com.selectcode.pos.shared.web.ApiException;
import com.selectcode.pos.shared.web.PageResponse;
import com.selectcode.pos.shop.app.BranchAccess;
import com.selectcode.pos.shop.app.ShopConfigService;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prescriptions on sales (MED-08) and the controlled-medicine register (MED-09). Medicines are OTC, RX or CONTROLLED
 * ({@code product_pharma.schedule}). RX needs a prescription when the shop's {@code prescription_required} is on;
 * CONTROLLED always needs one plus a pharmacist's approval, and each sale and return is written into the register.
 */
@Service
public class PrescriptionService {

	/** @param fileId photo of the prescription (uploaded first through /files with purpose PRESCRIPTION) */
	public record PrescriptionInput(String patientName, String patientAge, String patientPhone, String doctorName,
			String doctorRegNo, LocalDate issuedOn, Long fileId, String notes) {
	}

	public record PrescriptionView(long id, String number, Long saleId, String saleNumber, String patientName,
			String patientAge, String patientPhone, String doctorName, String doctorRegNo, LocalDate issuedOn,
			Long fileId, String notes, String actorName, LocalDate date, Instant at) {
	}

	public record RegisterEntry(long id, LocalDate date, long saleId, String saleNumber, Long returnId,
			String productName, String batchNo, String qty, String unitName, String patientName, String doctorName,
			String prescriptionNumber, String pharmacistName, Instant at) {
	}

	/** Schedules of the medicines on a bill. */
	public record Schedules(Map<Long, String> byProduct) {

		public boolean any(String schedule) {
			return byProduct.containsValue(schedule);
		}

		public List<Long> products(String schedule) {
			return byProduct.entrySet().stream().filter(e -> schedule.equals(e.getValue())).map(Map.Entry::getKey).toList();
		}
	}

	private final JdbcTemplate jdbc;

	private final ShopFeatures features;

	private final ShopConfigService numbering;

	private final BranchAccess branches;

	private final ShopTime time;

	PrescriptionService(JdbcTemplate jdbc, ShopFeatures features, ShopConfigService numbering, BranchAccess branches,
			ShopTime time) {
		this.jdbc = jdbc;
		this.features = features;
		this.numbering = numbering;
		this.branches = branches;
		this.time = time;
	}

	/** RX and CONTROLLED medicines among the products (empty when the module is off). */
	@Transactional(propagation = Propagation.MANDATORY)
	Schedules schedules(Collection<Long> productIds) {
		Map<Long, String> map = new LinkedHashMap<>();
		if (!features.isOn(ModuleKey.PRESCRIPTIONS) || productIds.isEmpty()) {
			return new Schedules(map);
		}
		jdbc.query("select product_id, schedule from product_pharma where product_id = any(?) and schedule <> 'OTC'",
				rs -> {
					map.put(rs.getLong("product_id"), rs.getString("schedule"));
				}, (Object) productIds.toArray(Long[]::new));
		return new Schedules(map);
	}

	boolean required() {
		return Boolean.TRUE.equals(jdbc.queryForObject("select prescription_required from shop_settings where shop_id = ?",
				Boolean.class, TenantContext.requireShopId()));
	}

	/** Checks a prescription before anything is saved; null when none was given. */
	static void check(PrescriptionInput in) {
		if (in == null) {
			return;
		}
		if (in.patientName() == null || in.patientName().isBlank()) {
			throw ApiException.field("prescription.patientName", "required", "Enter the patient's name");
		}
		if (in.doctorName() == null || in.doctorName().isBlank()) {
			throw ApiException.field("prescription.doctorName", "required", "Enter the doctor's name");
		}
	}

	@Transactional(propagation = Propagation.MANDATORY)
	long create(PrescriptionInput in, long saleId, Long userId, String actor) {
		if (in.fileId() != null) {
			Integer ok = jdbc.queryForObject("select count(*) from stored_file where id = ?", Integer.class, in.fileId());
			if (ok == null || ok == 0) {
				throw ApiException.notFound("Prescription photo");
			}
		}
		String number = numbering.nextNumber("PRESCRIPTION", null);
		return jdbc.queryForObject("""
				insert into prescription (shop_id, number, sale_id, patient_name, patient_age, patient_phone, doctor_name,
				                          doctor_reg_no, issued_on, file_id, notes, created_by, actor_name, business_date)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class,
				TenantContext.requireShopId(), number, saleId, SalesService.trim(in.patientName(), 200),
				SalesService.trim(in.patientAge(), 20), SalesService.trim(in.patientPhone(), 30),
				SalesService.trim(in.doctorName(), 200), SalesService.trim(in.doctorRegNo(), 40),
				in.issuedOn() == null ? null : Date.valueOf(in.issuedOn()), in.fileId(),
				SalesService.trim(in.notes(), 500), userId, actor, Date.valueOf(time.today()));
	}

	/** Register rows for the controlled lines of a posted sale. */
	@Transactional(propagation = Propagation.MANDATORY)
	void registerSale(long saleId, List<Long> controlled, long prescriptionId, Long pharmacistId) {
		if (controlled.isEmpty()) {
			return;
		}
		String pharmacist = pharmacistId == null ? null
				: jdbc.queryForList("select display_name from app_user where id = ?", String.class, pharmacistId).stream()
					.findFirst().orElse(null);
		jdbc.update("""
				insert into controlled_register (shop_id, branch_id, sale_id, sale_number, product_id, product_name, batch_no,
				                                 qty, unit_name, patient_name, doctor_name, prescription_id, pharmacist_id,
				                                 pharmacist_name, business_date)
				select l.shop_id, s.branch_id, s.id, s.number, l.product_id, l.product_name, b.batch_no, l.qty, l.unit_name,
				       p.patient_name, p.doctor_name, p.id, ?, ?, s.business_date
				from sale_line l join sale s on s.id = l.sale_id join prescription p on p.id = ?
				left join batch b on b.id = l.batch_id
				where l.sale_id = ? and l.product_id = any(?) order by l.line_no""", pharmacistId, pharmacist,
				prescriptionId, saleId, (Object) controlled.toArray(Long[]::new));
	}

	/** Returned controlled medicines come off the register (negative rows); {@code lines}: sale line id → qty. */
	@Transactional(propagation = Propagation.MANDATORY)
	void registerReturn(long saleId, Long returnId, Map<Long, BigDecimal> lines) {
		for (Map.Entry<Long, BigDecimal> e : lines.entrySet()) {
			jdbc.update("""
					insert into controlled_register (shop_id, branch_id, sale_id, sale_number, return_id, product_id,
					                                 product_name, batch_no, qty, unit_name, patient_name, doctor_name,
					                                 prescription_id, pharmacist_id, pharmacist_name, business_date)
					select r.shop_id, r.branch_id, r.sale_id, r.sale_number, ?, r.product_id, r.product_name, r.batch_no, ?,
					       r.unit_name, r.patient_name, r.doctor_name, r.prescription_id, r.pharmacist_id, r.pharmacist_name, ?
					from controlled_register r join sale_line l on l.sale_id = r.sale_id and l.product_id = r.product_id
					left join batch b on b.id = l.batch_id
					where r.sale_id = ? and l.id = ? and r.return_id is null and r.qty > 0
					  and r.batch_no is not distinct from b.batch_no
					order by r.id limit 1""", returnId, e.getValue().negate(), Date.valueOf(time.today()), saleId, e.getKey());
		}
	}

	/** A cancelled sale: every controlled line comes off the register. */
	@Transactional(propagation = Propagation.MANDATORY)
	void registerCancel(long saleId) {
		jdbc.update("""
				insert into controlled_register (shop_id, branch_id, sale_id, sale_number, product_id, product_name, batch_no,
				                                 qty, unit_name, patient_name, doctor_name, prescription_id, pharmacist_id,
				                                 pharmacist_name, business_date)
				select shop_id, branch_id, sale_id, sale_number, product_id, product_name, batch_no, -qty, unit_name,
				       patient_name, doctor_name, prescription_id, pharmacist_id, pharmacist_name, ?
				from controlled_register where sale_id = ? and return_id is null and qty > 0""",
				Date.valueOf(time.today()), saleId);
	}

	// ------------------------------------------------------------------ screens

	@Transactional(readOnly = true)
	public PrescriptionView get(long id) {
		// Patient phone is personal data: shown to pharmacists and users who may see personal details.
		boolean pii = com.selectcode.pos.shared.security.CurrentActor
			.hasPermission(com.selectcode.pos.shared.security.Permissions.CUSTOMERS_PII)
				|| com.selectcode.pos.shared.security.CurrentActor
					.hasPermission(com.selectcode.pos.shared.security.Permissions.PRESCRIPTIONS_APPROVE);
		return jdbc.query("""
				select p.*, s.number as sale_number from prescription p left join sale s on s.id = p.sale_id where p.id = ?""",
				(rs, i) -> new PrescriptionView(rs.getLong("id"), rs.getString("number"), (Long) rs.getObject("sale_id"),
						rs.getString("sale_number"), rs.getString("patient_name"), rs.getString("patient_age"),
						pii ? rs.getString("patient_phone")
								: com.selectcode.pos.customers.app.CustomerService.maskPhone(rs.getString("patient_phone")),
						rs.getString("doctor_name"), rs.getString("doctor_reg_no"),
						rs.getObject("issued_on", LocalDate.class), (Long) rs.getObject("file_id"), rs.getString("notes"),
						rs.getString("actor_name"), rs.getObject("business_date", LocalDate.class),
						rs.getTimestamp("at").toInstant()),
				id).stream().findFirst().orElseThrow(() -> ApiException.notFound("Prescription"));
	}

	@Transactional(readOnly = true)
	public PageResponse<PrescriptionView> list(LocalDate from, LocalDate to, String q, int page, int size) {
		StringBuilder where = new StringBuilder(" where 1=1");
		List<Object> args = new ArrayList<>();
		if (from != null) {
			where.append(" and business_date >= ?");
			args.add(Date.valueOf(from));
		}
		if (to != null) {
			where.append(" and business_date <= ?");
			args.add(Date.valueOf(to));
		}
		if (q != null && !q.isBlank()) {
			where.append(" and (lower(patient_name) like ? or lower(doctor_name) like ? or upper(number) = ?)");
			String like = "%" + q.trim().toLowerCase() + "%";
			args.add(like);
			args.add(like);
			args.add(q.trim().toUpperCase());
		}
		long total = jdbc.queryForObject("select count(*) from prescription" + where, Long.class, args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(size);
		pageArgs.add((long) page * size);
		List<Long> ids = jdbc.queryForList("select id from prescription" + where + " order by id desc limit ? offset ?",
				Long.class, pageArgs.toArray());
		return new PageResponse<>(ids.stream().map(this::get).toList(), page, size, total);
	}

	@Transactional(readOnly = true)
	public List<RegisterEntry> register(LocalDate from, LocalDate to, Long productId, Long branchId) {
		StringBuilder where = new StringBuilder(" where r.business_date >= ? and r.business_date <= ?"
				+ branches.filter("r.branch_id"));
		List<Object> args = new ArrayList<>(List.of(Date.valueOf(from), Date.valueOf(to)));
		if (productId != null) {
			where.append(" and r.product_id = ?");
			args.add(productId);
		}
		if (branchId != null) {
			where.append(" and r.branch_id = ?");
			args.add(branchId);
		}
		return jdbc.query("""
				select r.*, p.number as rx_number from controlled_register r left join prescription p on p.id = r.prescription_id
				""" + where + " order by r.business_date, r.id limit 5000",
				(rs, i) -> new RegisterEntry(rs.getLong("id"), rs.getObject("business_date", LocalDate.class),
						rs.getLong("sale_id"), rs.getString("sale_number"), (Long) rs.getObject("return_id"),
						rs.getString("product_name"), rs.getString("batch_no"),
						rs.getBigDecimal("qty").stripTrailingZeros().toPlainString(), rs.getString("unit_name"),
						rs.getString("patient_name"), rs.getString("doctor_name"), rs.getString("rx_number"),
						rs.getString("pharmacist_name"), rs.getTimestamp("at").toInstant()),
				args.toArray());
	}
}
