package com.manish.ecommerce.api.controller;

import com.manish.ecommerce.api.dto.PageResponse;
import com.manish.ecommerce.api.dto.ReviewRequest;
import com.manish.ecommerce.api.dto.ReviewResponse;
import com.manish.ecommerce.api.exception.BusinessRuleException;
import com.manish.ecommerce.api.exception.ResourceNotFoundException;
import com.manish.ecommerce.api.service.ProductReviewService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductReviewController.class)
class ProductReviewControllerTest {

    private static final String REVIEWS_URL = "/api/v1/products/10/reviews";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ProductReviewService productReviewService;

    // --- POST: happy path ---------------------------------------------------

    @Test
    void should_return201WithBody_when_reviewIsCreated() throws Exception {
        // Arrange
        when(productReviewService.create(eq(10L), any(ReviewRequest.class))).thenReturn(reviewResponse(100L, 5));

        // Act
        mockMvc.perform(post(REVIEWS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(1L, 5, "Great mouse"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(100))
                .andExpect(jsonPath("$.productId").value(10))
                .andExpect(jsonPath("$.customerName").value("John Doe"))
                .andExpect(jsonPath("$.rating").value(5));

        // Assert
        ArgumentCaptor<ReviewRequest> captor = ArgumentCaptor.forClass(ReviewRequest.class);
        verify(productReviewService).create(eq(10L), captor.capture());
        assertThat(captor.getValue())
                .extracting(ReviewRequest::getCustomerId, ReviewRequest::getRating, ReviewRequest::getComment)
                .containsExactly(1L, 5, "Great mouse");
    }

    @ParameterizedTest(name = "rating {0} is accepted")
    @ValueSource(ints = {1, 5})
    void should_return201_when_ratingIsAtBoundary(int rating) throws Exception {
        // Arrange
        when(productReviewService.create(eq(10L), any(ReviewRequest.class))).thenReturn(reviewResponse(100L, rating));

        // Act & Assert
        mockMvc.perform(post(REVIEWS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(1L, rating, "ok"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rating").value(rating));
    }

    // --- POST: rating validation (issue #1) ---------------------------------

    @ParameterizedTest(name = "rating {0} is rejected")
    @ValueSource(ints = {0, 6, -1, 100})
    void should_return400WithRatingFieldError_when_ratingIsOutsideOneToFive(int rating) throws Exception {
        // Act & Assert
        mockMvc.perform(post(REVIEWS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(1L, rating, "ok"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("rating"))
                .andExpect(jsonPath("$.fieldErrors[0].rejectedValue").value(rating))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("rating must be between 1 and 5"));
        verify(productReviewService, never()).create(anyLong(), any());
    }

    @Test
    void should_return400WithRatingFieldError_when_ratingIsMissing() throws Exception {
        // Act & Assert
        mockMvc.perform(post(REVIEWS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":1,\"comment\":\"ok\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("rating"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("rating is required"));
        verify(productReviewService, never()).create(anyLong(), any());
    }

    @Test
    void should_return400_when_ratingIsNotAnInteger() throws Exception {
        // Act & Assert
        mockMvc.perform(post(REVIEWS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":1,\"rating\":\"five\",\"comment\":\"ok\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body"));
        verify(productReviewService, never()).create(anyLong(), any());
    }

    // --- POST: other validation / errors ------------------------------------

    @Test
    void should_return400WithAllFieldErrors_when_customerIdAndCommentAreMissing() throws Exception {
        // Act & Assert
        mockMvc.perform(post(REVIEWS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":3,\"comment\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem("customerId")))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem("comment")));
        verify(productReviewService, never()).create(anyLong(), any());
    }

    @Test
    void should_return404_when_productDoesNotExist() throws Exception {
        // Arrange
        when(productReviewService.create(eq(999L), any(ReviewRequest.class)))
                .thenThrow(new ResourceNotFoundException("Product", 999L));

        // Act & Assert
        mockMvc.perform(post("/api/v1/products/999/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(1L, 4, "ok"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Product not found with id: 999"))
                .andExpect(jsonPath("$.path").value("/api/v1/products/999/reviews"));
    }

    @Test
    void should_return404_when_customerDoesNotExist() throws Exception {
        // Arrange
        when(productReviewService.create(eq(10L), any(ReviewRequest.class)))
                .thenThrow(new ResourceNotFoundException("Customer", 999L));

        // Act & Assert
        mockMvc.perform(post(REVIEWS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(999L, 4, "ok"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Customer not found with id: 999"));
    }

    @Test
    void should_return422_when_customerHasNoDeliveredOrderForProduct() throws Exception {
        // Arrange
        when(productReviewService.create(eq(10L), any(ReviewRequest.class)))
                .thenThrow(new BusinessRuleException("Customer 1 cannot review product 'ELEC-MOU-001': no delivered order contains it"));

        // Act & Assert
        mockMvc.perform(post(REVIEWS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(1L, 4, "ok"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Customer 1 cannot review product 'ELEC-MOU-001': no delivered order contains it"));
    }

    // --- GET ----------------------------------------------------------------

    @Test
    void should_return200WithNewestFirstPageable_when_listingReviews() throws Exception {
        // Arrange
        when(productReviewService.findByProduct(eq(10L), any(Pageable.class)))
                .thenReturn(PageResponse.from(new PageImpl<>(List.of(reviewResponse(2L, 4), reviewResponse(1L, 5)))));

        // Act
        mockMvc.perform(get(REVIEWS_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(2))
                .andExpect(jsonPath("$.content[1].id").value(1));

        // Assert
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(productReviewService).findByProduct(eq(10L), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
        assertThat(captor.getValue().getSort().getOrderFor("createdAt")).isNotNull();
        assertThat(captor.getValue().getSort().getOrderFor("createdAt").isDescending()).isTrue();
    }

    @Test
    void should_return404_when_listingReviewsForUnknownProduct() throws Exception {
        // Arrange
        when(productReviewService.findByProduct(eq(999L), any(Pageable.class)))
                .thenThrow(new ResourceNotFoundException("Product", 999L));

        // Act & Assert
        mockMvc.perform(get("/api/v1/products/999/reviews"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Product not found with id: 999"));
    }

    // --- fixtures -----------------------------------------------------------

    private static ReviewRequest request(Long customerId, int rating, String comment) {
        return ReviewRequest.builder().customerId(customerId).rating(rating).comment(comment).build();
    }

    private static ReviewResponse reviewResponse(Long id, int rating) {
        return ReviewResponse.builder()
                .id(id).productId(10L).customerId(1L).customerName("John Doe")
                .rating(rating).comment("Great mouse").createdAt(LocalDateTime.of(2026, 9, 14, 10, 0))
                .build();
    }
}
