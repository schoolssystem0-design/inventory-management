package com.selectcode.pos.shared.files;

import com.selectcode.pos.shared.security.Permissions;
import java.util.Set;

/**
 * What a file is for: decides allowed types, maximum size, who may upload it and who may download it
 * (docs/architecture/SECURITY.md §2). An empty upload or download set means any signed-in user of the shop.
 */
public enum FilePurpose {

	SHOP_LOGO(FileKind.IMAGE, Set.of(Permissions.SETTINGS_EDIT), Set.of()),
	PRODUCT_IMAGE(FileKind.IMAGE, Set.of(Permissions.CATALOG_MANAGE), Set.of()),
	/** CNIC photos of used-phone sellers, guarantors (Stage 3): private. */
	CNIC_PHOTO(FileKind.IMAGE, Set.of(Permissions.BUYBACK_MANAGE), Set.of(Permissions.CUSTOMERS_PII)),
	/** Photo of a phone or other item bought from a walk-in seller (MOB-04). */
	DEVICE_PHOTO(FileKind.IMAGE, Set.of(Permissions.BUYBACK_MANAGE), Set.of(Permissions.BUYBACK_MANAGE)),
	/** Bill or receipt of an expense (CSH-01). */
	EXPENSE_ATTACHMENT(FileKind.DOCUMENT, Set.of(Permissions.EXPENSES_MANAGE), Set.of(Permissions.CASH_VIEW)),
	/** Generated exports and backups (OPS-13, OPS-14): downloaded only by who may export. */
	EXPORT(FileKind.EXPORT, Set.of(Permissions.REPORTS_VIEW, Permissions.DATA_EXPORT),
			Set.of(Permissions.REPORTS_VIEW, Permissions.DATA_EXPORT)),
	/** Prescriptions (Stage 3): private. */
	PRESCRIPTION(FileKind.DOCUMENT, Set.of(Permissions.POS_SELL), Set.of(Permissions.PRESCRIPTIONS_APPROVE));

	public enum FileKind {

		IMAGE(5L * 1024 * 1024, Set.of("image/png", "image/jpeg", "image/webp")),
		DOCUMENT(10L * 1024 * 1024, Set.of("application/pdf", "image/png", "image/jpeg")),
		/** Made by the server, never uploaded by users. */
		EXPORT(200L * 1024 * 1024, Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
				"application/pdf"));

		final long maxBytes;

		final Set<String> allowedTypes;

		FileKind(long maxBytes, Set<String> allowedTypes) {
			this.maxBytes = maxBytes;
			this.allowedTypes = allowedTypes;
		}
	}

	final FileKind kind;

	/** Any one of these permits upload. */
	final Set<String> uploadPermissions;

	/** Any one of these permits download; empty = any signed-in user of the shop. */
	final Set<String> viewPermissions;

	FilePurpose(FileKind kind, Set<String> uploadPermissions, Set<String> viewPermissions) {
		this.kind = kind;
		this.uploadPermissions = uploadPermissions;
		this.viewPermissions = viewPermissions;
	}
}
