package com.rbdip.bookstore.purchase;

/** Контракт поиска покупки по имени клиента; результат не является авторизацией автора отзыва. */
public interface PurchaseHistory {

    boolean hasPurchased(Long productId, String customerName);
}
