package com.rbdip.bookstore.order;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Координирует создание заказа в одной транзакции. Валидация, подготовка
 * позиций, расчёт цены, сохранение и уведомление делегируются компонентам.
 */
@Service
public class OrderService {

    private final OrderRequestValidator validator;
    private final OrderItemFactory itemFactory;
    private final PricingCalculator pricingCalculator;
    private final OrderWriter orderWriter;
    private final OrderConfirmationNotifier confirmationNotifier;

    public OrderService(
            OrderRequestValidator validator,
            OrderItemFactory itemFactory,
            PricingCalculator pricingCalculator,
            OrderWriter orderWriter,
            OrderConfirmationNotifier confirmationNotifier) {
        this.validator = validator;
        this.itemFactory = itemFactory;
        this.pricingCalculator = pricingCalculator;
        this.orderWriter = orderWriter;
        this.confirmationNotifier = confirmationNotifier;
    }

    @Transactional
    public Order createOrder(CreateOrderRequest request) {
        validator.validate(request);
        List<PreparedOrderItem> items = itemFactory.prepare(request.items());
        BigDecimal total = pricingCalculator.calculateOrderTotal(
                items.stream().map(PreparedOrderItem::pricingLine).toList(),
                request.customerType(), request.couponCode());
        Order order = orderWriter.save(request, items);
        confirmationNotifier.send(request.customerFullName(), order.getId(), total);
        return order;
    }
}
