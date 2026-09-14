package com.manish.ecommerce.api.controller;

import com.manish.ecommerce.api.dto.CategoryRequest;
import com.manish.ecommerce.api.dto.CategoryResponse;
import com.manish.ecommerce.api.exception.BusinessRuleException;
import com.manish.ecommerce.api.exception.DuplicateResourceException;
import com.manish.ecommerce.api.exception.ResourceNotFoundException;
import com.manish.ecommerce.api.service.CategoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CategoryController.class)
class CategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CategoryService categoryService;

    @Test
    void should_return201WithBody_when_categoryIsCreated() throws Exception {
        // Arrange
        CategoryRequest request = validRequest();
        when(categoryService.create(any(CategoryRequest.class))).thenReturn(categoryResponse(10L));

        // Act & Assert
        mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.name").value("Electronics"))
                .andExpect(jsonPath("$.slug").value("electronics"));

        org.mockito.ArgumentCaptor<CategoryRequest> captor = org.mockito.ArgumentCaptor.forClass(CategoryRequest.class);
        verify(categoryService).create(captor.capture());
        CategoryRequest captured = captor.getValue();
        assertThat(captured.getName()).isEqualTo("Electronics");
        assertThat(captured.getSlug()).isEqualTo("electronics");
    }

    @Test
    void should_return400WithFieldError_when_nameIsBlank() throws Exception {
        // Arrange
        String invalidJson = """
                {
                  "name": "",
                  "slug": "electronics",
                  "description": "Electronic goods"
                }
                """;

        // Act & Assert
        mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItems("name")));

        verify(categoryService, never()).create(any());
    }

    @Test
    void should_return400_when_requestBodyIsMalformed() throws Exception {
        // Arrange
        String malformedJson = "{ \"name\": \"Electronics\", ";

        // Act & Assert
        mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(malformedJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body"));
    }

    @Test
    void should_return409_when_categoryNameAlreadyExists() throws Exception {
        // Arrange
        when(categoryService.create(any(CategoryRequest.class)))
                .thenThrow(new DuplicateResourceException("Category already exists with name: Electronics"));

        // Act & Assert
        mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already exists")))
                .andExpect(jsonPath("$.path").value("/api/v1/categories"));
    }

    @Test
    void should_return200WithArray_when_findAllCalled() throws Exception {
        // Arrange
        when(categoryService.findAll()).thenReturn(List.of(categoryResponse(1L), categoryResponse(2L)));

        // Act & Assert
        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$[0].slug").value("electronics"));
    }

    @Test
    void should_return200_when_categoryFoundById() throws Exception {
        // Arrange
        when(categoryService.findById(1L)).thenReturn(categoryResponse(1L));

        // Act & Assert
        mockMvc.perform(get("/api/v1/categories/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void should_return404_when_categoryNotFoundById() throws Exception {
        // Arrange
        when(categoryService.findById(99L)).thenThrow(new ResourceNotFoundException("Category", 99L));

        // Act & Assert
        mockMvc.perform(get("/api/v1/categories/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Category not found with id: 99"));
    }

    @Test
    void should_return200_when_categoryFoundBySlug() throws Exception {
        // Arrange
        when(categoryService.findBySlug("electronics")).thenReturn(categoryResponse(1L));

        // Act & Assert
        mockMvc.perform(get("/api/v1/categories/slug/electronics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("electronics"));
    }

    @Test
    void should_return404_when_categoryNotFoundBySlug() throws Exception {
        // Arrange
        when(categoryService.findBySlug("bogus")).thenThrow(new ResourceNotFoundException("Category not found with slug: bogus"));

        // Act & Assert
        mockMvc.perform(get("/api/v1/categories/slug/bogus"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Category not found with slug: bogus"));
    }

    @Test
    void should_return200WithUpdatedName_when_categoryIsUpdated() throws Exception {
        // Arrange
        CategoryRequest request = CategoryRequest.builder()
                .name("Consumer Electronics")
                .slug("electronics")
                .description("Electronic goods")
                .build();
        CategoryResponse response = categoryResponse(1L);
        response.setName("Consumer Electronics");
        when(categoryService.update(eq(1L), any(CategoryRequest.class))).thenReturn(response);

        // Act & Assert
        mockMvc.perform(put("/api/v1/categories/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Consumer Electronics"));

        verify(categoryService).update(1L, request);
    }

    @Test
    void should_return400WithFieldError_when_updateNameIsBlank() throws Exception {
        // Arrange
        String invalidJson = """
                {
                  "name": "",
                  "slug": "electronics",
                  "description": "Electronic goods"
                }
                """;

        // Act & Assert
        mockMvc.perform(put("/api/v1/categories/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItems("name")));

        verify(categoryService, never()).update(any(), any());
    }

    @Test
    void should_return204_when_categoryIsDeleted() throws Exception {
        // Act & Assert
        mockMvc.perform(delete("/api/v1/categories/1"))
                .andExpect(status().isNoContent())
                .andExpect(jsonPath("$").doesNotExist());

        verify(categoryService).delete(1L);
    }

    @Test
    void should_return422_when_categoryHasProductsOnDelete() throws Exception {
        // Arrange
        org.mockito.Mockito.doThrow(new BusinessRuleException("Cannot delete category 'Electronics': has products"))
                .when(categoryService).delete(1L);

        // Act & Assert
        mockMvc.perform(delete("/api/v1/categories/1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Cannot delete category 'Electronics': has products"));
    }

    // --- fixtures -------------------------------------------------------

    private static CategoryRequest validRequest() {
        return CategoryRequest.builder()
                .name("Electronics")
                .slug("electronics")
                .description("Electronic goods")
                .build();
    }

    private static CategoryResponse categoryResponse(Long id) {
        return CategoryResponse.builder()
                .id(id)
                .name("Electronics")
                .slug("electronics")
                .description("Electronic goods")
                .build();
    }
}
