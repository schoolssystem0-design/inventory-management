package com.selectcode.pos.catalog.api;

import com.selectcode.pos.catalog.app.LabelService;
import com.selectcode.pos.catalog.app.LabelService.Label;
import com.selectcode.pos.catalog.app.LabelService.LabelRequestItem;
import com.selectcode.pos.catalog.app.ProductImportService;
import com.selectcode.pos.catalog.app.ProductImportService.ImportResult;
import com.selectcode.pos.catalog.app.ProductService;
import com.selectcode.pos.catalog.app.ProductService.BulkPriceRequest;
import com.selectcode.pos.catalog.app.ProductService.BulkPriceResult;
import com.selectcode.pos.catalog.app.ProductService.HistoryEntry;
import com.selectcode.pos.catalog.app.ProductService.ProductInput;
import com.selectcode.pos.catalog.app.ProductService.ProductListItem;
import com.selectcode.pos.catalog.app.ProductService.ProductQuery;
import com.selectcode.pos.catalog.app.ProductService.ProductView;
import com.selectcode.pos.catalog.app.TaxonomyService;
import com.selectcode.pos.catalog.app.TaxonomyService.BrandView;
import com.selectcode.pos.catalog.app.TaxonomyService.CategoryInput;
import com.selectcode.pos.catalog.app.TaxonomyService.CategoryView;
import com.selectcode.pos.catalog.app.TaxonomyService.NamedInput;
import com.selectcode.pos.catalog.app.TaxonomyService.UnitView;
import com.selectcode.pos.shared.security.Permissions;
import com.selectcode.pos.shared.web.ApiException;
import com.selectcode.pos.shared.web.PageResponse;
import java.io.IOException;
import java.util.List;
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
import org.springframework.web.multipart.MultipartFile;

/** Products, categories, brands, units, import/export and labels (CAT-01..09). */
@RestController
@RequestMapping("/api/v1")
class CatalogController {

	static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

	record LabelRequest(List<LabelRequestItem> items) {
	}

	private final ProductService products;

	private final TaxonomyService taxonomy;

	private final ProductImportService imports;

	private final LabelService labels;

	CatalogController(ProductService products, TaxonomyService taxonomy, ProductImportService imports,
			LabelService labels) {
		this.products = products;
		this.taxonomy = taxonomy;
		this.imports = imports;
		this.labels = labels;
	}

	// ---- products

	@PreAuthorize(Permissions.HAS_CATALOG_VIEW)
	@GetMapping("/products")
	PageResponse<ProductListItem> products(@RequestParam(required = false) String q,
			@RequestParam(required = false) Long categoryId, @RequestParam(required = false) Long brandId,
			@RequestParam(required = false) Boolean active, @RequestParam(required = false) String kind,
			@RequestParam(required = false) String sort, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return products.search(new ProductQuery(q, categoryId, brandId, active, kind, sort, PageResponse.page(page),
				PageResponse.size(size)));
	}

	/** Scanner lookup: barcode, pack-unit barcode or SKU. 404 when nothing matches. */
	@PreAuthorize(Permissions.HAS_CATALOG_VIEW)
	@GetMapping("/products/lookup")
	ProductView lookup(@RequestParam String code) {
		return products.lookup(code).orElseThrow(() -> ApiException.notFound("Product with this barcode"));
	}

	@PreAuthorize(Permissions.HAS_CATALOG_VIEW)
	@GetMapping("/products/{id}")
	ProductView product(@PathVariable long id) {
		return products.get(id);
	}

	@PreAuthorize(Permissions.HAS_CATALOG_VIEW)
	@GetMapping("/products/{id}/history")
	List<HistoryEntry> productHistory(@PathVariable long id) {
		return products.history(id);
	}

	@PreAuthorize(Permissions.HAS_CATALOG_MANAGE)
	@PostMapping("/products")
	@ResponseStatus(HttpStatus.CREATED)
	ProductView createProduct(@RequestBody ProductInput body) {
		return products.create(body);
	}

	@PreAuthorize(Permissions.HAS_CATALOG_MANAGE)
	@PatchMapping("/products/{id}")
	ProductView updateProduct(@PathVariable long id, @RequestBody ProductInput body) {
		return products.update(id, body);
	}

	@PreAuthorize(Permissions.HAS_CATALOG_MANAGE)
	@PostMapping("/products/{id}/deactivate")
	ProductView deactivateProduct(@PathVariable long id) {
		return products.setActive(id, false);
	}

	@PreAuthorize(Permissions.HAS_CATALOG_MANAGE)
	@PostMapping("/products/{id}/activate")
	ProductView activateProduct(@PathVariable long id) {
		return products.setActive(id, true);
	}

	@PreAuthorize(Permissions.HAS_CATALOG_MANAGE)
	@PostMapping("/products/{id}/generate-barcode")
	ProductView generateBarcode(@PathVariable long id) {
		return products.generateBarcode(id);
	}

	/** Preview: shows old and new prices without saving. */
	@PreAuthorize(Permissions.HAS_CATALOG_PRICES)
	@PostMapping("/products/bulk-price/preview")
	BulkPriceResult bulkPricePreview(@RequestBody BulkPriceRequest body) {
		return products.bulkPrice(body, false);
	}

	@PreAuthorize(Permissions.HAS_CATALOG_PRICES)
	@PostMapping("/products/bulk-price")
	BulkPriceResult bulkPrice(@RequestBody BulkPriceRequest body) {
		return products.bulkPrice(body, true);
	}

	@PreAuthorize(Permissions.HAS_CATALOG_IMPORT)
	@GetMapping("/products/import/template")
	ResponseEntity<byte[]> importTemplate() {
		return xlsx(imports.template(), "products-template.xlsx");
	}

	/** {@code dryRun=true} (default) lists problems and saves nothing; {@code dryRun=false} saves all rows or none. */
	@PreAuthorize(Permissions.HAS_CATALOG_IMPORT)
	@PostMapping(path = "/products/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	ImportResult importProducts(@RequestParam MultipartFile file,
			@RequestParam(defaultValue = "true") boolean dryRun) throws IOException {
		return imports.importFile(file.getBytes(), !dryRun);
	}

	@PreAuthorize(Permissions.HAS_CATALOG_EXPORT)
	@GetMapping("/products/export")
	ResponseEntity<byte[]> exportProducts() {
		return xlsx(imports.export(), "products.xlsx");
	}

	@PreAuthorize(Permissions.HAS_LABELS_PRINT)
	@PostMapping("/labels")
	List<Label> labels(@RequestBody LabelRequest body) {
		return labels.labels(body.items());
	}

	// ---- categories, brands, units

	@PreAuthorize(Permissions.HAS_CATALOG_VIEW)
	@GetMapping("/categories")
	List<CategoryView> categories() {
		return taxonomy.categories();
	}

	@PreAuthorize(Permissions.HAS_CATALOG_MANAGE)
	@PostMapping("/categories")
	@ResponseStatus(HttpStatus.CREATED)
	CategoryView createCategory(@RequestBody CategoryInput body) {
		return taxonomy.createCategory(body);
	}

	@PreAuthorize(Permissions.HAS_CATALOG_MANAGE)
	@PatchMapping("/categories/{id}")
	CategoryView updateCategory(@PathVariable long id, @RequestBody CategoryInput body) {
		return taxonomy.updateCategory(id, body);
	}

	@PreAuthorize(Permissions.HAS_CATALOG_VIEW)
	@GetMapping("/brands")
	List<BrandView> brands() {
		return taxonomy.brands();
	}

	@PreAuthorize(Permissions.HAS_CATALOG_MANAGE)
	@PostMapping("/brands")
	@ResponseStatus(HttpStatus.CREATED)
	BrandView createBrand(@RequestBody NamedInput body) {
		return taxonomy.createBrand(body);
	}

	@PreAuthorize(Permissions.HAS_CATALOG_MANAGE)
	@PatchMapping("/brands/{id}")
	BrandView updateBrand(@PathVariable long id, @RequestBody NamedInput body) {
		return taxonomy.updateBrand(id, body);
	}

	@PreAuthorize(Permissions.HAS_CATALOG_VIEW)
	@GetMapping("/units")
	List<UnitView> units() {
		return taxonomy.units();
	}

	@PreAuthorize(Permissions.HAS_CATALOG_MANAGE)
	@PostMapping("/units")
	@ResponseStatus(HttpStatus.CREATED)
	UnitView createUnit(@RequestBody NamedInput body) {
		return taxonomy.createUnit(body);
	}

	@PreAuthorize(Permissions.HAS_CATALOG_MANAGE)
	@PatchMapping("/units/{id}")
	UnitView updateUnit(@PathVariable long id, @RequestBody NamedInput body) {
		return taxonomy.updateUnit(id, body);
	}

	private static ResponseEntity<byte[]> xlsx(byte[] content, String name) {
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(XLSX))
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
			.body(content);
	}
}
