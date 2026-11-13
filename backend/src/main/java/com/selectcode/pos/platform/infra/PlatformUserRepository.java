package com.selectcode.pos.platform.infra;

import com.selectcode.pos.platform.domain.PlatformUser;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PlatformUserRepository extends JpaRepository<PlatformUser, Long> {

	@Query("select u from PlatformUser u where lower(u.email) = lower(?1)")
	Optional<PlatformUser> findByEmailIgnoreCase(String email);

	List<PlatformUser> findAllByOrderByNameAsc();
}
