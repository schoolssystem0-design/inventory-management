package com.selectcode.pos.shop.domain;

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
import java.time.Instant;

/** A place inside a shop that holds stock: the shop floor, a godown, a second branch (SET-02). */
@Entity
@Table(name = "branch")
public class Branch extends ShopScopedEntity {

	public enum Kind {
		SHOP, GODOWN
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(nullable = false, length = 10)
	private String code;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private Kind kind = Kind.SHOP;

	@Column(length = 500)
	private String address;

	@Column(length = 30)
	private String phone;

	@Column(name = "is_default", nullable = false)
	private boolean isDefault;

	@Column(nullable = false)
	private boolean active = true;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	@Version
	private long version;

	protected Branch() {
	}

	public Branch(String name, String code, Kind kind, boolean isDefault) {
		this.name = name;
		this.code = code;
		this.kind = kind;
		this.isDefault = isDefault;
	}

	public void update(String name, String code, Kind kind, String address, String phone, Instant now) {
		this.name = name;
		this.code = code;
		this.kind = kind;
		this.address = address;
		this.phone = phone;
		this.updatedAt = now;
	}

	public void setActive(boolean active, Instant now) {
		this.active = active;
		this.updatedAt = now;
	}

	public void setDefault(boolean isDefault) {
		this.isDefault = isDefault;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getCode() {
		return code;
	}

	public Kind getKind() {
		return kind;
	}

	public String getAddress() {
		return address;
	}

	public String getPhone() {
		return phone;
	}

	public boolean isDefault() {
		return isDefault;
	}

	public boolean isActive() {
		return active;
	}

	public long getVersion() {
		return version;
	}
}
