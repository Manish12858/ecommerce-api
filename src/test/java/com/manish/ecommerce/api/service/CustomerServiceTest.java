package com.manish.ecommerce.api.service;

import com.manish.ecommerce.api.dto.CustomerRequest;
import com.manish.ecommerce.api.dto.CustomerResponse;
import com.manish.ecommerce.api.dto.PageResponse;
import com.manish.ecommerce.api.entity.Customer;
import com.manish.ecommerce.api.exception.BusinessRuleException;
import com.manish.ecommerce.api.exception.DuplicateResourceException;
import com.manish.ecommerce.api.exception.ResourceNotFoundException;
import com.manish.ecommerce.api.repository.CustomerRepository;
import com.manish.ecommerce.api.repository.OrderRepository;
import com.manish.ecommerce.api.repository.ProductReviewRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ProductReviewRepository productReviewRepository;

    @InjectMocks
    private CustomerService customerService;

    // --- create -------------------------------------------------------------

    @Test
    void should_registerCustomer_when_emailIsUnique() {
        // Arrange
        CustomerRequest request = request("john.doe@example.com");
        when(customerRepository.existsByEmail("john.doe@example.com")).thenReturn(false);
        when(customerRepository.save(any(Customer.class))).thenAnswer(inv -> {
            Customer c = inv.getArgument(0);
            c.setId(1L);
            return c;
        });

        // Act
        CustomerResponse response = customerService.create(request);

        // Assert
        assertThat(response)
                .extracting(CustomerResponse::getId, CustomerResponse::getEmail, CustomerResponse::getFirstName)
                .containsExactly(1L, "john.doe@example.com", "John");
    }

    @Test
    void should_throwDuplicateResource_when_emailAlreadyRegistered() {
        // Arrange
        CustomerRequest request = request("john.doe@example.com");
        when(customerRepository.existsByEmail("john.doe@example.com")).thenReturn(true);

        // Act & Assert
        assertThatThrownBy(() -> customerService.create(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("john.doe@example.com");
        verify(customerRepository, never()).save(any());
    }

    // --- read ---------------------------------------------------------------

    @Test
    void should_returnPagedCustomers_when_findAllCalled() {
        // Arrange
        Pageable pageable = PageRequest.of(0, 10);
        when(customerRepository.findAll(pageable)).thenReturn(new PageImpl<>(
                List.of(customer(1L, "a@example.com"), customer(2L, "b@example.com")), pageable, 2));

        // Act
        PageResponse<CustomerResponse> page = customerService.findAll(pageable);

        // Assert
        assertThat(page.getContent()).extracting(CustomerResponse::getEmail)
                .containsExactly("a@example.com", "b@example.com");
    }

    @Test
    void should_returnCustomer_when_idExists() {
        // Arrange
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L, "john.doe@example.com")));

        // Act
        CustomerResponse response = customerService.findById(1L);

        // Assert
        assertThat(response.getEmail()).isEqualTo("john.doe@example.com");
    }

    @Test
    void should_throwResourceNotFound_when_idDoesNotExist() {
        // Arrange
        when(customerRepository.findById(99L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> customerService.findById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void should_throwResourceNotFound_when_emailDoesNotExist() {
        // Arrange
        when(customerRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> customerService.findByEmail("ghost@example.com"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("ghost@example.com");
    }

    // --- update -------------------------------------------------------------

    @Test
    void should_updateCustomer_when_newEmailIsUnique() {
        // Arrange
        Customer existing = customer(1L, "old@example.com");
        when(customerRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(customerRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(customerRepository.save(existing)).thenReturn(existing);

        // Act
        CustomerResponse response = customerService.update(1L, request("new@example.com"));

        // Assert
        assertThat(response.getEmail()).isEqualTo("new@example.com");
    }

    @Test
    void should_throwDuplicateResource_when_updatingToExistingEmail() {
        // Arrange
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L, "old@example.com")));
        when(customerRepository.existsByEmail("taken@example.com")).thenReturn(true);

        // Act & Assert
        assertThatThrownBy(() -> customerService.update(1L, request("taken@example.com")))
                .isInstanceOf(DuplicateResourceException.class);
    }

    // --- delete -------------------------------------------------------------

    @Test
    void should_deleteCustomer_when_theyHaveNoOrders() {
        // Arrange
        Customer existing = customer(1L, "john.doe@example.com");
        when(customerRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(orderRepository.existsByCustomerId(1L)).thenReturn(false);
        when(productReviewRepository.existsByCustomerId(1L)).thenReturn(false);

        // Act
        customerService.delete(1L);

        // Assert
        verify(customerRepository).delete(existing);
    }

    @Test
    void should_throwBusinessRule_when_deletingCustomerWithOrders() {
        // Arrange
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L, "john.doe@example.com")));
        when(orderRepository.existsByCustomerId(1L)).thenReturn(true);

        // Act & Assert
        assertThatThrownBy(() -> customerService.delete(1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("existing orders");
        verify(customerRepository, never()).delete(any());
    }

    @Test
    void should_throwBusinessRule_when_deletingCustomerWithReviews() {
        // Arrange
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L, "john.doe@example.com")));
        when(orderRepository.existsByCustomerId(1L)).thenReturn(false);
        when(productReviewRepository.existsByCustomerId(1L)).thenReturn(true);

        // Act & Assert
        assertThatThrownBy(() -> customerService.delete(1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("posted reviews");
        verify(customerRepository, never()).delete(any());
    }

    // --- fixtures -----------------------------------------------------------

    private static CustomerRequest request(String email) {
        return CustomerRequest.builder()
                .firstName("John").lastName("Doe").email(email).phone("+1-202-555-0101").address("123 Maple St")
                .build();
    }

    private static Customer customer(Long id, String email) {
        return Customer.builder().id(id).firstName("John").lastName("Doe").email(email).build();
    }
}
