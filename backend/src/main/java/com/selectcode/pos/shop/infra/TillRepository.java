package com.selectcode.pos.shop.infra;

import com.selectcode.pos.shop.domain.Till;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TillRepository extends JpaRepository<Till, Long> {

	@Query("select t from Till t order by t.branchId, t.name")
	List<Till> findAllOrdered();

	@Query("select count(t) from Till t where t.branchId = ?1 and lower(t.name) = lower(?2) and t.id <> ?3")
	long countSameName(long branchId, String name, long excludeId);

	long countByActiveTrue();
}
