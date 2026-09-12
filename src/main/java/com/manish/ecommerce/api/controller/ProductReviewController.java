package com.manish.ecommerce.api.controller;

import com.manish.ecommerce.api.dto.PageResponse;
import com.manish.ecommerce.api.dto.ReviewRequest;
import com.manish.ecommerce.api.dto.ReviewResponse;
import com.manish.ecommerce.api.service.ProductReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products/{productId}/reviews")
@RequiredArgsConstructor
public class ProductReviewController {

    private final ProductReviewService productReviewService;

    @PostMapping
    public ResponseEntity<ReviewResponse> create(@PathVariable Long productId,
                                                 @Valid @RequestBody ReviewRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productReviewService.create(productId, request));
    }

    @GetMapping
    public ResponseEntity<PageResponse<ReviewResponse>> findByProduct(
            @PathVariable Long productId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(productReviewService.findByProduct(productId, pageable));
    }
}
