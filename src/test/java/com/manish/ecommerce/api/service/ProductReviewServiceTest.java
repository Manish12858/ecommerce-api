package com.manish.ecommerce.api.service;

import com.manish.ecommerce.api.dto.PageResponse;
import com.manish.ecommerce.api.dto.ReviewRequest;
import com.manish.ecommerce.api.dto.ReviewResponse;
import com.manish.ecommerce.api.entity.Customer;
import com.manish.ecommerce.api.entity.OrderStatus;
import com.manish.ecommerce.api.entity.Product;
import com.manish.ecommerce.api.entity.ProductReview;
import com.manish.ecommerce.api.exception.BusinessRuleException;
import com.manish.ecommerce.api.exception.ResourceNotFoundException;
import com.manish.ecommerce.api.repository.OrderItemRepository;
import com.manish.ecommerce.api.repository.ProductReviewRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductReviewServiceTest {

    private static final Product MOUSE = Product.builder()
            .id(10L).name("Wireless Mouse").sku("ELEC-MOU-001").price(new BigDecimal("24.99")).build();
    private static final Customer JOHN = Customer.builder()
            .id(1L).firstName("John").lastName("Doe").email("john.doe@example.com").build();

    @Mock
    private ProductReviewRepository productReviewRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private ProductService productService;

    @Mock
    private CustomerService customerService;

    @InjectMocks
    private ProductReviewService productReviewService;

    // --- create -------------------------------------------------------------

    @Test
    void should_createReview_when_customerHasDeliveredOrderForProduct() {
        // Arrange
        when(productService.getEntity(10L)).thenReturn(MOUSE);
        when(customerService.getEntity(1L)).thenReturn(JOHN);
        when(orderItemRepository.existsByOrderCustomerIdAndProductIdAndOrderStatus(1L, 10L, OrderStatus.DELIVERED))
                .thenReturn(true);
        when(productReviewRepository.save(any(ProductReview.class))).thenAnswer(inv -> {
            ProductReview r = inv.getArgument(0);
            r.setId(100L);
            return r;
        });

        // Act
        ReviewResponse response = productReviewService.create(10L, request(1L, 5, "Great mouse"));

        // Assert
        ArgumentCaptor<ProductReview> captor = ArgumentCaptor.forClass(ProductReview.class);
        verify(productReviewRepository).save(captor.capture());
        assertThat(captor.getValue().getProduct()).isSameAs(MOUSE);
        assertThat(captor.getValue().getCustomer()).isSameAs(JOHN);
        assertThat(response)
                .extracting(ReviewResponse::getId, ReviewResponse::getProductId, ReviewResponse::getCustomerId,
                        ReviewResponse::getCustomerName, ReviewResponse::getRating, ReviewResponse::getComment)
                .containsExactly(100L, 10L, 1L, "John Doe", 5, "Great mouse");
    }

    @Test
    void should_throwNotFound_when_productDoesNotExist() {
        // Arrange
        when(productService.getEntity(999L)).thenThrow(new ResourceNotFoundException("Product", 999L));

        // Act & Assert
        assertThatThrownBy(() -> productReviewService.create(999L, request(1L, 4, "Nice")))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Product not found with id: 999");
        verify(productReviewRepository, never()).save(any());
    }

    @Test
    void should_throwNotFound_when_customerDoesNotExist() {
        // Arrange
        when(productService.getEntity(10L)).thenReturn(MOUSE);
        when(customerService.getEntity(999L)).thenThrow(new ResourceNotFoundException("Customer", 999L));

        // Act & Assert
        assertThatThrownBy(() -> productReviewService.create(10L, request(999L, 4, "Nice")))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Customer not found with id: 999");
        verify(productReviewRepository, never()).save(any());
    }

    @Test
    void should_throwBusinessRule_when_customerHasNoDeliveredOrderForProduct() {
        // Arrange
        when(productService.getEntity(10L)).thenReturn(MOUSE);
        when(customerService.getEntity(1L)).thenReturn(JOHN);
        when(orderItemRepository.existsByOrderCustomerIdAndProductIdAndOrderStatus(1L, 10L, OrderStatus.DELIVERED))
                .thenReturn(false);

        // Act & Assert
        assertThatThrownBy(() -> productReviewService.create(10L, request(1L, 2, "Never arrived")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("no delivered order contains it");
        verify(productReviewRepository, never()).save(any());
    }

    // --- findByProduct ------------------------------------------------------

    @Test
    void should_returnPagedReviews_when_productExists() {
        // Arrange
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        ProductReview newer = review(2L, 4, "Solid", LocalDateTime.of(2026, 9, 12, 10, 0));
        ProductReview older = review(1L, 5, "Great mouse", LocalDateTime.of(2026, 9, 1, 10, 0));
        when(productService.getEntity(10L)).thenReturn(MOUSE);
        when(productReviewRepository.findByProductId(10L, pageable))
                .thenReturn(new PageImpl<>(List.of(newer, older), pageable, 2));

        // Act
        PageResponse<ReviewResponse> page = productReviewService.findByProduct(10L, pageable);

        // Assert
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent())
                .extracting(ReviewResponse::getId, ReviewResponse::getRating, ReviewResponse::getCustomerName)
                .containsExactly(
                        tuple(2L, 4, "John Doe"),
                        tuple(1L, 5, "John Doe"));
    }

    @Test
    void should_throwNotFound_when_listingReviewsForUnknownProduct() {
        // Arrange
        when(productService.getEntity(999L)).thenThrow(new ResourceNotFoundException("Product", 999L));

        // Act & Assert
        assertThatThrownBy(() -> productReviewService.findByProduct(999L, PageRequest.of(0, 20)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Product not found with id: 999");
        verify(productReviewRepository, never()).findByProductId(anyLong(), any());
    }

    // --- fixtures -----------------------------------------------------------

    private static ReviewRequest request(Long customerId, int rating, String comment) {
        return ReviewRequest.builder().customerId(customerId).rating(rating).comment(comment).build();
    }

    private static ProductReview review(Long id, int rating, String comment, LocalDateTime createdAt) {
        return ProductReview.builder()
                .id(id).product(MOUSE).customer(JOHN).rating(rating).comment(comment).createdAt(createdAt)
                .build();
    }
}
