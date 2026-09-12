package com.manish.ecommerce.api.service;

import com.manish.ecommerce.api.dto.PageResponse;
import com.manish.ecommerce.api.dto.ReviewRequest;
import com.manish.ecommerce.api.dto.ReviewResponse;
import com.manish.ecommerce.api.entity.Customer;
import com.manish.ecommerce.api.entity.OrderStatus;
import com.manish.ecommerce.api.entity.Product;
import com.manish.ecommerce.api.entity.ProductReview;
import com.manish.ecommerce.api.exception.BusinessRuleException;
import com.manish.ecommerce.api.repository.OrderItemRepository;
import com.manish.ecommerce.api.repository.ProductReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductReviewService {

    private final ProductReviewRepository productReviewRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductService productService;
    private final CustomerService customerService;

    @Transactional
    public ReviewResponse create(Long productId, ReviewRequest request) {
        Product product = productService.getEntity(productId);
        Customer customer = customerService.getEntity(request.getCustomerId());
        assertVerifiedPurchase(customer, product);
        ProductReview review = ProductReview.builder()
                .product(product)
                .customer(customer)
                .rating(request.getRating())
                .comment(request.getComment())
                .build();
        return toResponse(productReviewRepository.save(review));
    }

    public PageResponse<ReviewResponse> findByProduct(Long productId, Pageable pageable) {
        productService.getEntity(productId);
        return PageResponse.from(productReviewRepository.findByProductId(productId, pageable).map(this::toResponse));
    }

    /** Only customers who have received the product (a DELIVERED order containing it) may review it. */
    private void assertVerifiedPurchase(Customer customer, Product product) {
        boolean delivered = orderItemRepository.existsByOrderCustomerIdAndProductIdAndOrderStatus(
                customer.getId(), product.getId(), OrderStatus.DELIVERED);
        if (!delivered) {
            throw new BusinessRuleException("Customer " + customer.getId() + " cannot review product '"
                    + product.getSku() + "': no delivered order contains it");
        }
    }

    private ReviewResponse toResponse(ProductReview review) {
        Customer customer = review.getCustomer();
        return ReviewResponse.builder()
                .id(review.getId())
                .productId(review.getProduct().getId())
                .customerId(customer.getId())
                .customerName(customer.getFirstName() + " " + customer.getLastName())
                .rating(review.getRating())
                .comment(review.getComment())
                .createdAt(review.getCreatedAt())
                .build();
    }
}
