package com.rbdip.bookstore.order;

import java.util.List;

public record OrderSummary(Long id, String customerFullName, String status, List<Item> items) {

    public record Item(String productName, Integer quantity) {
    }
}
