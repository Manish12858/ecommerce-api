package com.manish.ecommerce.api.service;

import com.manish.ecommerce.api.dto.CategoryRequest;
import com.manish.ecommerce.api.dto.CategoryResponse;
import com.manish.ecommerce.api.entity.Category;
import com.manish.ecommerce.api.exception.BusinessRuleException;
import com.manish.ecommerce.api.exception.DuplicateResourceException;
import com.manish.ecommerce.api.exception.ResourceNotFoundException;
import com.manish.ecommerce.api.repository.CategoryRepository;
import com.manish.ecommerce.api.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private CategoryService categoryService;

    // --- create -------------------------------------------------------------

    @Test
    void should_createCategory_when_nameAndSlugAreUnique() {
        // Arrange
        CategoryRequest request = request("Electronics", "electronics");
        when(categoryRepository.existsByName("Electronics")).thenReturn(false);
        when(categoryRepository.existsBySlug("electronics")).thenReturn(false);
        when(categoryRepository.save(any(Category.class))).thenAnswer(inv -> {
            Category c = inv.getArgument(0);
            c.setId(1L);
            return c;
        });

        // Act
        CategoryResponse response = categoryService.create(request);

        // Assert
        assertThat(response)
                .extracting(CategoryResponse::getId, CategoryResponse::getName, CategoryResponse::getSlug)
                .containsExactly(1L, "Electronics", "electronics");
    }

    @Test
    void should_throwDuplicateResource_when_categoryNameAlreadyExists() {
        // Arrange
        CategoryRequest request = request("Electronics", "electronics");
        when(categoryRepository.existsByName("Electronics")).thenReturn(true);

        // Act & Assert
        assertThatThrownBy(() -> categoryService.create(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("Electronics");
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void should_throwDuplicateResource_when_categorySlugAlreadyExists() {
        // Arrange
        CategoryRequest request = request("Electronics", "electronics");
        when(categoryRepository.existsByName("Electronics")).thenReturn(false);
        when(categoryRepository.existsBySlug("electronics")).thenReturn(true);

        // Act & Assert
        assertThatThrownBy(() -> categoryService.create(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("electronics");
    }

    // --- read ---------------------------------------------------------------

    @Test
    void should_returnAllCategories_when_findAllCalled() {
        // Arrange
        when(categoryRepository.findAll()).thenReturn(List.of(
                category(1L, "Books", "books"),
                category(2L, "Clothing", "clothing")));

        // Act
        List<CategoryResponse> result = categoryService.findAll();

        // Assert
        assertThat(result).extracting(CategoryResponse::getSlug).containsExactly("books", "clothing");
    }

    @Test
    void should_returnCategory_when_idExists() {
        // Arrange
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category(1L, "Books", "books")));

        // Act
        CategoryResponse response = categoryService.findById(1L);

        // Assert
        assertThat(response.getName()).isEqualTo("Books");
    }

    @Test
    void should_throwResourceNotFound_when_idDoesNotExist() {
        // Arrange
        when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> categoryService.findById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void should_throwResourceNotFound_when_slugDoesNotExist() {
        // Arrange
        when(categoryRepository.findBySlug("missing")).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> categoryService.findBySlug("missing"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("missing");
    }

    // --- update -------------------------------------------------------------

    @Test
    void should_updateCategory_when_newNameIsUnique() {
        // Arrange
        Category existing = category(1L, "Books", "books");
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(categoryRepository.existsByName("Literature")).thenReturn(false);
        when(categoryRepository.save(existing)).thenReturn(existing);

        // Act
        CategoryResponse response = categoryService.update(1L, request("Literature", "books"));

        // Assert
        assertThat(response)
                .extracting(CategoryResponse::getName, CategoryResponse::getSlug)
                .containsExactly("Literature", "books");
    }

    @Test
    void should_throwDuplicateResource_when_updatingToExistingName() {
        // Arrange
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category(1L, "Books", "books")));
        when(categoryRepository.existsByName("Clothing")).thenReturn(true);

        // Act & Assert
        assertThatThrownBy(() -> categoryService.update(1L, request("Clothing", "books")))
                .isInstanceOf(DuplicateResourceException.class);
    }

    // --- delete -------------------------------------------------------------

    @Test
    void should_deleteCategory_when_itHasNoProducts() {
        // Arrange
        Category existing = category(1L, "Books", "books");
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(productRepository.existsByCategoryId(1L)).thenReturn(false);

        // Act
        categoryService.delete(1L);

        // Assert
        verify(categoryRepository).delete(existing);
    }

    @Test
    void should_throwBusinessRule_when_deletingCategoryWithProducts() {
        // Arrange
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category(1L, "Books", "books")));
        when(productRepository.existsByCategoryId(1L)).thenReturn(true);

        // Act & Assert
        assertThatThrownBy(() -> categoryService.delete(1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("still has products");
        verify(categoryRepository, never()).delete(any());
    }

    // --- fixtures -----------------------------------------------------------

    private static CategoryRequest request(String name, String slug) {
        return CategoryRequest.builder().name(name).slug(slug).description("desc").build();
    }

    private static Category category(Long id, String name, String slug) {
        return Category.builder().id(id).name(name).slug(slug).description("desc").build();
    }
}
