package com.manish.ecommerce.api.repository;

import com.manish.ecommerce.api.entity.ProductReview;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductReviewRepository extends JpaRepository<ProductReview, Long> {

    @EntityGraph(attributePaths = "customer")
    Page<ProductReview> findByProductId(Long productId, Pageable pageable);

    boolean existsByProductId(Long productId);

    boolean existsByCustomerId(Long customerId);
}
