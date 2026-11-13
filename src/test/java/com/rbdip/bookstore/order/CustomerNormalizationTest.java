package com.rbdip.bookstore.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.rbdip.bookstore.reference.AbstractIntegrationTest;
import com.rbdip.bookstore.support.ContactTestData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class CustomerNormalizationTest extends AbstractIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Test
    void reusesCustomerWithIdenticalContactsIncludingNullPhone() {
        Long productId = createProduct("Book", "10.00");
        CreateOrderRequest request = request("Moscow", null, productId);

        createOrder(request);
        createOrder(request);

        assertThat(count("customers")).isEqualTo(1);
        assertThat(count("orders")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT count(DISTINCT customer_id) FROM orders", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap("SELECT address, phone FROM customers"))
                .containsEntry("address", "Moscow")
                .containsEntry("phone", null);
    }

    @Test
    void returnsUsableContactsAfterTransactionEnds() {
        Long productId = createProduct("Book", "10.00");

        Order order = orderService.createOrder(request("Moscow", "+79990000000", productId));

        assertThat(order.getId()).isNotNull();
        assertThat(order.getCustomerFullName()).isEqualTo("Ivan Petrov");
        assertThat(order.getCustomerAddress()).isEqualTo("Moscow");
        assertThat(order.getCustomerPhone()).isEqualTo("+79990000000");
    }

    @Test
    void acceptsMaximumLengthUnicodeContacts() {
        Long productId = createProduct("Book", "10.00");
        ContactTestData.Contacts contacts = ContactTestData.maximumLengthUnicodeContacts();
        CreateOrderRequest request = new CreateOrderRequest(contacts.fullName(), contacts.address(),
                contacts.phone(), null, null, List.of(new CreateOrderRequest.Item(productId, 1)));

        createOrder(request);
        Order order = orderService.createOrder(request);

        assertThat(order.getCustomerFullName()).isEqualTo(contacts.fullName());
        assertThat(order.getCustomerAddress()).isEqualTo(contacts.address());
        assertThat(order.getCustomerPhone()).isEqualTo(contacts.phone());
        assertThat(count("customers")).isEqualTo(1);
        assertThat(count("orders")).isEqualTo(2);
    }

    @Test
    void distinguishesContactFieldBoundaries() {
        Long productId = createProduct("Book", "10.00");
        List<CreateOrderRequest.Item> items = List.of(new CreateOrderRequest.Item(productId, 1));

        createOrder(new CreateOrderRequest("A", "BC", "D", null, null, items));
        createOrder(new CreateOrderRequest("AB", "C", "D", null, null, items));
        createOrder(new CreateOrderRequest("A", "B", "CD", null, null, items));

        assertThat(count("customers")).isEqualTo(3);
    }

    @Test
    void directContactUpdatesKeepDeduplicationConsistent() {
        Long productId = createProduct("Book", "10.00");
        createOrder(request("Moscow", null, productId));

        jdbcTemplate.update("UPDATE customers SET address = 'SPb'");
        createOrder(request("SPb", null, productId));

        assertThat(count("customers")).isEqualTo(1);
        createOrder(request("Moscow", null, productId));
        assertThat(count("customers")).isEqualTo(2);
    }

    @Test
    void keepsSameNameWithDifferentContactsSeparate() {
        Long productId = createProduct("Book", "10.00");
        createOrder(request("Moscow", null, productId));
        createOrder(request("SPb", null, productId));
        createOrder(request("Moscow", "+79990000000", productId));
        createOrder(request("Moscow", "", productId));

        assertThat(count("customers")).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("SELECT count(DISTINCT customer_id) FROM orders", Integer.class))
                .isEqualTo(4);
    }

    @Test
    void usesRequestedProductIdEvenWhenProductNamesMatch() {
        createProduct("Same title", "10.00");
        Long requestedId = createProduct("Same title", "20.00");

        createOrder(request("Moscow", null, requestedId));

        assertThat(jdbcTemplate.queryForObject("SELECT product_id FROM order_items", Long.class))
                .isEqualTo(requestedId);
        assertThat(jdbcTemplate.queryForObject("SELECT quantity FROM order_items", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentOrdersReuseOneCustomer() throws Exception {
        Long productId = createProduct("Book", "10.00");
        CreateOrderRequest request = request("Moscow", null, productId);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            List<Future<ResponseEntity<Map>>> responses = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                responses.add(pool.submit(() -> {
                    start.await();
                    return restTemplate.postForEntity("/orders", request, Map.class);
                }));
            }
            start.countDown();
            for (Future<ResponseEntity<Map>> response : responses) {
                assertThat(response.get(20, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            }
        }
        assertThat(count("customers")).isEqualTo(1);
        assertThat(count("orders")).isEqualTo(4);
        assertThat(count("order_items")).isEqualTo(4);
    }

    @Test
    void invalidOrderDoesNotLeaveAnOrphanCustomer() {
        Long productId = createProduct("Book", "10.00");
        CreateOrderRequest request = new CreateOrderRequest("Ivan Petrov", "Moscow", null, null, null,
                List.of(new CreateOrderRequest.Item(productId, 1), new CreateOrderRequest.Item(productId, 0)));

        assertThat(restTemplate.postForEntity("/orders", request, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(count("customers")).isZero();
        assertThat(count("orders")).isZero();
        assertThat(count("order_items")).isZero();
    }

    @Test
    void failedOrderInsertRollsBackCustomerCreation() {
        Long productId = createProduct("Book", "10.00");
        jdbcTemplate.execute("ALTER TABLE orders ADD CONSTRAINT test_reject_new CHECK (status <> 'new')");
        try {
            assertThat(restTemplate.postForEntity("/orders", request("Moscow", null, productId), Map.class)
                    .getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(count("customers")).isZero();
            assertThat(count("orders")).isZero();
            assertThat(count("order_items")).isZero();
        } finally {
            jdbcTemplate.execute("ALTER TABLE orders DROP CONSTRAINT test_reject_new");
        }
    }

    private void createOrder(CreateOrderRequest request) {
        assertThat(restTemplate.postForEntity("/orders", request, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    private Long createProduct(String name, String price) {
        Map<?, ?> body = restTemplate.postForEntity("/products", Map.of("name", name, "price", price), Map.class)
                .getBody();
        return Long.valueOf(body.get("id").toString());
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static CreateOrderRequest request(String address, String phone, Long productId) {
        return new CreateOrderRequest("Ivan Petrov", address, phone, null, null,
                List.of(new CreateOrderRequest.Item(productId, 1)));
    }
}
