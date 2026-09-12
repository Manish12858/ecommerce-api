package com.manish.ecommerce.api.service;

import com.manish.ecommerce.api.dto.OrderItemRequest;
import com.manish.ecommerce.api.dto.OrderItemResponse;
import com.manish.ecommerce.api.dto.OrderRequest;
import com.manish.ecommerce.api.dto.OrderResponse;
import com.manish.ecommerce.api.dto.PageResponse;
import com.manish.ecommerce.api.entity.Customer;
import com.manish.ecommerce.api.entity.Order;
import com.manish.ecommerce.api.entity.OrderItem;
import com.manish.ecommerce.api.entity.OrderStatus;
import com.manish.ecommerce.api.entity.Payment;
import com.manish.ecommerce.api.entity.PaymentMethod;
import com.manish.ecommerce.api.entity.PaymentStatus;
import com.manish.ecommerce.api.entity.Product;
import com.manish.ecommerce.api.exception.BusinessRuleException;
import com.manish.ecommerce.api.exception.ResourceNotFoundException;
import com.manish.ecommerce.api.repository.OrderRepository;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private CustomerService customerService;

    @Mock
    private ProductService productService;

    @InjectMocks
    private OrderService orderService;

    // --- placeOrder ---------------------------------------------------------

    @Test
    void should_reduceStockAndBuildOrderWithPayment_when_orderIsPlaced() {
        // Arrange
        Product mouse = product(1L, "ELEC-MOU-001", "24.99", 150);
        Product book = product(5L, "BOOK-CLN-001", "32.50", 200);
        when(customerService.getEntity(1L)).thenReturn(customer());
        when(productService.getEntity(1L)).thenReturn(mouse);
        when(productService.getEntity(5L)).thenReturn(book);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        OrderRequest request = orderRequest(item(1L, 3), item(5L, 2));

        // Act
        OrderResponse response = orderService.placeOrder(request);

        // Assert
        assertThat(response).satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(r.getTotalAmount()).isEqualByComparingTo("139.97");
            assertThat(r.getItems()).extracting(OrderItemResponse::getSku, OrderItemResponse::getSubtotal)
                    .containsExactly(
                            tuple("ELEC-MOU-001", new BigDecimal("74.97")),
                            tuple("BOOK-CLN-001", new BigDecimal("65.00")));
            assertThat(r.getPayment().getPaymentMethod()).isEqualTo(PaymentMethod.UPI);
            assertThat(r.getPayment().getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(r.getPayment().getAmount()).isEqualByComparingTo("139.97");
        });
        assertThat(mouse.getStockQuantity()).isEqualTo(147);
        assertThat(book.getStockQuantity()).isEqualTo(198);
    }

    @Test
    void should_throwResourceNotFound_when_placingOrderForUnknownCustomer() {
        // Arrange
        when(customerService.getEntity(99L)).thenThrow(new ResourceNotFoundException("Customer", 99L));
        OrderRequest request = orderRequest(item(1L, 1));
        request.setCustomerId(99L);

        // Act & Assert
        assertThatThrownBy(() -> orderService.placeOrder(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Customer");
        verify(orderRepository, never()).save(any());
    }

    @Test
    void should_throwResourceNotFound_when_placingOrderForUnknownProduct() {
        // Arrange
        when(customerService.getEntity(1L)).thenReturn(customer());
        when(productService.getEntity(99L)).thenThrow(new ResourceNotFoundException("Product", 99L));

        // Act & Assert
        assertThatThrownBy(() -> orderService.placeOrder(orderRequest(item(99L, 1))))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Product");
    }

    @Test
    void should_throwBusinessRuleAndLeaveStockUntouched_when_stockIsInsufficient() {
        // Arrange
        Product mouse = product(1L, "ELEC-MOU-001", "24.99", 2);
        when(customerService.getEntity(1L)).thenReturn(customer());
        when(productService.getEntity(1L)).thenReturn(mouse);

        // Act & Assert
        assertThatThrownBy(() -> orderService.placeOrder(orderRequest(item(1L, 5))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Insufficient stock")
                .hasMessageContaining("requested 5, available 2");
        assertThat(mouse.getStockQuantity()).isEqualTo(2);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void should_throwBusinessRule_when_productIsInactive() {
        // Arrange
        Product mouse = product(1L, "ELEC-MOU-001", "24.99", 150);
        mouse.setActive(false);
        when(customerService.getEntity(1L)).thenReturn(customer());
        when(productService.getEntity(1L)).thenReturn(mouse);

        // Act & Assert
        assertThatThrownBy(() -> orderService.placeOrder(orderRequest(item(1L, 1))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("not active");
    }

    @Test
    void should_logLowStockWarning_when_remainingStockDropsBelowThreshold() {
        // Arrange
        ListAppender<ILoggingEvent> logs = attachLogCapture();
        Product headphones = product(4L, "ELEC-HDP-004", "199.99", 12);
        when(customerService.getEntity(1L)).thenReturn(customer());
        when(productService.getEntity(4L)).thenReturn(headphones);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        OrderResponse response = orderService.placeOrder(orderRequest(item(4L, 5)));

        // Assert
        assertThat(response.getStatus()).as("order still placed").isEqualTo(OrderStatus.PENDING);
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).isEqualTo("Low stock alert: Product ELEC-HDP-004 has 7 units left");
        });
    }

    @Test
    void should_notLogLowStockWarning_when_remainingStockIsAtOrAboveThreshold() {
        // Arrange
        ListAppender<ILoggingEvent> logs = attachLogCapture();
        Product headphones = product(4L, "ELEC-HDP-004", "199.99", 12);
        when(customerService.getEntity(1L)).thenReturn(customer());
        when(productService.getEntity(4L)).thenReturn(headphones);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        orderService.placeOrder(orderRequest(item(4L, 2)));

        // Assert
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN).isEmpty();
    }

    // --- read ---------------------------------------------------------------

    @Test
    void should_returnOrder_when_idExists() {
        // Arrange
        when(orderRepository.findWithDetailsById(1L)).thenReturn(Optional.of(order(OrderStatus.PENDING, PaymentStatus.PENDING)));

        // Act
        OrderResponse response = orderService.findById(1L);

        // Assert
        assertThat(response)
                .extracting(OrderResponse::getOrderNumber, OrderResponse::getCustomerName)
                .containsExactly("ORD-TEST0001", "John Doe");
    }

    @Test
    void should_throwResourceNotFound_when_orderIdDoesNotExist() {
        // Arrange
        when(orderRepository.findWithDetailsById(99L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> orderService.findById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void should_throwResourceNotFound_when_orderNumberDoesNotExist() {
        // Arrange
        when(orderRepository.findByOrderNumber("ORD-NOPE")).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> orderService.findByOrderNumber("ORD-NOPE"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("ORD-NOPE");
    }

    @Test
    void should_filterByCustomerAndStatus_when_bothGiven() {
        // Arrange
        Pageable pageable = PageRequest.of(0, 10);
        when(orderRepository.findByCustomerIdAndStatus(1L, OrderStatus.SHIPPED, pageable))
                .thenReturn(new PageImpl<>(List.of(order(OrderStatus.SHIPPED, PaymentStatus.COMPLETED)), pageable, 1));

        // Act
        PageResponse<OrderResponse> page = orderService.findAll(1L, OrderStatus.SHIPPED, pageable);

        // Assert
        assertThat(page.getContent()).singleElement()
                .extracting(OrderResponse::getStatus).isEqualTo(OrderStatus.SHIPPED);
    }

    // --- status transitions -------------------------------------------------

    @Test
    void should_confirmOrder_when_currentStatusIsPending() {
        // Arrange
        Order order = order(OrderStatus.PENDING, PaymentStatus.PENDING);
        when(orderRepository.findWithDetailsById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);

        // Act
        OrderResponse response = orderService.updateStatus(1L, OrderStatus.CONFIRMED);

        // Assert
        assertThat(response.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void should_throwBusinessRule_when_skippingStatusSteps() {
        // Arrange
        when(orderRepository.findWithDetailsById(1L))
                .thenReturn(Optional.of(order(OrderStatus.CONFIRMED, PaymentStatus.COMPLETED)));

        // Act & Assert
        assertThatThrownBy(() -> orderService.updateStatus(1L, OrderStatus.DELIVERED))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("from CONFIRMED to DELIVERED");
        verify(orderRepository, never()).save(any());
    }

    @Test
    void should_throwBusinessRule_when_movingStatusBackwards() {
        // Arrange
        when(orderRepository.findWithDetailsById(1L))
                .thenReturn(Optional.of(order(OrderStatus.SHIPPED, PaymentStatus.COMPLETED)));

        // Act & Assert
        assertThatThrownBy(() -> orderService.updateStatus(1L, OrderStatus.PROCESSING))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("from SHIPPED to PROCESSING");
    }

    @Test
    void should_throwBusinessRule_when_cancellingShippedOrder() {
        // Arrange
        when(orderRepository.findWithDetailsById(1L))
                .thenReturn(Optional.of(order(OrderStatus.SHIPPED, PaymentStatus.COMPLETED)));

        // Act & Assert
        assertThatThrownBy(() -> orderService.cancel(1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("from SHIPPED to CANCELLED");
    }

    // --- cancelOrder --------------------------------------------------------

    @Test
    void should_restoreStockAndRefundPayment_when_paidOrderIsCancelled() {
        // Arrange
        Order order = order(OrderStatus.CONFIRMED, PaymentStatus.COMPLETED);
        Product product = order.getItems().getFirst().getProduct();
        int stockBefore = product.getStockQuantity();
        when(orderRepository.findWithDetailsById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);

        // Act
        OrderResponse response = orderService.cancel(1L);

        // Assert
        assertThat(response.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(response.getPayment().getPaymentStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(product.getStockQuantity()).isEqualTo(stockBefore + 3);
    }

    @Test
    void should_restoreStockButNotRefund_when_unpaidOrderIsCancelled() {
        // Arrange
        Order order = order(OrderStatus.PENDING, PaymentStatus.PENDING);
        Product product = order.getItems().getFirst().getProduct();
        int stockBefore = product.getStockQuantity();
        when(orderRepository.findWithDetailsById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);

        // Act
        OrderResponse response = orderService.cancel(1L);

        // Assert
        assertThat(response.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(response.getPayment().getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(product.getStockQuantity()).isEqualTo(stockBefore + 3);
    }

    @Test
    void should_throwBusinessRule_when_cancellingDeliveredOrder() {
        // Arrange
        Order order = order(OrderStatus.DELIVERED, PaymentStatus.COMPLETED);
        Product product = order.getItems().getFirst().getProduct();
        int stockBefore = product.getStockQuantity();
        when(orderRepository.findWithDetailsById(1L)).thenReturn(Optional.of(order));

        // Act & Assert
        assertThatThrownBy(() -> orderService.cancel(1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("from DELIVERED to CANCELLED");
        assertThat(product.getStockQuantity()).isEqualTo(stockBefore);
        assertThat(order.getPayment().getPaymentStatus()).isEqualTo(PaymentStatus.COMPLETED);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void should_throwBusinessRule_when_cancellingAlreadyCancelledOrder() {
        // Arrange
        Order order = order(OrderStatus.CANCELLED, PaymentStatus.REFUNDED);
        Product product = order.getItems().getFirst().getProduct();
        int stockBefore = product.getStockQuantity();
        when(orderRepository.findWithDetailsById(1L)).thenReturn(Optional.of(order));

        // Act & Assert
        assertThatThrownBy(() -> orderService.cancel(1L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("from CANCELLED to CANCELLED");
        assertThat(product.getStockQuantity()).as("stock must not be restored twice").isEqualTo(stockBefore);
        verify(orderRepository, never()).save(any());
    }

    // --- updatePaymentStatus ------------------------------------------------

    @Test
    void should_completePayment_when_paymentIsPending() {
        // Arrange
        Order order = order(OrderStatus.PENDING, PaymentStatus.PENDING);
        when(orderRepository.findWithDetailsById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);

        // Act
        OrderResponse response = orderService.updatePaymentStatus(1L, PaymentStatus.COMPLETED);

        // Assert
        assertThat(response.getPayment().getPaymentStatus()).isEqualTo(PaymentStatus.COMPLETED);
    }

    @Test
    void should_throwBusinessRule_when_refundRequestedDirectly() {
        // Arrange
        when(orderRepository.findWithDetailsById(1L))
                .thenReturn(Optional.of(order(OrderStatus.CONFIRMED, PaymentStatus.COMPLETED)));

        // Act & Assert
        assertThatThrownBy(() -> orderService.updatePaymentStatus(1L, PaymentStatus.REFUNDED))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("cancelling the order");
    }

    @Test
    void should_throwBusinessRule_when_changingPaymentOnFinalOrder() {
        // Arrange
        when(orderRepository.findWithDetailsById(1L))
                .thenReturn(Optional.of(order(OrderStatus.CANCELLED, PaymentStatus.PENDING)));

        // Act & Assert
        assertThatThrownBy(() -> orderService.updatePaymentStatus(1L, PaymentStatus.COMPLETED))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("order is CANCELLED");
    }

    @Test
    void should_throwBusinessRule_when_failingAlreadyCompletedPayment() {
        // Arrange
        when(orderRepository.findWithDetailsById(1L))
                .thenReturn(Optional.of(order(OrderStatus.CONFIRMED, PaymentStatus.COMPLETED)));

        // Act & Assert
        assertThatThrownBy(() -> orderService.updatePaymentStatus(1L, PaymentStatus.FAILED))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("from COMPLETED to FAILED");
    }

    // --- fixtures -----------------------------------------------------------

    private static ListAppender<ILoggingEvent> attachLogCapture() {
        Logger logger = (Logger) LoggerFactory.getLogger(OrderService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static Customer customer() {
        return Customer.builder().id(1L).firstName("John").lastName("Doe").email("john.doe@example.com").build();
    }

    private static Product product(Long id, String sku, String price, int stock) {
        return Product.builder()
                .id(id).name("Product " + sku).sku(sku).price(new BigDecimal(price)).stockQuantity(stock).active(true)
                .build();
    }

    private static OrderItemRequest item(Long productId, int quantity) {
        return OrderItemRequest.builder().productId(productId).quantity(quantity).build();
    }

    private static OrderRequest orderRequest(OrderItemRequest... items) {
        return OrderRequest.builder()
                .customerId(1L).shippingAddress("123 Maple Street").paymentMethod(PaymentMethod.UPI)
                .items(List.of(items))
                .build();
    }

    /** An order with one line (3 × mouse @ 24.99) and a payment, in the given states. */
    private static Order order(OrderStatus status, PaymentStatus paymentStatus) {
        Product mouse = product(1L, "ELEC-MOU-001", "24.99", 147);
        Order order = Order.builder()
                .id(1L).orderNumber("ORD-TEST0001").customer(customer()).status(status)
                .shippingAddress("123 Maple Street")
                .build();
        OrderItem item = OrderItem.builder().id(1L).product(mouse).quantity(3).unitPrice(mouse.getPrice()).build();
        item.calculateSubtotal();
        order.addItem(item);
        order.recalculateTotal();
        order.attachPayment(Payment.builder()
                .id(1L).paymentMethod(PaymentMethod.UPI).paymentStatus(paymentStatus).amount(order.getTotalAmount())
                .build());
        return order;
    }
}
