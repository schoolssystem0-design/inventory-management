package com.selectcode.pos.catalog.api;

import com.selectcode.pos.catalog.app.VariantService;
import com.selectcode.pos.catalog.app.VariantService.BundleItemInput;
import com.selectcode.pos.catalog.app.VariantService.BundleView;
import com.selectcode.pos.catalog.app.VariantService.SizeSetInput;
import com.selectcode.pos.catalog.app.VariantService.SizeSetView;
import com.selectcode.pos.catalog.app.VariantService.VariantGrid;
import com.selectcode.pos.catalog.app.VariantService.VariantGridInput;
import com.selectcode.pos.shared.features.ModuleKey;
import com.selectcode.pos.shared.features.RequiresModule;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Size × colour variants and size sets (GAR-01, GAR-02, OTH-01) and bundles / book sets (OTH-02). */
@RestController
@RequestMapping("/api/v1")
class VariantController {

	record BundleInput(List<BundleItemInput> items) {
	}

	private final VariantService variants;

	VariantController(VariantService variants) {
		this.variants = variants;
	}

	@RequiresModule(ModuleKey.VARIANTS)
	@PreAuthorize("hasAuthority('catalog.view')")
	@GetMapping("/products/{id}/variants")
	VariantGrid variants(@PathVariable long id) {
		return variants.grid(id);
	}

	@RequiresModule(ModuleKey.VARIANTS)
	@PreAuthorize("hasAuthority('catalog.manage')")
	@PostMapping("/products/{id}/variants")
	@ResponseStatus(HttpStatus.CREATED)
	VariantGrid createVariants(@PathVariable long id, @RequestBody VariantGridInput in) {
		return variants.createGrid(id, in);
	}

	@RequiresModule(ModuleKey.VARIANTS)
	@PreAuthorize("hasAuthority('catalog.view')")
	@GetMapping("/size-sets")
	List<SizeSetView> sizeSets() {
		return variants.sizeSets();
	}

	@RequiresModule(ModuleKey.VARIANTS)
	@PreAuthorize("hasAuthority('catalog.manage')")
	@PostMapping("/size-sets")
	@ResponseStatus(HttpStatus.CREATED)
	SizeSetView createSizeSet(@RequestBody SizeSetInput in) {
		return variants.createSizeSet(in);
	}

	@RequiresModule(ModuleKey.VARIANTS)
	@PreAuthorize("hasAuthority('catalog.manage')")
	@DeleteMapping("/size-sets/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void deleteSizeSet(@PathVariable long id) {
		variants.deleteSizeSet(id);
	}

	@RequiresModule(ModuleKey.BUNDLES)
	@PreAuthorize("hasAuthority('catalog.view')")
	@GetMapping("/products/{id}/bundle")
	BundleView bundle(@PathVariable long id) {
		return variants.bundle(id);
	}

	@RequiresModule(ModuleKey.BUNDLES)
	@PreAuthorize("hasAuthority('catalog.manage')")
	@PutMapping("/products/{id}/bundle")
	BundleView setBundle(@PathVariable long id, @RequestBody BundleInput in) {
		return variants.setBundle(id, in.items());
	}
}
