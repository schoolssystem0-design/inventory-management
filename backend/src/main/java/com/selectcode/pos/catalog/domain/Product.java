package com.selectcode.pos.catalog.domain;

import com.selectcode.pos.shared.persistence.ShopScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A product (CAT-01). Barcodes, pack units and shop-type fields live in their own tables. {@code costAvg} is the
 * weighted average cost, kept by the stock engine from Stage 3 onwards; here it is the opening cost.
 */
@Entity
@Table(name = "product")
public class Product extends ShopScopedEntity {

	public enum Kind {
		STANDARD, SERIAL, BATCH, VARIANT_PARENT, BUNDLE, SERVICE, RECIPE
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 40)
	private String sku;

	@Column(nullable = false, length = 200)
	private String name;

	@Column(name = "urdu_name", length = 200)
	private String urduName;

	@Column(name = "category_id")
	private Long categoryId;

	@Column(name = "brand_id")
	private Long brandId;

	@Column(name = "unit_id", nullable = false)
	private long unitId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Kind kind = Kind.STANDARD;

	@Column(name = "cost_avg", nullable = false, precision = 14, scale = 2)
	private BigDecimal costAvg = BigDecimal.ZERO;

	@Column(nullable = false, precision = 14, scale = 2)
	private BigDecimal price;

	@Column(name = "wholesale_price", precision = 14, scale = 2)
	private BigDecimal wholesalePrice;

	@Column(name = "min_price", precision = 14, scale = 2)
	private BigDecimal minPrice;

	@Column(name = "tax_rate", precision = 7, scale = 4)
	private BigDecimal taxRate;

	@Column(name = "reorder_level", nullable = false, precision = 14, scale = 3)
	private BigDecimal reorderLevel = BigDecimal.ZERO;

	@Column(name = "reorder_qty", nullable = false, precision = 14, scale = 3)
	private BigDecimal reorderQty = BigDecimal.ZERO;

	@Column(name = "image_file_id")
	private Long imageFileId;

	@Column(nullable = false)
	private boolean weighed;

	@Column(name = "warranty_days", nullable = false)
	private int warrantyDays;

	@Column(length = 1000)
	private String description;

	@Column(nullable = false)
	private boolean active = true;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	@Version
	private long version;

	protected Product() {
	}

	public Product(String sku, String name, long unitId, BigDecimal price) {
		this.sku = sku;
		this.name = name;
		this.unitId = unitId;
		this.price = price;
	}

	public void touch(Instant now) {
		this.updatedAt = now;
	}

	public Long getId() {
		return id;
	}

	public String getSku() {
		return sku;
	}

	public void setSku(String sku) {
		this.sku = sku;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getUrduName() {
		return urduName;
	}

	public void setUrduName(String urduName) {
		this.urduName = urduName;
	}

	public Long getCategoryId() {
		return categoryId;
	}

	public void setCategoryId(Long categoryId) {
		this.categoryId = categoryId;
	}

	public Long getBrandId() {
		return brandId;
	}

	public void setBrandId(Long brandId) {
		this.brandId = brandId;
	}

	public long getUnitId() {
		return unitId;
	}

	public void setUnitId(long unitId) {
		this.unitId = unitId;
	}

	public Kind getKind() {
		return kind;
	}

	public void setKind(Kind kind) {
		this.kind = kind;
	}

	public BigDecimal getCostAvg() {
		return costAvg;
	}

	public void setCostAvg(BigDecimal costAvg) {
		this.costAvg = costAvg;
	}

	public BigDecimal getPrice() {
		return price;
	}

	public void setPrice(BigDecimal price) {
		this.price = price;
	}

	public BigDecimal getWholesalePrice() {
		return wholesalePrice;
	}

	public void setWholesalePrice(BigDecimal wholesalePrice) {
		this.wholesalePrice = wholesalePrice;
	}

	public BigDecimal getMinPrice() {
		return minPrice;
	}

	public void setMinPrice(BigDecimal minPrice) {
		this.minPrice = minPrice;
	}

	public BigDecimal getTaxRate() {
		return taxRate;
	}

	public void setTaxRate(BigDecimal taxRate) {
		this.taxRate = taxRate;
	}

	public BigDecimal getReorderLevel() {
		return reorderLevel;
	}

	public void setReorderLevel(BigDecimal reorderLevel) {
		this.reorderLevel = reorderLevel;
	}

	public BigDecimal getReorderQty() {
		return reorderQty;
	}

	public void setReorderQty(BigDecimal reorderQty) {
		this.reorderQty = reorderQty;
	}

	public Long getImageFileId() {
		return imageFileId;
	}

	public void setImageFileId(Long imageFileId) {
		this.imageFileId = imageFileId;
	}

	public boolean isWeighed() {
		return weighed;
	}

	public void setWeighed(boolean weighed) {
		this.weighed = weighed;
	}

	public int getWarrantyDays() {
		return warrantyDays;
	}

	public void setWarrantyDays(int warrantyDays) {
		this.warrantyDays = warrantyDays;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public boolean isActive() {
		return active;
	}

	public void setActive(boolean active) {
		this.active = active;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public long getVersion() {
		return version;
	}
}
