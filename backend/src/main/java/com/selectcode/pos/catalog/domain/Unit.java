package com.selectcode.pos.catalog.domain;

import com.selectcode.pos.shared.persistence.ShopScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** A unit of measure (piece, kg, strip…). {@code decimalsAllowed} lets quantities like 2.5 be sold. */
@Entity
@Table(name = "unit")
public class Unit extends ShopScopedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 30)
	private String name;

	@Column(name = "decimals_allowed", nullable = false)
	private boolean decimalsAllowed;

	@Column(nullable = false)
	private boolean active = true;

	@Version
	private long version;

	protected Unit() {
	}

	public Unit(String name, boolean decimalsAllowed) {
		this.name = name;
		this.decimalsAllowed = decimalsAllowed;
	}

	public void update(String name, boolean decimalsAllowed, boolean active) {
		this.name = name;
		this.decimalsAllowed = decimalsAllowed;
		this.active = active;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public boolean isDecimalsAllowed() {
		return decimalsAllowed;
	}

	public boolean isActive() {
		return active;
	}

	public long getVersion() {
		return version;
	}
}
