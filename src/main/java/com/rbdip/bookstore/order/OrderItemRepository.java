package com.rbdip.bookstore.order;

import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByOrderId(Long orderId);

    @EntityGraph(attributePaths = "product")
    @Query("select i from OrderItem i order by i.id")
    List<OrderItem> findAllForListing();

    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM order_items i
                JOIN orders o ON o.id = i.order_id
                JOIN customer_read_model c ON c.id = o.customer_id
                WHERE i.product_id = :productId AND c.full_name = :customerName
            )
            """, nativeQuery = true)
    boolean existsPurchase(@Param("productId") Long productId, @Param("customerName") String customerName);
}
