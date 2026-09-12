package com.manish.ecommerce.api.service;

import com.manish.ecommerce.api.dto.PageResponse;
import com.manish.ecommerce.api.dto.ProductRequest;
import com.manish.ecommerce.api.dto.ProductResponse;
import com.manish.ecommerce.api.entity.Category;
import com.manish.ecommerce.api.entity.Product;
import com.manish.ecommerce.api.exception.BusinessRuleException;
import com.manish.ecommerce.api.exception.DuplicateResourceException;
import com.manish.ecommerce.api.exception.ResourceNotFoundException;
import com.manish.ecommerce.api.repository.OrderItemRepository;
import com.manish.ecommerce.api.repository.ProductRepository;
import com.manish.ecommerce.api.repository.ProductReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductReviewRepository productReviewRepository;
    private final CategoryService categoryService;

    @Transactional
    public ProductResponse create(ProductRequest request) {
        if (productRepository.existsBySku(request.getSku())) {
            throw new DuplicateResourceException("Product already exists with SKU: " + request.getSku());
        }
        Category category = categoryService.getEntity(request.getCategoryId());
        Product product = Product.builder()
                .name(request.getName())
                .description(request.getDescription())
                .price(request.getPrice())
                .sku(request.getSku())
                .stockQuantity(request.getStockQuantity())
                .active(request.getActive())
                .category(category)
                .build();
        return toResponse(productRepository.save(product));
    }

    public PageResponse<ProductResponse> findAll(Long categoryId, boolean activeOnly, Pageable pageable) {
        Page<Product> page;
        if (categoryId != null && activeOnly) {
            page = productRepository.findByCategoryIdAndActiveTrue(categoryId, pageable);
        } else if (categoryId != null) {
            page = productRepository.findByCategoryId(categoryId, pageable);
        } else if (activeOnly) {
            page = productRepository.findByActiveTrue(pageable);
        } else {
            page = productRepository.findAll(pageable);
        }
        return PageResponse.from(page.map(this::toResponse));
    }

    public ProductResponse findById(Long id) {
        return toResponse(getEntity(id));
    }

    public ProductResponse findBySku(String sku) {
        return productRepository.findBySku(sku)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with SKU: " + sku));
    }

    @Transactional
    public ProductResponse update(Long id, ProductRequest request) {
        Product product = getEntity(id);
        if (!product.getSku().equals(request.getSku()) && productRepository.existsBySku(request.getSku())) {
            throw new DuplicateResourceException("Product already exists with SKU: " + request.getSku());
        }
        if (!product.getCategory().getId().equals(request.getCategoryId())) {
            product.setCategory(categoryService.getEntity(request.getCategoryId()));
        }
        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());
        product.setSku(request.getSku());
        product.setStockQuantity(request.getStockQuantity());
        product.setActive(request.getActive());
        return toResponse(productRepository.save(product));
    }

    @Transactional
    public void delete(Long id) {
        Product product = getEntity(id);
        if (orderItemRepository.existsByProductId(id)) {
            throw new BusinessRuleException(
                    "Cannot delete product '" + product.getSku() + "': it appears on existing orders. Deactivate it instead");
        }
        if (productReviewRepository.existsByProductId(id)) {
            throw new BusinessRuleException(
                    "Cannot delete product '" + product.getSku() + "': it has customer reviews. Deactivate it instead");
        }
        productRepository.delete(product);
    }

    Product getEntity(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));
    }

    private ProductResponse toResponse(Product product) {
        return ProductResponse.builder()
                .id(product.getId())
                .name(product.getName())
                .description(product.getDescription())
                .price(product.getPrice())
                .sku(product.getSku())
                .stockQuantity(product.getStockQuantity())
                .active(product.getActive())
                .categoryId(product.getCategory().getId())
                .categoryName(product.getCategory().getName())
                .createdAt(product.getCreatedAt())
                .updatedAt(product.getUpdatedAt())
                .build();
    }
}
