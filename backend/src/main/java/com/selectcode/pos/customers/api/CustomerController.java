package com.selectcode.pos.customers.api;

import com.selectcode.pos.customers.app.CustomerService;
import com.selectcode.pos.customers.app.CustomerService.CustomerInput;
import com.selectcode.pos.customers.app.CustomerService.CustomerListItem;
import com.selectcode.pos.customers.app.CustomerService.CustomerView;
import com.selectcode.pos.customers.app.CustomerService.LedgerLine;
import com.selectcode.pos.customers.app.CustomerService.Statement;
import com.selectcode.pos.shared.docs.PdfWriter;
import com.selectcode.pos.shared.web.ApiException;
import com.selectcode.pos.shared.web.PageResponse;
import com.selectcode.pos.shop.app.SettingsService;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Customers, their ledger and statements (CUS-01, CUS-02). Payments are under /customer-payments. */
@RestController
@RequestMapping("/api/v1")
class CustomerController {

	static final String VIEW = "hasAuthority('customers.view')";

	static final String MANAGE = "hasAuthority('customers.manage')";

	private final CustomerService customers;

	private final SettingsService settings;

	CustomerController(CustomerService customers, SettingsService settings) {
		this.customers = customers;
		this.settings = settings;
	}

	@PreAuthorize(VIEW)
	@GetMapping("/customers")
	PageResponse<CustomerListItem> list(@RequestParam(required = false) String q,
			@RequestParam(required = false) String kind, @RequestParam(required = false) Boolean withBalance,
			@RequestParam(required = false) Boolean active, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return customers.list(q, kind, withBalance, active, PageResponse.page(page), PageResponse.size(size));
	}

	/** POS quick lookup by phone (POS-05); 404 when nobody has this number. */
	@PreAuthorize(VIEW)
	@GetMapping("/customers/lookup")
	CustomerView lookup(@RequestParam(required = false) String phone) {
		return customers.byPhone(phone).orElseThrow(() -> ApiException.notFound("Customer with this phone"));
	}

	@PreAuthorize(VIEW)
	@GetMapping("/customers/{id}")
	CustomerView get(@PathVariable long id) {
		return customers.get(id);
	}

	@PreAuthorize(MANAGE)
	@PostMapping("/customers")
	@ResponseStatus(HttpStatus.CREATED)
	CustomerView create(@RequestBody CustomerInput in) {
		return customers.create(in);
	}

	@PreAuthorize(MANAGE)
	@PatchMapping("/customers/{id}")
	CustomerView update(@PathVariable long id, @RequestBody CustomerInput in) {
		return customers.update(id, in);
	}

	@PreAuthorize(MANAGE)
	@PostMapping("/customers/{id}/activate")
	CustomerView activate(@PathVariable long id) {
		return customers.setActive(id, true);
	}

	@PreAuthorize(MANAGE)
	@PostMapping("/customers/{id}/deactivate")
	CustomerView deactivate(@PathVariable long id) {
		return customers.setActive(id, false);
	}

	@PreAuthorize(VIEW)
	@GetMapping("/customers/{id}/ledger")
	PageResponse<LedgerLine> ledger(@PathVariable long id, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return customers.ledger(id, PageResponse.page(page), PageResponse.size(size));
	}

	@PreAuthorize(VIEW)
	@GetMapping("/customers/{id}/statement")
	Statement statement(@PathVariable long id,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return customers.statement(id, from, to);
	}

	record SendStatement(String email) {
	}

	@PreAuthorize(VIEW)
	@PostMapping("/customers/{id}/statement/send")
	java.util.Map<String, Boolean> sendStatement(@PathVariable long id,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestBody(required = false) SendStatement in) {
		return java.util.Map.of("sent", customers.emailStatement(id, from, to, in == null ? null : in.email(),
				settings.get().businessName()));
	}

	@PreAuthorize(VIEW)
	@GetMapping("/customers/{id}/statement.pdf")
	ResponseEntity<byte[]> statementPdf(@PathVariable long id,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		Statement s = customers.statement(id, from, to);
		byte[] pdf = PdfWriter.write(customers.statementDoc(s, settings.get().businessName()));
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_PDF)
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.inline().filename("statement-" + id + ".pdf").build().toString())
			.body(pdf);
	}
}
