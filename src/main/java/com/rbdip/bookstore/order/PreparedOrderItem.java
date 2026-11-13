package com.rbdip.bookstore.order;

import com.rbdip.bookstore.product.Product;

/** Товар и проверенное количество для расчёта и сохранения одной позиции. */
record PreparedOrderItem(Product product, int quantity) {

    PricingCalculator.LineItem pricingLine() {
        return new PricingCalculator.LineItem(product.getPrice(), quantity);
    }

    OrderItem toOrderItem(Long orderId) {
        return new OrderItem(orderId, product, quantity);
    }
}
