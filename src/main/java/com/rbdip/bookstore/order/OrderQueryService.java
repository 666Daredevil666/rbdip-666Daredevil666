package com.rbdip.bookstore.order;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderQueryService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;

    public OrderQueryService(OrderRepository orderRepository, OrderItemRepository orderItemRepository) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
    }

    @Transactional(readOnly = true)
    public List<OrderSummary> listOrders() {
        List<Order> orders = orderRepository.findAllForListing();
        if (orders.isEmpty()) {
            return List.of();
        }
        Map<Long, List<OrderItem>> items = orderItemRepository.findAllForListing().stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId));
        return orders.stream().map(order -> new OrderSummary(
                order.getId(), order.getCustomerFullName(), order.getStatus(),
                items.getOrDefault(order.getId(), List.of()).stream()
                        .map(item -> new OrderSummary.Item(item.getProductName(), item.getQuantity()))
                        .toList())).toList();
    }
}
