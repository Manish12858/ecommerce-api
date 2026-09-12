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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    private static final Category ELECTRONICS = Category.builder().id(1L).name("Electronics").slug("electronics").build();
    private static final Category BOOKS = Category.builder().id(2L).name("Books").slug("books").build();

    @Mock
    private ProductRepository productRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private ProductReviewRepository productReviewRepository;

    @Mock
    private CategoryService categoryService;

    @InjectMocks
    private ProductService productService;

    // --- create -------------------------------------------------------------

    @Test
    void should_createProduct_when_skuIsUnique() {
        // Arrange
        ProductRequest request = request("ELEC-MOU-001", 1L);
        when(productRepository.existsBySku("ELEC-MOU-001")).thenReturn(false);
        when(categoryService.getEntity(1L)).thenReturn(ELECTRONICS);
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
            Product p = inv.getArgument(0);
            p.setId(10L);
            return p;
        });

        // Act
        ProductResponse response = productService.create(request);

        // Assert
        assertThat(response)
                .extracting(ProductResponse::getId, ProductResponse::getSku, ProductResponse::getPrice,
                        ProductResponse::getCategoryName)
                .containsExactly(10L, "ELEC-MOU-001", new BigDecimal("24.99"), "Electronics");
    }

    @Test
    void should_throwDuplicateResource_when_skuAlreadyExists() {
        // Arrange
        ProductRequest request = request("ELEC-MOU-001", 1L);
        when(productRepository.existsBySku("ELEC-MOU-001")).thenReturn(true);

        // Act & Assert
        assertThatThrownBy(() -> productService.create(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("ELEC-MOU-001");
        verify(productRepository, never()).save(any());
    }

    @Test
    void should_throwResourceNotFound_when_creatingWithUnknownCategory() {
        // Arrange
        ProductRequest request = request("ELEC-MOU-001", 99L);
        when(productRepository.existsBySku("ELEC-MOU-001")).thenReturn(false);
        when(categoryService.getEntity(99L)).thenThrow(new ResourceNotFoundException("Category", 99L));

        // Act & Assert
        assertThatThrownBy(() -> productService.create(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Category");
    }

    // --- read ---------------------------------------------------------------

    @Test
    void should_returnActiveProductsInCategory_when_bothFiltersGiven() {
        // Arrange
        Pageable pageable = PageRequest.of(0, 10);
        when(productRepository.findByCategoryIdAndActiveTrue(1L, pageable))
                .thenReturn(new PageImpl<>(List.of(product(10L, "ELEC-MOU-001", 150)), pageable, 1));

        // Act
        PageResponse<ProductResponse> page = productService.findAll(1L, true, pageable);

        // Assert
        assertThat(page.getContent()).singleElement()
                .extracting(ProductResponse::getSku).isEqualTo("ELEC-MOU-001");
    }

    @Test
    void should_returnAllProducts_when_noFiltersGiven() {
        // Arrange
        Pageable pageable = PageRequest.of(0, 10);
        when(productRepository.findAll(pageable)).thenReturn(new PageImpl<>(
                List.of(product(10L, "A", 1), product(11L, "B", 2)), pageable, 2));

        // Act
        PageResponse<ProductResponse> page = productService.findAll(null, false, pageable);

        // Assert
        assertThat(page).extracting(PageResponse::getTotalElements, PageResponse::isLast).containsExactly(2L, true);
    }

    @Test
    void should_returnProduct_when_idExists() {
        // Arrange
        when(productRepository.findById(10L)).thenReturn(Optional.of(product(10L, "ELEC-MOU-001", 150)));

        // Act
        ProductResponse response = productService.findById(10L);

        // Assert
        assertThat(response.getSku()).isEqualTo("ELEC-MOU-001");
    }

    @Test
    void should_throwResourceNotFound_when_idDoesNotExist() {
        // Arrange
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> productService.findById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void should_throwResourceNotFound_when_skuDoesNotExist() {
        // Arrange
        when(productRepository.findBySku("NOPE")).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> productService.findBySku("NOPE"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("NOPE");
    }

    // --- update -------------------------------------------------------------

    @Test
    void should_updateProductAndSwitchCategory_when_categoryChanges() {
        // Arrange
        Product existing = product(10L, "ELEC-MOU-001", 150);
        when(productRepository.findById(10L)).thenReturn(Optional.of(existing));
        when(categoryService.getEntity(2L)).thenReturn(BOOKS);
        when(productRepository.save(existing)).thenReturn(existing);

        // Act
        ProductResponse response = productService.update(10L, request("ELEC-MOU-001", 2L));

        // Assert
        assertThat(response)
                .extracting(ProductResponse::getCategoryId, ProductResponse::getCategoryName)
                .containsExactly(2L, "Books");
    }

    @Test
    void should_throwDuplicateResource_when_updatingToExistingSku() {
        // Arrange
        when(productRepository.findById(10L)).thenReturn(Optional.of(product(10L, "ELEC-MOU-001", 150)));
        when(productRepository.existsBySku("ELEC-KEY-002")).thenReturn(true);

        // Act & Assert
        assertThatThrownBy(() -> productService.update(10L, request("ELEC-KEY-002", 1L)))
                .isInstanceOf(DuplicateResourceException.class);
    }

    // --- delete -------------------------------------------------------------

    @Test
    void should_deleteProduct_when_notOnAnyOrder() {
        // Arrange
        Product existing = product(10L, "ELEC-MOU-001", 150);
        when(productRepository.findById(10L)).thenReturn(Optional.of(existing));
        when(orderItemRepository.existsByProductId(10L)).thenReturn(false);
        when(productReviewRepository.existsByProductId(10L)).thenReturn(false);

        // Act
        productService.delete(10L);

        // Assert
        verify(productRepository).delete(existing);
    }

    @Test
    void should_throwBusinessRule_when_deletingProductOnExistingOrders() {
        // Arrange
        when(productRepository.findById(10L)).thenReturn(Optional.of(product(10L, "ELEC-MOU-001", 150)));
        when(orderItemRepository.existsByProductId(10L)).thenReturn(true);

        // Act & Assert
        assertThatThrownBy(() -> productService.delete(10L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("existing orders");
        verify(productRepository, never()).delete(any());
    }

    @Test
    void should_throwBusinessRule_when_deletingProductWithReviews() {
        // Arrange
        when(productRepository.findById(10L)).thenReturn(Optional.of(product(10L, "ELEC-MOU-001", 150)));
        when(orderItemRepository.existsByProductId(10L)).thenReturn(false);
        when(productReviewRepository.existsByProductId(10L)).thenReturn(true);

        // Act & Assert
        assertThatThrownBy(() -> productService.delete(10L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("customer reviews");
        verify(productRepository, never()).delete(any());
    }

    // --- fixtures -----------------------------------------------------------

    private static ProductRequest request(String sku, Long categoryId) {
        return ProductRequest.builder()
                .name("Wireless Mouse").description("desc").price(new BigDecimal("24.99"))
                .sku(sku).stockQuantity(150).active(true).categoryId(categoryId)
                .build();
    }

    private static Product product(Long id, String sku, int stock) {
        return Product.builder()
                .id(id).name("Wireless Mouse").price(new BigDecimal("24.99")).sku(sku)
                .stockQuantity(stock).active(true).category(ELECTRONICS)
                .build();
    }
}
