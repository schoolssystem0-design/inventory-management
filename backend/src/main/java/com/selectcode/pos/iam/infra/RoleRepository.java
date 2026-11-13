package com.selectcode.pos.iam.infra;

import com.selectcode.pos.iam.domain.Role;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RoleRepository extends JpaRepository<Role, Long> {

	Optional<Role> findBySystemKey(String systemKey);

	@Query("select r from Role r order by case when r.systemKey = 'OWNER' then 0 else 1 end, r.name")
	List<Role> findAllOrdered();
}
