package com.rbdip.bookstore.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.rbdip.bookstore.order.CreateOrderRequest;
import com.rbdip.bookstore.reference.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ReviewApiTest extends AbstractIntegrationTest {

    @Test
    void acceptsUnpurchasedAnonymousReviewAndPreservesMissingComment() {
        Long productId = createProduct("Book");

        ResponseEntity<Map> created = restTemplate.postForEntity(
                "/products/" + productId + "/reviews", Map.of("rating", 4), Map.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("id")).isNotNull();
        assertThat(reviews(productId)).isEqualTo(List.of(
                Map.of("authorName", "anonymous", "rating", 4, "comment", "")));
    }

    @Test
    void listsOnlyReviewsForRequestedProduct() {
        Long first = createProduct("First");
        Long second = createProduct("Second");
        addReview(first, "Alice", "First review");
        addReview(second, "Bob", "Second review");

        assertThat(reviews(first)).isEqualTo(List.of(
                Map.of("authorName", "Alice", "rating", 5, "comment", "First review")));
        assertThat(reviews(second)).isEqualTo(List.of(
                Map.of("authorName", "Bob", "rating", 5, "comment", "Second review")));
    }

    @Test
    void keepsReviewsAvailableForBothPurchasersAndOtherAuthors() {
        Long productId = createProduct("Book");
        CreateOrderRequest request = new CreateOrderRequest("Alice", "Address", null, null, null,
                List.of(new CreateOrderRequest.Item(productId, 1)));
        assertThat(restTemplate.postForEntity("/orders", request, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        addReview(productId, "Alice", "Purchased");
        addReview(productId, "Bob", "Not purchased");

        assertThat(reviews(productId)).hasSize(2).extracting("authorName", "rating", "comment")
                .containsExactlyInAnyOrder(tuple("Alice", 5, "Purchased"), tuple("Bob", 5, "Not purchased"));
    }

    private Long createProduct(String name) {
        Map<?, ?> product = restTemplate.postForEntity("/products", Map.of("name", name, "price", "10.00"), Map.class)
                .getBody();
        return Long.valueOf(product.get("id").toString());
    }

    private void addReview(Long productId, String author, String comment) {
        assertThat(restTemplate.postForEntity("/products/" + productId + "/reviews",
                Map.of("authorName", author, "rating", 5, "comment", comment), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    private List<?> reviews(Long productId) {
        ResponseEntity<List> response = restTemplate.getForEntity("/products/" + productId + "/reviews", List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }
}
