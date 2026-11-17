package com.rbdip.bookstore.order;

import com.rbdip.bookstore.purchase.PurchaseHistory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderPurchaseHistory implements PurchaseHistory {

    private final OrderItemRepository orderItemRepository;

    public OrderPurchaseHistory(OrderItemRepository orderItemRepository) {
        this.orderItemRepository = orderItemRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasPurchased(Long productId, String customerName) {
        if (productId == null || customerName == null) {
            return false;
        }
        return orderItemRepository.existsPurchase(productId, customerName);
    }
}
