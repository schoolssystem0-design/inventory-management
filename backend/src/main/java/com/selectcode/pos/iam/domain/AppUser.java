package com.selectcode.pos.iam.domain;

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
import java.time.Duration;
import java.time.Instant;

/** A shop user (owner, manager, cashier…). Belongs to exactly one shop. */
@Entity
@Table(name = "app_user")
public class AppUser extends ShopScopedEntity {

	public static final int MAX_FAILED_ATTEMPTS = 5;

	public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

	public enum Status {
		ACTIVE, INACTIVE
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 60)
	private String username;

	@Column(name = "display_name", nullable = false, length = 200)
	private String displayName;

	@Column(length = 30)
	private String phone;

	@Column(length = 254)
	private String email;

	@Column(name = "role_id", nullable = false)
	private long roleId;

	@Column(name = "is_owner", nullable = false)
	private boolean owner;

	@Column(name = "all_branches", nullable = false)
	private boolean allBranches = true;

	@Column(name = "password_hash", nullable = false, length = 200)
	private String passwordHash;

	@Column(name = "must_change_password", nullable = false)
	private boolean mustChangePassword;

	@Column(name = "pin_hash", length = 200)
	private String pinHash;

	@Column(name = "pin_failed_attempts", nullable = false)
	private int pinFailedAttempts;

	@Column(name = "pin_locked_until")
	private Instant pinLockedUntil;

	@Column(name = "totp_secret", length = 200)
	private String totpSecret;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Status status = Status.ACTIVE;

	@Column(name = "failed_attempts", nullable = false)
	private int failedAttempts;

	@Column(name = "locked_until")
	private Instant lockedUntil;

	@Column(name = "last_login_at")
	private Instant lastLoginAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	@Version
	private long version;

	protected AppUser() {
	}

	public AppUser(String username, String displayName, long roleId, String passwordHash) {
		this.username = username;
		this.displayName = displayName;
		this.roleId = roleId;
		this.passwordHash = passwordHash;
	}

	public boolean canSignIn(Instant now) {
		return status == Status.ACTIVE && (lockedUntil == null || !lockedUntil.isAfter(now));
	}

	public boolean isLocked(Instant now) {
		return lockedUntil != null && lockedUntil.isAfter(now);
	}

	public void setPassword(String hash, boolean mustChange, Instant now) {
		this.passwordHash = hash;
		this.mustChangePassword = mustChange;
		this.failedAttempts = 0;
		this.lockedUntil = null;
		this.updatedAt = now;
	}

	public void setPin(String hash, Instant now) {
		this.pinHash = hash;
		this.pinFailedAttempts = 0;
		this.pinLockedUntil = null;
		this.updatedAt = now;
	}

	public boolean pinLocked(Instant now) {
		return pinLockedUntil != null && pinLockedUntil.isAfter(now);
	}

	public void pinFailed(Instant now) {
		pinFailedAttempts++;
		if (pinFailedAttempts >= MAX_FAILED_ATTEMPTS) {
			pinLockedUntil = now.plus(LOCK_DURATION);
			pinFailedAttempts = 0;
		}
	}

	public void pinSucceeded() {
		pinFailedAttempts = 0;
		pinLockedUntil = null;
	}

	public void setTotpSecret(String secret, Instant now) {
		this.totpSecret = secret;
		this.updatedAt = now;
	}

	public void updateProfile(String displayName, String phone, String email, Instant now) {
		this.displayName = displayName;
		this.phone = phone;
		this.email = email;
		this.updatedAt = now;
	}

	public void setRole(long roleId, Instant now) {
		this.roleId = roleId;
		this.updatedAt = now;
	}

	public void setAllBranches(boolean allBranches) {
		this.allBranches = allBranches;
	}

	public void setOwner(boolean owner) {
		this.owner = owner;
	}

	public void setStatus(Status status, Instant now) {
		this.status = status;
		this.updatedAt = now;
	}

	public void unlock(Instant now) {
		this.failedAttempts = 0;
		this.lockedUntil = null;
		this.updatedAt = now;
	}

	public Long getId() {
		return id;
	}

	public String getUsername() {
		return username;
	}

	public String getDisplayName() {
		return displayName;
	}

	public String getPhone() {
		return phone;
	}

	public String getEmail() {
		return email;
	}

	public long getRoleId() {
		return roleId;
	}

	public boolean isOwner() {
		return owner;
	}

	public boolean isAllBranches() {
		return allBranches;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public boolean isMustChangePassword() {
		return mustChangePassword;
	}

	public String getPinHash() {
		return pinHash;
	}

	public String getTotpSecret() {
		return totpSecret;
	}

	public Status getStatus() {
		return status;
	}

	public Instant getLockedUntil() {
		return lockedUntil;
	}

	public Instant getLastLoginAt() {
		return lastLoginAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public long getVersion() {
		return version;
	}
}
