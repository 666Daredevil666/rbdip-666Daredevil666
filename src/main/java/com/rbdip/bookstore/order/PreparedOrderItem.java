package com.rbdip.bookstore.order;

import java.math.BigDecimal;

/** Снимок товара и проверенного количества для расчёта и сохранения одной позиции. */
record PreparedOrderItem(String productName, BigDecimal productPrice, int quantity) {

    PricingCalculator.LineItem pricingLine() {
        return new PricingCalculator.LineItem(productPrice, quantity);
    }

    OrderItem toOrderItem(Long orderId) {
        return new OrderItem(orderId, productName, productPrice, quantity);
    }
}
