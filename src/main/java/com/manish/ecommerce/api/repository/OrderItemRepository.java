package com.manish.ecommerce.api.repository;

import com.manish.ecommerce.api.entity.OrderItem;
import com.manish.ecommerce.api.entity.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByOrderId(Long orderId);

    boolean existsByProductId(Long productId);

    /** True when the customer has an order in the given status that contains the product. */
    boolean existsByOrderCustomerIdAndProductIdAndOrderStatus(Long customerId, Long productId, OrderStatus status);
}
