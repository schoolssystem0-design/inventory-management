package com.selectcode.pos.shop.infra;

import com.selectcode.pos.shop.domain.Branch;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BranchRepository extends JpaRepository<Branch, Long> {

	@Query("select b from Branch b order by b.isDefault desc, b.name")
	List<Branch> findAllOrdered();

	@Query("select b from Branch b where lower(b.name) = lower(?1)")
	Optional<Branch> findByNameIgnoreCase(String name);

	@Query("select b from Branch b where upper(b.code) = upper(?1)")
	Optional<Branch> findByCodeIgnoreCase(String code);

	Optional<Branch> findByIsDefaultTrue();

	long countByActiveTrue();
}
