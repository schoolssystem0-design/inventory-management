package com.selectcode.pos.catalog.infra;

import com.selectcode.pos.catalog.domain.Category;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CategoryRepository extends JpaRepository<Category, Long> {

	@Query("select x from Category x where lower(x.name) = lower(?1)")
	List<Category> findByNameIgnoreCase(String name);

	@Query("select x from Category x order by x.name")
	List<Category> findAllByName();
}
