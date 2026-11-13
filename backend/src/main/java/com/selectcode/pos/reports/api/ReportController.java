package com.selectcode.pos.reports.api;

import com.selectcode.pos.reports.app.DashboardService;
import com.selectcode.pos.reports.app.DashboardService.Dashboard;
import com.selectcode.pos.reports.app.DashboardService.SearchHit;
import com.selectcode.pos.reports.app.ExportService;
import com.selectcode.pos.reports.app.ExportService.ExportJob;
import com.selectcode.pos.reports.app.ExportService.ExportRequest;
import com.selectcode.pos.reports.app.ReportService;
import com.selectcode.pos.reports.app.ReportService.ReportInfo;
import com.selectcode.pos.reports.app.ReportService.ReportResult;
import com.selectcode.pos.shared.docs.ExcelWriter;
import com.selectcode.pos.shared.docs.PdfWriter;
import com.selectcode.pos.shared.files.FileService;
import com.selectcode.pos.shared.security.AnyAuthenticated;
import com.selectcode.pos.shared.web.ApiException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Dashboard, reports with Excel/PDF export, background exports, the owner's backup and global search. */
@RestController
@RequestMapping("/api/v1")
class ReportController {

	static final String REPORTS = "hasAuthority('reports.view')";

	/** Request parameters that are filters every report understands (the rest are passed to the report). */
	private static final Set<String> COMMON = Set.of("from", "to", "branchId", "format", "max");

	private final ReportService reports;

	private final ExportService exports;

	private final DashboardService dashboard;

	private final FileService files;

	ReportController(ReportService reports, ExportService exports, DashboardService dashboard, FileService files) {
		this.reports = reports;
		this.exports = exports;
		this.dashboard = dashboard;
		this.files = files;
	}

	@AnyAuthenticated
	@GetMapping("/dashboard")
	Dashboard dashboard(@RequestParam(required = false) Long branchId) {
		return dashboard.dashboard(branchId);
	}

	@AnyAuthenticated
	@GetMapping("/search")
	List<SearchHit> search(@RequestParam(required = false) String q) {
		return dashboard.search(q);
	}

	@PreAuthorize(REPORTS)
	@GetMapping("/reports")
	List<ReportInfo> reports() {
		return reports.list();
	}

	@PreAuthorize(REPORTS)
	@GetMapping("/reports/{key}")
	ReportResult report(@PathVariable String key,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) Long branchId, @RequestParam Map<String, String> all) {
		return reports.run(key, from, to, branchId, extra(all), reports.access(), ReportService.SCREEN_ROWS);
	}

	/** Immediate download (up to 20,000 rows); bigger exports go through POST /reports/{key}/export. */
	@PreAuthorize(REPORTS)
	@GetMapping("/reports/{key}/download")
	ResponseEntity<byte[]> download(@PathVariable String key,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) Long branchId, @RequestParam(required = false) String format,
			@RequestParam Map<String, String> all) {
		ReportResult result = reports.run(key, from, to, branchId, extra(all), reports.access(), 20_000);
		if (result.truncated()) {
			throw ApiException.rule("too_many_rows", "This report has " + result.rowCount()
					+ " rows. Use 'Export in background' and download the file when it is ready.");
		}
		boolean pdf = "PDF".equalsIgnoreCase(format);
		byte[] bytes = pdf ? PdfWriter.write(ReportService.toDoc(result)) : ExcelWriter.write(ReportService.toDoc(result));
		return ResponseEntity.ok()
			.contentType(pdf ? MediaType.APPLICATION_PDF : MediaType.parseMediaType(ExportService.XLSX))
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename(key + (pdf ? ".pdf" : ".xlsx")).build().toString())
			.body(bytes);
	}

	@PreAuthorize(REPORTS)
	@PostMapping("/reports/{key}/export")
	@ResponseStatus(HttpStatus.ACCEPTED)
	ExportJob export(@PathVariable String key, @RequestBody(required = false) ExportRequest in) {
		return exports.request(key, in == null ? new ExportRequest(null, null, null, null, null) : in);
	}

	/** The owner's full Excel backup (NFR-13), made in the background. */
	@PreAuthorize("hasAuthority('data.export')")
	@PostMapping("/backup")
	@ResponseStatus(HttpStatus.ACCEPTED)
	ExportJob backup() {
		return exports.request("BACKUP", new ExportRequest("XLSX", null, null, null, null));
	}

	@PreAuthorize("hasAnyAuthority('reports.view', 'data.export')")
	@GetMapping("/exports")
	List<ExportJob> exports() {
		return exports.recent();
	}

	@PreAuthorize("hasAnyAuthority('reports.view', 'data.export')")
	@GetMapping("/exports/{id}")
	ExportJob exportJob(@PathVariable long id) {
		return exports.get(id);
	}

	@PreAuthorize("hasAnyAuthority('reports.view', 'data.export')")
	@GetMapping("/exports/{id}/file")
	ResponseEntity<byte[]> exportFile(@PathVariable long id) {
		ExportJob job = exports.get(id);
		if (job.fileId() == null) {
			throw ApiException.rule("export_not_ready", "The file is not ready yet");
		}
		var download = files.download(job.fileId());
		byte[] content = download.content();
		if (content == null) {
			return ResponseEntity.status(HttpStatus.FOUND).location(download.redirect().orElseThrow()).build();
		}
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(download.file().getContentType()))
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename(download.file().getOriginalName()).build().toString())
			.body(content);
	}

	private static Map<String, String> extra(Map<String, String> all) {
		Map<String, String> extra = new LinkedHashMap<>();
		all.forEach((k, v) -> {
			if (!COMMON.contains(k) && v != null && !v.isBlank() && k.matches("[a-zA-Z]{1,30}") && v.length() <= 60) {
				extra.put(k, v);
			}
		});
		return extra;
	}
}
