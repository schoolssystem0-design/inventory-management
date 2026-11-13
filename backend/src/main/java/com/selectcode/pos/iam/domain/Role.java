package com.selectcode.pos.iam.domain;

import com.selectcode.pos.shared.persistence.ShopScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** A bundle of permissions. System roles (owner, manager, cashier…) come from {@link RoleTemplates}. */
@Entity
@Table(name = "role")
public class Role extends ShopScopedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(name = "system_key", length = 40)
	private String systemKey;

	@Column(nullable = false, length = 300)
	private String description = "";

	@Column(nullable = false, columnDefinition = "text[]")
	private String[] permissions = new String[0];

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	@Version
	private long version;

	protected Role() {
	}

	public Role(String name, String systemKey, String description, List<String> permissions) {
		this.name = name;
		this.systemKey = systemKey;
		this.description = description;
		this.permissions = permissions.toArray(new String[0]);
	}

	public void setPermissions(List<String> permissions, Instant now) {
		this.permissions = permissions.toArray(new String[0]);
		this.updatedAt = now;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getSystemKey() {
		return systemKey;
	}

	public String getDescription() {
		return description;
	}

	public List<String> getPermissions() {
		return permissions == null ? List.of() : Arrays.asList(permissions);
	}

	public long getVersion() {
		return version;
	}
}
