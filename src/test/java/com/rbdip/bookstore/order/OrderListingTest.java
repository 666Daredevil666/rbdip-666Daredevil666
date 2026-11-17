package com.rbdip.bookstore.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.rbdip.bookstore.reference.AbstractIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class OrderListingTest extends AbstractIntegrationTest {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void returnsEmptyListWithoutLoadingItems() {
        Statistics stats = statistics();
        stats.clear();

        ResponseEntity<List> response = restTemplate.getForEntity("/orders", List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
        assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 20})
    void boundsQueriesWithDistinctCustomersAndProductsAndKeepsAllOrderLines(int count) {
        List<Map<String, Object>> expected = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Long firstProduct = createProduct("First " + i);
            Long secondProduct = createProduct("Second " + i);
            CreateOrderRequest request = new CreateOrderRequest("Customer " + i, "Address " + i,
                    null, null, null, List.of(new CreateOrderRequest.Item(firstProduct, 1),
                            new CreateOrderRequest.Item(secondProduct, 2),
                            new CreateOrderRequest.Item(firstProduct, 3)));
            ResponseEntity<Map> created = restTemplate.postForEntity("/orders", request, Map.class);
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            expected.add(Map.of("id", created.getBody().get("id"), "customerFullName", "Customer " + i,
                    "status", "new", "items", List.of(
                            Map.of("productName", "First " + i, "quantity", 1),
                            Map.of("productName", "Second " + i, "quantity", 2),
                            Map.of("productName", "First " + i, "quantity", 3))));
        }
        Integer emptyOrder = jdbcTemplate.queryForObject(
                "INSERT INTO orders(customer_id,status) VALUES (1,'legacy') RETURNING id", Integer.class);
        expected.add(Map.of("id", emptyOrder, "customerFullName", "Customer 0", "status", "legacy", "items", List.of()));
        Statistics stats = statistics();
        stats.clear();

        ResponseEntity<List> response = restTemplate.getForEntity("/orders", List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(expected);
        long queries = stats.getPrepareStatementCount();
        System.out.printf("Order listing: orders=%d, distinct products=%d, SQL=%d%n", count + 1, count * 2, queries);
        assertThat(queries).isLessThanOrEqualTo(3);
    }

    private Long createProduct(String name) {
        Map<?, ?> product = restTemplate.postForEntity("/products", Map.of("name", name, "price", "10.00"), Map.class)
                .getBody();
        return Long.valueOf(product.get("id").toString());
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }
}
