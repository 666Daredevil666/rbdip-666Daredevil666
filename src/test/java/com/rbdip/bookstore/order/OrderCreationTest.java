package com.rbdip.bookstore.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.rbdip.bookstore.reference.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class OrderCreationTest extends AbstractIntegrationTest {

    @Test
    void preservesDefaultQuantityAndTheProductOfEachOrderLine() {
        Long firstProduct = createProduct("Clean Code", "35.00");
        Long secondProduct = createProduct("Refactoring", "40.00");
        CreateOrderRequest request = request(List.of(
                new CreateOrderRequest.Item(firstProduct, null),
                new CreateOrderRequest.Item(secondProduct, 3),
                new CreateOrderRequest.Item(firstProduct, 2)));

        ResponseEntity<Map> created = restTemplate.postForEntity("/orders", request, Map.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsEntry("status", "new");
        ResponseEntity<List> listed = restTemplate.getForEntity("/orders", List.class);
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listed.getBody()).hasSize(1);
        Map<?, ?> order = (Map<?, ?>) listed.getBody().getFirst();
        assertThat(order.get("customerFullName")).isEqualTo("Ivan Petrov");
        assertThat(order.get("items")).isEqualTo(List.of(
                Map.of("productName", "Clean Code", "quantity", 1),
                Map.of("productName", "Refactoring", "quantity", 3),
                Map.of("productName", "Clean Code", "quantity", 2)));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveQuantityWithoutSavingAnyPartOfOrder(int quantity) {
        Long productId = createProduct("Clean Code", "35.00");
        CreateOrderRequest request = request(List.of(
                new CreateOrderRequest.Item(productId, 1),
                new CreateOrderRequest.Item(productId, quantity)));

        assertRejected(request, "quantity must be positive");
    }

    @Test
    void rejectsUnknownProductWithoutSavingAnyPartOfOrder() {
        Long productId = createProduct("Clean Code", "35.00");
        CreateOrderRequest request = request(List.of(
                new CreateOrderRequest.Item(productId, 1),
                new CreateOrderRequest.Item(Long.MAX_VALUE, 1)));

        assertRejected(request, "product " + Long.MAX_VALUE + " not found");
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void preservesRequiredFieldValidation(CreateOrderRequest request, String message) {
        assertRejected(request, message);
    }

    private void assertRejected(CreateOrderRequest request, String message) {
        ResponseEntity<Map> response = restTemplate.postForEntity("/orders", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", message);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM orders", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM order_items", Long.class)).isZero();
    }

    private Long createProduct(String name, String price) {
        Map<?, ?> body = restTemplate.postForEntity(
                "/products", Map.of("name", name, "price", new BigDecimal(price)), Map.class).getBody();
        return Long.valueOf(body.get("id").toString());
    }

    private static CreateOrderRequest request(List<CreateOrderRequest.Item> items) {
        return new CreateOrderRequest("Ivan Petrov", "Moscow", null, null, null, items);
    }

    private static Stream<Arguments> invalidRequests() {
        List<CreateOrderRequest.Item> items = List.of(new CreateOrderRequest.Item(1L, 1));
        return Stream.of(
                Arguments.of(new CreateOrderRequest(null, "Moscow", null, null, null, items),
                        "customerFullName is required"),
                Arguments.of(new CreateOrderRequest("  ", "Moscow", null, null, null, items),
                        "customerFullName is required"),
                Arguments.of(new CreateOrderRequest("Ivan", null, null, null, null, items),
                        "customerAddress is required"),
                Arguments.of(new CreateOrderRequest("Ivan", "  ", null, null, null, items),
                        "customerAddress is required"),
                Arguments.of(request(null), "order must contain at least one item"),
                Arguments.of(request(List.of()), "order must contain at least one item"));
    }
}
