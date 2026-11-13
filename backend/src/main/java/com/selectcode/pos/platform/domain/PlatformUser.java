package com.selectcode.pos.platform.domain;

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

/** A SelectCode staff account for the platform portal. Not tenant-scoped. */
@Entity
@Table(name = "platform_user")
public class PlatformUser {

	public static final int MAX_FAILED_ATTEMPTS = 5;

	public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

	public enum Role {
		SUPER_ADMIN, SUPPORT, BILLING, ONBOARDING
	}

	public enum Status {
		ACTIVE, INACTIVE
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 254)
	private String email;

	@Column(nullable = false, length = 200)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Role role = Role.SUPER_ADMIN;

	@Column(name = "password_hash", nullable = false, length = 200)
	private String passwordHash;

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

	protected PlatformUser() {
	}

	public PlatformUser(String email, String name, Role role, String passwordHash) {
		this.email = email;
		this.name = name;
		this.role = role;
		this.passwordHash = passwordHash;
	}

	public boolean isActive() {
		return status == Status.ACTIVE;
	}

	public boolean isLocked(Instant now) {
		return lockedUntil != null && lockedUntil.isAfter(now);
	}

	public void recordFailure(Instant now) {
		failedAttempts++;
		if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
			lockedUntil = now.plus(LOCK_DURATION);
			failedAttempts = 0;
		}
		updatedAt = now;
	}

	public void recordSuccess(Instant now) {
		failedAttempts = 0;
		lockedUntil = null;
		lastLoginAt = now;
		updatedAt = now;
	}

	public void enrollTotp(String secret, Instant now) {
		this.totpSecret = secret;
		this.updatedAt = now;
	}

	public void resetCredentials(String passwordHash, Instant now) {
		this.passwordHash = passwordHash;
		this.totpSecret = null;
		this.failedAttempts = 0;
		this.lockedUntil = null;
		this.updatedAt = now;
	}

	public void setStatus(Status status, Instant now) {
		this.status = status;
		this.updatedAt = now;
	}

	public void setRole(Role role, Instant now) {
		this.role = role;
		this.updatedAt = now;
	}

	public void rename(String name, Instant now) {
		this.name = name;
		this.updatedAt = now;
	}

	public Long getId() {
		return id;
	}

	public String getEmail() {
		return email;
	}

	public String getName() {
		return name;
	}

	public Role getRole() {
		return role;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public String getTotpSecret() {
		return totpSecret;
	}

	public Status getStatus() {
		return status;
	}

	public Instant getLastLoginAt() {
		return lastLoginAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
