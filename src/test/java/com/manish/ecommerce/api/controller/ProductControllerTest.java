package com.manish.ecommerce.api.controller;

import com.manish.ecommerce.api.dto.PageResponse;
import com.manish.ecommerce.api.dto.ProductRequest;
import com.manish.ecommerce.api.dto.ProductResponse;
import com.manish.ecommerce.api.exception.BusinessRuleException;
import com.manish.ecommerce.api.exception.DuplicateResourceException;
import com.manish.ecommerce.api.exception.ResourceNotFoundException;
import com.manish.ecommerce.api.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ProductService productService;

    @Test
    void should_return201WithBody_when_productIsCreated() throws Exception {
        // Arrange
        ProductRequest request = validRequest();
        when(productService.create(any(ProductRequest.class))).thenReturn(productResponse(10L));

        // Act & Assert
        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.sku").value("ELEC-MOU-001"))
                .andExpect(jsonPath("$.price").value(24.99))
                .andExpect(jsonPath("$.categoryId").value(1))
                .andExpect(jsonPath("$.categoryName").value("Electronics"));

        org.mockito.ArgumentCaptor<ProductRequest> captor = org.mockito.ArgumentCaptor.forClass(ProductRequest.class);
        verify(productService).create(captor.capture());
        ProductRequest captured = captor.getValue();
        assertThat(captured.getName()).isEqualTo("Wireless Mouse");
        assertThat(captured.getSku()).isEqualTo("ELEC-MOU-001");
        assertThat(captured.getPrice()).isEqualTo(new BigDecimal("24.99"));
        assertThat(captured.getCategoryId()).isEqualTo(1L);
    }

    @Test
    void should_return400WithFieldErrors_when_requiredFieldsAreMissing() throws Exception {
        // Arrange
        String invalidJson = """
                {
                  "name": "",
                  "sku": "ELEC-MOU-001",
                  "stockQuantity": 10
                }
                """;

        // Act & Assert
        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItems("name", "price", "categoryId")));

        verify(productService, never()).create(any());
    }

    @Test
    void should_return400WithFieldError_when_priceIsNegative() throws Exception {
        // Arrange
        String invalidJson = """
                {
                  "name": "Wireless Mouse",
                  "price": -5.00,
                  "sku": "ELEC-MOU-001",
                  "stockQuantity": 10,
                  "categoryId": 1
                }
                """;

        // Act & Assert
        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", containsInAnyOrder("price")));

        verify(productService, never()).create(any());
    }

    @Test
    void should_return400_when_requestBodyIsMalformed() throws Exception {
        // Arrange
        String malformedJson = "{ \"name\": \"Wireless Mouse\", ";

        // Act & Assert
        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(malformedJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body"));
    }

    @Test
    void should_return409_when_skuAlreadyExists() throws Exception {
        // Arrange
        when(productService.create(any(ProductRequest.class)))
                .thenThrow(new DuplicateResourceException("Product already exists with SKU: ELEC-MOU-001"));

        // Act & Assert
        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Product already exists with SKU: ELEC-MOU-001"));
    }

    @Test
    void should_return404_when_categoryNotFoundOnCreate() throws Exception {
        // Arrange
        when(productService.create(any(ProductRequest.class)))
                .thenThrow(new ResourceNotFoundException("Category", 99L));

        // Act & Assert
        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Category not found with id: 99"));
    }

    @Test
    void should_return200WithDefaultPageable_when_findAllCalledWithNoParams() throws Exception {
        // Arrange
        Page<ProductResponse> page = new PageImpl<>(List.of(productResponse(1L)));
        when(productService.findAll(isNull(), eq(false), any(Pageable.class))).thenReturn(PageResponse.from(page));

        // Act & Assert
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(1));

        org.mockito.ArgumentCaptor<Pageable> captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(productService).findAll(isNull(), eq(false), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
        assertThat(captor.getValue().getSort().getOrderFor("name")).isNotNull();
        assertThat(captor.getValue().getSort().getOrderFor("name").isAscending()).isTrue();
    }

    @Test
    void should_passCategoryIdAndActiveOnly_when_findAllCalledWithParams() throws Exception {
        // Arrange
        Page<ProductResponse> page = new PageImpl<>(List.of(productResponse(1L)));
        when(productService.findAll(eq(1L), eq(true), any(Pageable.class))).thenReturn(PageResponse.from(page));

        // Act & Assert
        mockMvc.perform(get("/api/v1/products").queryParam("categoryId", "1").queryParam("activeOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].categoryId").value(1));

        verify(productService).findAll(eq(1L), eq(true), any(Pageable.class));
    }

    @Test
    void should_useRequestedPageAndSort_when_findAllCalledWithPagingParams() throws Exception {
        // Arrange
        Page<ProductResponse> page = new PageImpl<>(List.of(productResponse(1L)));
        when(productService.findAll(isNull(), eq(false), any(Pageable.class))).thenReturn(PageResponse.from(page));

        // Act & Assert
        mockMvc.perform(get("/api/v1/products")
                        .queryParam("page", "1")
                        .queryParam("size", "5")
                        .queryParam("sort", "price,desc"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<Pageable> captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(productService).findAll(isNull(), eq(false), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(1);
        assertThat(captor.getValue().getPageSize()).isEqualTo(5);
        assertThat(captor.getValue().getSort().getOrderFor("price").isDescending()).isTrue();
    }

    @Test
    void should_return200_when_productFoundById() throws Exception {
        // Arrange
        when(productService.findById(1L)).thenReturn(productResponse(1L));

        // Act & Assert
        mockMvc.perform(get("/api/v1/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void should_return404_when_productNotFoundById() throws Exception {
        // Arrange
        when(productService.findById(99L)).thenThrow(new ResourceNotFoundException("Product", 99L));

        // Act & Assert
        mockMvc.perform(get("/api/v1/products/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Product not found with id: 99"));
    }

    @Test
    void should_return200_when_productFoundBySku() throws Exception {
        // Arrange
        when(productService.findBySku("ELEC-MOU-001")).thenReturn(productResponse(1L));

        // Act & Assert
        mockMvc.perform(get("/api/v1/products/sku/ELEC-MOU-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("ELEC-MOU-001"));
    }

    @Test
    void should_return404_when_productNotFoundBySku() throws Exception {
        // Arrange
        when(productService.findBySku("UNKNOWN-SKU")).thenThrow(new ResourceNotFoundException("Product not found with SKU: UNKNOWN-SKU"));

        // Act & Assert
        mockMvc.perform(get("/api/v1/products/sku/UNKNOWN-SKU"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Product not found with SKU: UNKNOWN-SKU"));
    }

    @Test
    void should_return200WithUpdatedBody_when_productIsUpdated() throws Exception {
        // Arrange
        when(productService.update(eq(1L), any(ProductRequest.class))).thenReturn(productResponse(1L));

        // Act & Assert
        mockMvc.perform(put("/api/v1/products/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));

        verify(productService).update(eq(1L), any(ProductRequest.class));
    }

    @Test
    void should_return400_when_updateRequestFailsValidation() throws Exception {
        // Arrange
        String invalidJson = """
                {
                  "name": "",
                  "sku": "ELEC-MOU-001",
                  "stockQuantity": 10
                }
                """;

        // Act & Assert
        mockMvc.perform(put("/api/v1/products/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"));

        verify(productService, never()).update(anyLong(), any());
    }

    @Test
    void should_return204_when_productIsDeleted() throws Exception {
        // Act & Assert
        mockMvc.perform(delete("/api/v1/products/1"))
                .andExpect(status().isNoContent());

        verify(productService).delete(1L);
    }

    @Test
    void should_return422_when_productHasExistingOrdersOnDelete() throws Exception {
        // Arrange
        org.mockito.Mockito.doThrow(new BusinessRuleException("Cannot delete product 'Wireless Mouse': it has existing orders"))
                .when(productService).delete(1L);

        // Act & Assert
        mockMvc.perform(delete("/api/v1/products/1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Cannot delete product 'Wireless Mouse': it has existing orders"));
    }

    // --- fixtures -------------------------------------------------------

    private static ProductRequest validRequest() {
        return ProductRequest.builder()
                .name("Wireless Mouse")
                .description("Ergonomic wireless mouse")
                .price(new BigDecimal("24.99"))
                .sku("ELEC-MOU-001")
                .stockQuantity(50)
                .active(true)
                .categoryId(1L)
                .build();
    }

    private static ProductResponse productResponse(Long id) {
        return ProductResponse.builder()
                .id(id)
                .name("Wireless Mouse")
                .description("Ergonomic wireless mouse")
                .price(new BigDecimal("24.99"))
                .sku("ELEC-MOU-001")
                .stockQuantity(50)
                .active(true)
                .categoryId(1L)
                .categoryName("Electronics")
                .build();
    }
}
