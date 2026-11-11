package com.rbdip.bookstore.order;

import java.util.List;
import org.springframework.stereotype.Component;

/** Сохраняет заказ и его позиции внутри транзакции вызывающего OrderService. */
@Component
public class OrderWriter {

    private static final String INITIAL_STATUS = "new";

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;

    public OrderWriter(OrderRepository orderRepository, OrderItemRepository orderItemRepository) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
    }

    Order save(CreateOrderRequest request, List<PreparedOrderItem> items) {
        Order order = orderRepository.save(new Order(
                request.customerFullName(), request.customerAddress(), request.customerPhone(), INITIAL_STATUS));
        for (PreparedOrderItem item : items) {
            orderItemRepository.save(item.toOrderItem(order.getId()));
        }
        return order;
    }
}
