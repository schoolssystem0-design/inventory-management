package com.selectcode.pos.shop.domain;

import com.selectcode.pos.shared.persistence.ShopScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** A cash counter at a branch (SET-02). Till sessions arrive in Stage 3. */
@Entity
@Table(name = "till")
public class Till extends ShopScopedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "branch_id", nullable = false)
	private long branchId;

	@Column(nullable = false, length = 60)
	private String name;

	@Column(nullable = false)
	private boolean active = true;

	@Version
	private long version;

	protected Till() {
	}

	public Till(long branchId, String name) {
		this.branchId = branchId;
		this.name = name;
	}

	public void rename(String name) {
		this.name = name;
	}

	public void setActive(boolean active) {
		this.active = active;
	}

	public Long getId() {
		return id;
	}

	public long getBranchId() {
		return branchId;
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
