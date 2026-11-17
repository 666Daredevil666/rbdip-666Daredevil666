package com.rbdip.bookstore.purchase;

import static org.assertj.core.api.Assertions.assertThat;

import com.rbdip.bookstore.order.CreateOrderRequest;
import com.rbdip.bookstore.reference.AbstractIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

class PurchaseHistoryTest extends AbstractIntegrationTest {

    @Autowired
    private PurchaseHistory history;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void matchesTheSameCustomerAndProductWithoutLoadingWholeHistory() {
        Long first = createProduct("First");
        Long second = createProduct("Second");
        createOrder("Alice", first);
        createOrder("Bob", second);

        assertMatch(first, "Alice", true);
        assertMatch(second, "Bob", true);
        assertMatch(second, "Alice", false);
        assertMatch(first, "Bob", false);
        assertMatch(first, "Unknown", false);
        assertMatch(first, "alice", false);
        assertMatch(first, " Alice ", false);
    }

    @Test
    void returnsFalseWhenNoOrdersExist() {
        assertMatch(createProduct("Book"), "Alice", false);
    }

    @Test
    void returnsFalseForMissingIdentifiers() {
        assertMatch(null, "Alice", false);
        assertMatch(1L, null, false);
    }

    private void assertMatch(Long productId, String customer, boolean expected) {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        assertThat(history.hasPurchased(productId, customer)).isEqualTo(expected);

        assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(1);
    }

    private Long createProduct(String name) {
        Map<?, ?> product = restTemplate.postForEntity("/products", Map.of("name", name, "price", "10.00"), Map.class)
                .getBody();
        return Long.valueOf(product.get("id").toString());
    }

    private void createOrder(String customer, Long productId) {
        CreateOrderRequest request = new CreateOrderRequest(customer, "Address", null, null, null,
                List.of(new CreateOrderRequest.Item(productId, 1)));
        assertThat(restTemplate.postForEntity("/orders", request, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }
}
