package com.manish.ecommerce.api.service;

import com.manish.ecommerce.api.dto.OrderItemRequest;
import com.manish.ecommerce.api.dto.OrderItemResponse;
import com.manish.ecommerce.api.dto.OrderRequest;
import com.manish.ecommerce.api.dto.OrderResponse;
import com.manish.ecommerce.api.dto.PageResponse;
import com.manish.ecommerce.api.dto.PaymentResponse;
import com.manish.ecommerce.api.entity.Customer;
import com.manish.ecommerce.api.entity.Order;
import com.manish.ecommerce.api.entity.OrderItem;
import com.manish.ecommerce.api.entity.OrderStatus;
import com.manish.ecommerce.api.entity.Payment;
import com.manish.ecommerce.api.entity.PaymentStatus;
import com.manish.ecommerce.api.entity.Product;
import com.manish.ecommerce.api.exception.BusinessRuleException;
import com.manish.ecommerce.api.exception.ResourceNotFoundException;
import com.manish.ecommerce.api.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    private static final int LOW_STOCK_THRESHOLD = 10;

    private final OrderRepository orderRepository;
    private final CustomerService customerService;
    private final ProductService productService;

    /**
     * Places an order. Stock is reserved here, at order time — not at shipment.
     * The order, its items and its payment are persisted in one cascading save.
     */
    @Transactional
    public OrderResponse placeOrder(OrderRequest request) {
        Customer customer = customerService.getEntity(request.getCustomerId());

        Order order = Order.builder()
                .customer(customer)
                .shippingAddress(request.getShippingAddress())
                .notes(request.getNotes())
                .build();

        for (OrderItemRequest itemRequest : request.getItems()) {
            Product product = productService.getEntity(itemRequest.getProductId());
            reserveStock(product, itemRequest.getQuantity());

            OrderItem item = OrderItem.builder()
                    .product(product)
                    .quantity(itemRequest.getQuantity())
                    .unitPrice(product.getPrice())
                    .build();
            item.calculateSubtotal();
            order.addItem(item);
        }
        order.recalculateTotal();
        warnOnLowStock(order);

        Payment payment = Payment.builder()
                .paymentMethod(request.getPaymentMethod())
                .amount(order.getTotalAmount())
                .build();
        order.attachPayment(payment);

        return toResponse(orderRepository.save(order));
    }

    public PageResponse<OrderResponse> findAll(Long customerId, OrderStatus status, Pageable pageable) {
        Page<Order> page;
        if (customerId != null && status != null) {
            page = orderRepository.findByCustomerIdAndStatus(customerId, status, pageable);
        } else if (customerId != null) {
            page = orderRepository.findByCustomerId(customerId, pageable);
        } else if (status != null) {
            page = orderRepository.findByStatus(status, pageable);
        } else {
            page = orderRepository.findAll(pageable);
        }
        return PageResponse.from(page.map(this::toResponse));
    }

    public OrderResponse findById(Long id) {
        return toResponse(getEntity(id));
    }

    public OrderResponse findByOrderNumber(String orderNumber) {
        return orderRepository.findByOrderNumber(orderNumber)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with number: " + orderNumber));
    }

    /** Moves the order along the one-way status graph defined on {@link OrderStatus}. */
    @Transactional
    public OrderResponse updateStatus(Long id, OrderStatus target) {
        Order order = getEntity(id);
        transition(order, target);
        return toResponse(orderRepository.save(order));
    }

    @Transactional
    public OrderResponse cancel(Long id) {
        return updateStatus(id, OrderStatus.CANCELLED);
    }

    /** Payment moves PENDING → COMPLETED/FAILED here; REFUNDED is only reachable by cancelling the order. */
    @Transactional
    public OrderResponse updatePaymentStatus(Long id, PaymentStatus target) {
        Order order = getEntity(id);
        Payment payment = order.getPayment();
        if (payment == null) {
            throw new ResourceNotFoundException("Order " + order.getOrderNumber() + " has no payment");
        }
        if (target == PaymentStatus.REFUNDED) {
            throw new BusinessRuleException("Refunds are issued by cancelling the order, not by setting payment status");
        }
        if (order.getStatus().isFinal()) {
            throw new BusinessRuleException(
                    "Cannot change payment on order " + order.getOrderNumber() + ": order is " + order.getStatus());
        }
        if (!payment.getPaymentStatus().canTransitionTo(target)) {
            throw new BusinessRuleException(
                    "Cannot change payment status from " + payment.getPaymentStatus() + " to " + target);
        }
        payment.setPaymentStatus(target);
        return toResponse(orderRepository.save(order));
    }

    // --- business rules -----------------------------------------------------

    private void reserveStock(Product product, int quantity) {
        if (!Boolean.TRUE.equals(product.getActive())) {
            throw new BusinessRuleException("Product '" + product.getSku() + "' is not active");
        }
        if (product.getStockQuantity() < quantity) {
            throw new BusinessRuleException("Insufficient stock for product '" + product.getSku()
                    + "': requested " + quantity + ", available " + product.getStockQuantity());
        }
        product.setStockQuantity(product.getStockQuantity() - quantity);
    }

    /** Warning only — an order that drains stock below the threshold is still valid. */
    private void warnOnLowStock(Order order) {
        for (OrderItem item : order.getItems()) {
            Product product = item.getProduct();
            if (product.getStockQuantity() < LOW_STOCK_THRESHOLD) {
                log.warn("Low stock alert: {} has {} units left", product.getName(), product.getStockQuantity());
            }
        }
    }

    private void transition(Order order, OrderStatus target) {
        OrderStatus current = order.getStatus();
        if (!current.canTransitionTo(target)) {
            throw new BusinessRuleException("Cannot transition order " + order.getOrderNumber()
                    + " from " + current + " to " + target
                    + " (allowed: " + current.allowedTransitions() + ")");
        }
        if (target == OrderStatus.CANCELLED) {
            restoreStock(order);
            refundIfPaid(order);
        }
        order.setStatus(target);
    }

    private void restoreStock(Order order) {
        for (OrderItem item : order.getItems()) {
            Product product = item.getProduct();
            product.setStockQuantity(product.getStockQuantity() + item.getQuantity());
        }
    }

    private void refundIfPaid(Order order) {
        Payment payment = order.getPayment();
        if (payment != null && payment.getPaymentStatus() == PaymentStatus.COMPLETED) {
            payment.setPaymentStatus(PaymentStatus.REFUNDED);
        }
    }

    // --- helpers ------------------------------------------------------------

    private Order getEntity(Long id) {
        return orderRepository.findWithDetailsById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order", id));
    }

    private OrderResponse toResponse(Order order) {
        Customer customer = order.getCustomer();
        return OrderResponse.builder()
                .id(order.getId())
                .orderNumber(order.getOrderNumber())
                .customerId(customer.getId())
                .customerName(customer.getFirstName() + " " + customer.getLastName())
                .customerEmail(customer.getEmail())
                .status(order.getStatus())
                .totalAmount(order.getTotalAmount())
                .shippingAddress(order.getShippingAddress())
                .notes(order.getNotes())
                .items(order.getItems().stream().map(this::toItemResponse).toList())
                .payment(toPaymentResponse(order.getPayment()))
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }

    private OrderItemResponse toItemResponse(OrderItem item) {
        Product product = item.getProduct();
        return OrderItemResponse.builder()
                .id(item.getId())
                .productId(product.getId())
                .productName(product.getName())
                .sku(product.getSku())
                .quantity(item.getQuantity())
                .unitPrice(item.getUnitPrice())
                .subtotal(item.getSubtotal())
                .build();
    }

    private PaymentResponse toPaymentResponse(Payment payment) {
        if (payment == null) {
            return null;
        }
        return PaymentResponse.builder()
                .id(payment.getId())
                .paymentMethod(payment.getPaymentMethod())
                .paymentStatus(payment.getPaymentStatus())
                .amount(payment.getAmount())
                .createdAt(payment.getCreatedAt())
                .build();
    }
}
