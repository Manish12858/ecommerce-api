package com.manish.ecommerce.api.controller;

import com.manish.ecommerce.api.dto.CustomerRequest;
import com.manish.ecommerce.api.dto.CustomerResponse;
import com.manish.ecommerce.api.dto.PageResponse;
import com.manish.ecommerce.api.exception.BusinessRuleException;
import com.manish.ecommerce.api.exception.DuplicateResourceException;
import com.manish.ecommerce.api.exception.ResourceNotFoundException;
import com.manish.ecommerce.api.service.CustomerService;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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

@WebMvcTest(CustomerController.class)
class CustomerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CustomerService customerService;

    @Test
    void should_return201WithBody_when_customerIsCreated() throws Exception {
        // Arrange
        CustomerRequest request = validRequest();
        when(customerService.create(any(CustomerRequest.class))).thenReturn(customerResponse(10L));

        // Act & Assert
        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.email").value("john.doe@example.com"))
                .andExpect(jsonPath("$.firstName").value("John"));

        org.mockito.ArgumentCaptor<CustomerRequest> captor = org.mockito.ArgumentCaptor.forClass(CustomerRequest.class);
        verify(customerService).create(captor.capture());
        CustomerRequest captured = captor.getValue();
        assertThat(captured.getFirstName()).isEqualTo("John");
        assertThat(captured.getLastName()).isEqualTo("Doe");
        assertThat(captured.getEmail()).isEqualTo("john.doe@example.com");
    }

    @Test
    void should_return400WithFieldErrors_when_requiredFieldsAreBlank() throws Exception {
        // Arrange
        String invalidJson = """
                {
                  "firstName": "",
                  "lastName": "",
                  "email": "",
                  "phone": "555-1234",
                  "address": "123 Main St"
                }
                """;

        // Act & Assert
        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors[*].field", containsInAnyOrder("firstName", "lastName", "email")));

        verify(customerService, never()).create(any());
    }

    @Test
    void should_return400WithFieldError_when_emailFormatIsInvalid() throws Exception {
        // Arrange
        CustomerRequest request = validRequest();
        request.setEmail("not-an-email");

        // Act & Assert
        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", containsInAnyOrder("email")));

        verify(customerService, never()).create(any());
    }

    @Test
    void should_return400WithFieldError_when_phoneExceedsMaxLength() throws Exception {
        // Arrange
        CustomerRequest request = validRequest();
        request.setPhone("1".repeat(21));

        // Act & Assert
        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", containsInAnyOrder("phone")));

        verify(customerService, never()).create(any());
    }

    @Test
    void should_return400_when_requestBodyIsMalformed() throws Exception {
        // Arrange
        String malformedJson = "{ \"firstName\": \"John\", ";

        // Act & Assert
        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(malformedJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body"));
    }

    @Test
    void should_return409_when_emailAlreadyExists() throws Exception {
        // Arrange
        when(customerService.create(any(CustomerRequest.class)))
                .thenThrow(new DuplicateResourceException("Customer already exists with email: john.doe@example.com"));

        // Act & Assert
        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Customer already exists with email: john.doe@example.com"))
                .andExpect(jsonPath("$.path").value("/api/v1/customers"));
    }

    @Test
    void should_return200WithDefaultPageable_when_findAllCalledWithNoParams() throws Exception {
        // Arrange
        Page<CustomerResponse> page = new PageImpl<>(List.of(customerResponse(1L)));
        when(customerService.findAll(any(Pageable.class))).thenReturn(PageResponse.from(page));

        // Act & Assert
        mockMvc.perform(get("/api/v1/customers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(1));

        org.mockito.ArgumentCaptor<Pageable> captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(customerService).findAll(captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
        assertThat(captor.getValue().getSort().getOrderFor("lastName")).isNotNull();
        assertThat(captor.getValue().getSort().getOrderFor("lastName").isAscending()).isTrue();
    }

    @Test
    void should_usePageAndSizeFromQueryParams_when_findAllCalledWithParams() throws Exception {
        // Arrange
        Page<CustomerResponse> page = new PageImpl<>(List.of(customerResponse(1L)));
        when(customerService.findAll(any(Pageable.class))).thenReturn(PageResponse.from(page));

        // Act & Assert
        mockMvc.perform(get("/api/v1/customers").queryParam("page", "2").queryParam("size", "10"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<Pageable> captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(customerService).findAll(captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(captor.getValue().getPageSize()).isEqualTo(10);
    }

    @Test
    void should_return200WithEmail_when_customerFoundById() throws Exception {
        // Arrange
        when(customerService.findById(1L)).thenReturn(customerResponse(1L));

        // Act & Assert
        mockMvc.perform(get("/api/v1/customers/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("john.doe@example.com"));
    }

    @Test
    void should_return404_when_customerNotFoundById() throws Exception {
        // Arrange
        when(customerService.findById(99L)).thenThrow(new ResourceNotFoundException("Customer", 99L));

        // Act & Assert
        mockMvc.perform(get("/api/v1/customers/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Customer not found with id: 99"));
    }

    @Test
    void should_return200WithMatchingEmail_when_customerFoundByEmail() throws Exception {
        // Arrange
        when(customerService.findByEmail("john.doe@example.com")).thenReturn(customerResponse(1L));

        // Act & Assert
        mockMvc.perform(get("/api/v1/customers/email/john.doe@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("john.doe@example.com"));

        verify(customerService).findByEmail("john.doe@example.com");
    }

    @Test
    void should_return404_when_customerNotFoundByEmail() throws Exception {
        // Arrange
        when(customerService.findByEmail("missing@example.com"))
                .thenThrow(new ResourceNotFoundException("Customer not found with email: missing@example.com"));

        // Act & Assert
        mockMvc.perform(get("/api/v1/customers/email/missing@example.com"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Customer not found with email: missing@example.com"));
    }

    @Test
    void should_return200WithUpdatedBody_when_customerIsUpdated() throws Exception {
        // Arrange
        CustomerRequest request = validRequest();
        when(customerService.update(eq(1L), any(CustomerRequest.class))).thenReturn(customerResponse(1L));

        // Act & Assert
        mockMvc.perform(put("/api/v1/customers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));

        verify(customerService).update(eq(1L), any(CustomerRequest.class));
    }

    @Test
    void should_return400_when_updateRequestHasBlankEmail() throws Exception {
        // Arrange
        CustomerRequest request = validRequest();
        request.setEmail("");

        // Act & Assert
        mockMvc.perform(put("/api/v1/customers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", containsInAnyOrder("email")));

        verify(customerService, never()).update(anyLong(), any());
    }

    @Test
    void should_return204_when_customerIsDeleted() throws Exception {
        // Act & Assert
        mockMvc.perform(delete("/api/v1/customers/1"))
                .andExpect(status().isNoContent());

        verify(customerService).delete(1L);
    }

    @Test
    void should_return422_when_customerHasExistingOrders() throws Exception {
        // Arrange
        org.mockito.Mockito.doThrow(new BusinessRuleException("Cannot delete customer with id 1: existing orders"))
                .when(customerService).delete(1L);

        // Act & Assert
        mockMvc.perform(delete("/api/v1/customers/1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Cannot delete customer with id 1: existing orders"));
    }

    // --- fixtures -------------------------------------------------------

    private static CustomerRequest validRequest() {
        return CustomerRequest.builder()
                .firstName("John")
                .lastName("Doe")
                .email("john.doe@example.com")
                .phone("555-1234")
                .address("123 Main St, Springfield")
                .build();
    }

    private static CustomerResponse customerResponse(Long id) {
        return CustomerResponse.builder()
                .id(id)
                .firstName("John")
                .lastName("Doe")
                .email("john.doe@example.com")
                .phone("555-1234")
                .address("123 Main St, Springfield")
                .build();
    }
}
