package com.selectcode.pos.catalog.infra;

import com.selectcode.pos.catalog.domain.Brand;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BrandRepository extends JpaRepository<Brand, Long> {

	@Query("select x from Brand x where lower(x.name) = lower(?1)")
	List<Brand> findByNameIgnoreCase(String name);

	@Query("select x from Brand x order by x.name")
	List<Brand> findAllByName();
}
