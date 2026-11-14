package com.rbdip.bookstore.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.rbdip.bookstore.reference.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

class CustomerNameApiTest extends AbstractIntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"Anna Sidorova", "Single", " Leading  Name ", "Trailing ", "A\tB", "Иван Иванов"})
    void preservesPublicNameAfterPhysicalNameSplit(String name) {
        Map<?, ?> product = restTemplate.postForEntity("/products", Map.of("name", "Book", "price", "10.00"), Map.class)
                .getBody();
        Long productId = Long.valueOf(product.get("id").toString());
        CreateOrderRequest request = new CreateOrderRequest(name, "Address", null, null, null,
                List.of(new CreateOrderRequest.Item(productId, 1)));

        assertThat(restTemplate.postForEntity("/orders", request, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        List<?> orders = restTemplate.getForEntity("/orders", List.class).getBody();
        assertThat(((Map<?, ?>) orders.getFirst()).get("customerFullName")).isEqualTo(name);
        assertThat(jdbcTemplate.queryForObject("SELECT first_name || name_separator || last_name FROM customers", String.class))
                .isEqualTo(name);
        assertThat(jdbcTemplate.queryForList("SELECT column_name FROM information_schema.columns"
                + " WHERE table_name = 'customers'", String.class)).doesNotContain("full_name");
    }
}
