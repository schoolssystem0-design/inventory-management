package com.selectcode.pos.catalog.domain;

import com.selectcode.pos.shared.persistence.ShopScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "brand")
public class Brand extends ShopScopedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(nullable = false)
	private boolean active = true;

	@Version
	private long version;

	protected Brand() {
	}

	public Brand(String name) {
		this.name = name;
	}

	public void update(String name, boolean active) {
		this.name = name;
		this.active = active;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public boolean isActive() {
		return active;
	}

	public long getVersion() {
		return version;
	}
}
