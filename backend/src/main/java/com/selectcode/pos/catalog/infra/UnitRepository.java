package com.selectcode.pos.catalog.infra;

import com.selectcode.pos.catalog.domain.Unit;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UnitRepository extends JpaRepository<Unit, Long> {

	@Query("select x from Unit x where lower(x.name) = lower(?1)")
	List<Unit> findByNameIgnoreCase(String name);

	@Query("select x from Unit x order by x.name")
	List<Unit> findAllByName();
}
