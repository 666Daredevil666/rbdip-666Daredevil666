package com.rbdip.bookstore.order;

import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Имитирует отправку письма через журнал приложения; почтовый транспорт не настроен. */
@Component
public class OrderConfirmationNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(OrderConfirmationNotifier.class);

    public void send(String customerName, Long orderId, BigDecimal total) {
        LOG.info("[email] Dear {}, your order #{} for {} has been placed.", customerName, orderId, total);
    }
}
