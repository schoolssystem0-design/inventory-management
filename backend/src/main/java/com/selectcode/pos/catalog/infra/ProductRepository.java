package com.selectcode.pos.catalog.infra;

import com.selectcode.pos.catalog.domain.Product;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProductRepository extends JpaRepository<Product, Long> {

	@Query("select p from Product p where lower(p.sku) = lower(?1)")
	Optional<Product> findBySkuIgnoreCase(String sku);

	long countByActiveTrue();
}
