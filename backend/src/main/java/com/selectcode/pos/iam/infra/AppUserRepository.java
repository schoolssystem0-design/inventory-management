package com.selectcode.pos.iam.infra;

import com.selectcode.pos.iam.domain.AppUser;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Tenant-scoped: every query is limited to the current shop by Hibernate and by row-level security. */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

	@Query("select u from AppUser u where lower(u.username) = lower(?1)")
	Optional<AppUser> findByUsernameIgnoreCase(String username);

	Optional<AppUser> findByOwnerTrue();

	long countByStatus(AppUser.Status status);

	long countByRoleIdAndStatus(long roleId, AppUser.Status status);

	/*
	 * Sign-in bookkeeping is written with single atomic statements, not through the entity: it must not bump the
	 * optimistic-lock version and two sign-ins of the same user at the same moment must both succeed.
	 */

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = "update app_user set failed_attempts = 0, locked_until = null, last_login_at = :now where id = :id",
			nativeQuery = true)
	void recordSuccessfulSignIn(long id, Instant now);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			update app_user
			set locked_until = case when failed_attempts + 1 >= :max then :lockUntil else locked_until end,
			    failed_attempts = case when failed_attempts + 1 >= :max then 0 else failed_attempts + 1 end
			where id = :id""", nativeQuery = true)
	void recordFailedSignIn(long id, int max, Instant lockUntil);
}
