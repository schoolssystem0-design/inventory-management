package com.selectcode.pos.catalog.domain;

import com.selectcode.pos.shared.persistence.ShopScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** A product category; categories form a tree through {@code parentId} (CAT-03). */
@Entity
@Table(name = "category")
public class Category extends ShopScopedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "parent_id")
	private Long parentId;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	@Column(nullable = false)
	private boolean active = true;

	@Version
	private long version;

	protected Category() {
	}

	public Category(Long parentId, String name, int sortOrder) {
		this.parentId = parentId;
		this.name = name;
		this.sortOrder = sortOrder;
	}

	public void update(Long parentId, String name, int sortOrder, boolean active) {
		this.parentId = parentId;
		this.name = name;
		this.sortOrder = sortOrder;
		this.active = active;
	}

	public Long getId() {
		return id;
	}

	public Long getParentId() {
		return parentId;
	}

	public String getName() {
		return name;
	}

	public int getSortOrder() {
		return sortOrder;
	}

	public boolean isActive() {
		return active;
	}

	public long getVersion() {
		return version;
	}
}
