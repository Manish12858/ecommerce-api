package com.manish.ecommerce.api.controller;

import com.manish.ecommerce.api.dto.OrderItemRequest;
import com.manish.ecommerce.api.dto.OrderItemResponse;
import com.manish.ecommerce.api.dto.OrderRequest;
import com.manish.ecommerce.api.dto.OrderResponse;
import com.manish.ecommerce.api.dto.PageResponse;
import com.manish.ecommerce.api.dto.PaymentResponse;
import com.manish.ecommerce.api.entity.OrderStatus;
import com.manish.ecommerce.api.entity.PaymentMethod;
import com.manish.ecommerce.api.entity.PaymentStatus;
import com.manish.ecommerce.api.exception.BusinessRuleException;
import com.manish.ecommerce.api.exception.ResourceNotFoundException;
import com.manish.ecommerce.api.service.OrderService;
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
import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private OrderService orderService;

    @Test
    void should_return201WithBody_when_orderIsPlaced() throws Exception {
        // Arrange
        OrderRequest request = validRequest();
        when(orderService.placeOrder(any(OrderRequest.class))).thenReturn(orderResponse(10L, OrderStatus.PENDING));

        // Act & Assert
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.orderNumber").value("ORD-1A2B3C4D"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(74.97))
                .andExpect(jsonPath("$.items[0].subtotal").value(74.97))
                .andExpect(jsonPath("$.payment.paymentStatus").value("PENDING"));

        org.mockito.ArgumentCaptor<OrderRequest> captor = org.mockito.ArgumentCaptor.forClass(OrderRequest.class);
        verify(orderService).placeOrder(captor.capture());
        OrderRequest captured = captor.getValue();
        assertThat(captured.getCustomerId()).isEqualTo(1L);
        assertThat(captured.getShippingAddress()).isEqualTo("123 Main St, Springfield");
        assertThat(captured.getPaymentMethod()).isEqualTo(PaymentMethod.UPI);
        assertThat(captured.getItems()).hasSize(1);
        assertThat(captured.getItems().get(0).getProductId()).isEqualTo(1L);
        assertThat(captured.getItems().get(0).getQuantity()).isEqualTo(3);
    }

    @Test
    void should_return400WithFieldErrors_when_requiredFieldsAreMissing() throws Exception {
        // Arrange
        String invalidJson = """
                {
                  "shippingAddress": "",
                  "paymentMethod": "UPI",
                  "items": []
                }
                """;

        // Act & Assert
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItems("customerId", "shippingAddress", "items")));

        verify(orderService, never()).placeOrder(any());
    }

    @Test
    void should_return400WithFieldError_when_itemQuantityIsZero() throws Exception {
        // Arrange
        String invalidJson = """
                {
                  "customerId": 1,
                  "shippingAddress": "123 Main St, Springfield",
                  "paymentMethod": "UPI",
                  "items": [ { "productId": 1, "quantity": 0 } ]
                }
                """;

        // Act & Assert
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItems("items[0].quantity")));

        verify(orderService, never()).placeOrder(any());
    }

    @Test
    void should_return400_when_requestBodyIsMalformed() throws Exception {
        // Arrange
        String malformedJson = "{ \"customerId\": 1, ";

        // Act & Assert
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(malformedJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body"));
    }

    @Test
    void should_return404_when_customerNotFoundOnPlaceOrder() throws Exception {
        // Arrange
        when(orderService.placeOrder(any(OrderRequest.class)))
                .thenThrow(new ResourceNotFoundException("Customer", 99L));

        // Act & Assert
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Customer not found with id: 99"))
                .andExpect(jsonPath("$.path").value("/api/v1/orders"));
    }

    @Test
    void should_return422_when_insufficientStockOnPlaceOrder() throws Exception {
        // Arrange
        when(orderService.placeOrder(any(OrderRequest.class)))
                .thenThrow(new BusinessRuleException("Insufficient stock for product 'MOU-001': requested 3, available 1"));

        // Act & Assert
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Insufficient stock for product 'MOU-001': requested 3, available 1"));
    }

    @Test
    void should_return200WithDefaultPageable_when_findAllCalledWithNoParams() throws Exception {
        // Arrange
        Page<OrderResponse> page = new PageImpl<>(List.of(orderResponse(1L, OrderStatus.PENDING)));
        when(orderService.findAll(isNull(), isNull(), any(Pageable.class))).thenReturn(PageResponse.from(page));

        // Act & Assert
        mockMvc.perform(get("/api/v1/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(1));

        org.mockito.ArgumentCaptor<Pageable> captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(orderService).findAll(isNull(), isNull(), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
        assertThat(captor.getValue().getSort().getOrderFor("createdAt")).isNotNull();
        assertThat(captor.getValue().getSort().getOrderFor("createdAt").isDescending()).isTrue();
    }

    @Test
    void should_passCustomerIdAndStatus_when_findAllCalledWithParams() throws Exception {
        // Arrange
        Page<OrderResponse> page = new PageImpl<>(List.of(orderResponse(1L, OrderStatus.SHIPPED)));
        when(orderService.findAll(eq(1L), eq(OrderStatus.SHIPPED), any(Pageable.class))).thenReturn(PageResponse.from(page));

        // Act & Assert
        mockMvc.perform(get("/api/v1/orders").queryParam("customerId", "1").queryParam("status", "SHIPPED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("SHIPPED"));

        verify(orderService).findAll(eq(1L), eq(OrderStatus.SHIPPED), any(Pageable.class));
    }

    @Test
    void should_return500_when_statusQueryParamIsUnparseable() throws Exception {
        // Arrange: no handler for MethodArgumentTypeMismatchException in GlobalExceptionHandler,
        // so it falls through to the generic 500 handler. This is a gap, not a 400.

        // Act & Assert
        mockMvc.perform(get("/api/v1/orders").queryParam("status", "BOGUS"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    @Test
    void should_return200WithOrderNumber_when_orderFoundById() throws Exception {
        // Arrange
        when(orderService.findById(1L)).thenReturn(orderResponse(1L, OrderStatus.PENDING));

        // Act & Assert
        mockMvc.perform(get("/api/v1/orders/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value("ORD-1A2B3C4D"));
    }

    @Test
    void should_return404_when_orderNotFoundById() throws Exception {
        // Arrange
        when(orderService.findById(99L)).thenThrow(new ResourceNotFoundException("Order", 99L));

        // Act & Assert
        mockMvc.perform(get("/api/v1/orders/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Order not found with id: 99"));
    }

    @Test
    void should_return200_when_orderFoundByOrderNumber() throws Exception {
        // Arrange
        when(orderService.findByOrderNumber("ORD-1A2B3C4D")).thenReturn(orderResponse(1L, OrderStatus.PENDING));

        // Act & Assert
        mockMvc.perform(get("/api/v1/orders/number/ORD-1A2B3C4D"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void should_return200_when_statusUpdatedToValidTransition() throws Exception {
        // Arrange
        when(orderService.updateStatus(1L, OrderStatus.CONFIRMED)).thenReturn(orderResponse(1L, OrderStatus.CONFIRMED));

        // Act & Assert
        mockMvc.perform(put("/api/v1/orders/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CONFIRMED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        verify(orderService).updateStatus(1L, OrderStatus.CONFIRMED);
    }

    @Test
    void should_return422_when_statusTransitionIsInvalid() throws Exception {
        // Arrange
        when(orderService.updateStatus(1L, OrderStatus.DELIVERED))
                .thenThrow(new BusinessRuleException("Cannot transition order ORD-1A2B3C4D from PENDING to DELIVERED"));

        // Act & Assert
        mockMvc.perform(put("/api/v1/orders/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DELIVERED\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void should_return400_when_statusFieldIsMissing() throws Exception {
        // Arrange & Act & Assert
        mockMvc.perform(put("/api/v1/orders/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItems("status")));

        verify(orderService, never()).updateStatus(anyLong(), any());
    }

    @Test
    void should_return200WithCancelledStatus_when_orderCancelled() throws Exception {
        // Arrange
        when(orderService.cancel(1L)).thenReturn(orderResponse(1L, OrderStatus.CANCELLED));

        // Act & Assert
        mockMvc.perform(post("/api/v1/orders/1/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        verify(orderService).cancel(1L);
    }

    @Test
    void should_return200_when_paymentStatusUpdated() throws Exception {
        // Arrange
        OrderResponse response = orderResponse(1L, OrderStatus.PENDING);
        response.getPayment().setPaymentStatus(PaymentStatus.COMPLETED);
        when(orderService.updatePaymentStatus(1L, PaymentStatus.COMPLETED)).thenReturn(response);

        // Act & Assert
        mockMvc.perform(put("/api/v1/orders/1/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentStatus\":\"COMPLETED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.paymentStatus").value("COMPLETED"));

        verify(orderService).updatePaymentStatus(1L, PaymentStatus.COMPLETED);
    }

    @Test
    void should_return422_when_paymentStatusSetDirectlyToRefunded() throws Exception {
        // Arrange
        when(orderService.updatePaymentStatus(1L, PaymentStatus.REFUNDED))
                .thenThrow(new BusinessRuleException(
                        "Refunds are issued by cancelling the order, not by setting payment status"));

        // Act & Assert
        mockMvc.perform(put("/api/v1/orders/1/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentStatus\":\"REFUNDED\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // --- fixtures -------------------------------------------------------

    private static OrderRequest validRequest() {
        return OrderRequest.builder()
                .customerId(1L)
                .shippingAddress("123 Main St, Springfield")
                .paymentMethod(PaymentMethod.UPI)
                .items(List.of(OrderItemRequest.builder().productId(1L).quantity(3).build()))
                .build();
    }

    private static OrderResponse orderResponse(Long id, OrderStatus status) {
        OrderItemResponse item = OrderItemResponse.builder()
                .id(1L)
                .productId(1L)
                .productName("Wireless Mouse")
                .sku("ELEC-MOU-001")
                .quantity(3)
                .unitPrice(new BigDecimal("24.99"))
                .subtotal(new BigDecimal("74.97"))
                .build();
        PaymentResponse payment = PaymentResponse.builder()
                .id(1L)
                .paymentMethod(PaymentMethod.UPI)
                .paymentStatus(PaymentStatus.PENDING)
                .amount(new BigDecimal("74.97"))
                .build();
        return OrderResponse.builder()
                .id(id)
                .orderNumber("ORD-1A2B3C4D")
                .customerId(1L)
                .customerName("John Doe")
                .customerEmail("john.doe@example.com")
                .status(status)
                .totalAmount(new BigDecimal("74.97"))
                .shippingAddress("123 Main St, Springfield")
                .items(List.of(item))
                .payment(payment)
                .build();
    }
}
