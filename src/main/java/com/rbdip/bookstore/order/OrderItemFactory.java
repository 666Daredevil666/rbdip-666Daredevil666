package com.rbdip.bookstore.order;

import com.rbdip.bookstore.product.Product;
import com.rbdip.bookstore.product.ProductRepository;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class OrderItemFactory {

    private final ProductRepository productRepository;

    public OrderItemFactory(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    List<PreparedOrderItem> prepare(List<CreateOrderRequest.Item> items) {
        return items.stream().map(this::prepareItem).toList();
    }

    private PreparedOrderItem prepareItem(CreateOrderRequest.Item item) {
        Product product = productRepository.findById(item.productId())
                .orElseThrow(() -> new IllegalArgumentException("product " + item.productId() + " not found"));
        int quantity = item.quantity() == null ? 1 : item.quantity();
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        return new PreparedOrderItem(product, quantity);
    }
}
