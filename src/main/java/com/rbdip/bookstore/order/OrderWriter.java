package com.rbdip.bookstore.order;

import com.rbdip.bookstore.customer.Customer;
import com.rbdip.bookstore.customer.CustomerRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/** Сохраняет заказ и его позиции внутри транзакции вызывающего OrderService. */
@Component
public class OrderWriter {

    private static final String INITIAL_STATUS = "new";

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CustomerRepository customerRepository;

    public OrderWriter(OrderRepository orderRepository, OrderItemRepository orderItemRepository,
            CustomerRepository customerRepository) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.customerRepository = customerRepository;
    }

    Order save(CreateOrderRequest request, List<PreparedOrderItem> items) {
        Customer customer = customerRepository.findOrCreate(
                request.customerFullName(), request.customerAddress(), request.customerPhone());
        Order order = orderRepository.save(new Order(customer, INITIAL_STATUS));
        for (PreparedOrderItem item : items) {
            orderItemRepository.save(item.toOrderItem(order.getId()));
        }
        return order;
    }
}
