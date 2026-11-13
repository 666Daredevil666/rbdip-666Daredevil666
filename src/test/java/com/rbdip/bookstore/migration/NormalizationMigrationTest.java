package com.rbdip.bookstore.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rbdip.bookstore.support.ContactTestData;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class NormalizationMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void migratesExistingOrdersWithoutLosingContactsItemsOrHistoricalProductValues() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).target("1").load().migrate();
        seedLegacyData(jdbc);
        List<Map<String, Object>> orders = jdbc.queryForList(
                "SELECT id, customer_full_name, customer_address, customer_phone, status, created_at"
                        + " FROM orders ORDER BY id");
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT id, order_id, product_name, product_price, quantity FROM order_items ORDER BY id");

        Flyway.configure().dataSource(source).target("2").load().migrate();

        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables"
                + " WHERE table_schema = 'public'", String.class)).contains("customers");
        assertThat(jdbc.queryForList("SELECT o.id, c.full_name AS customer_full_name,"
                + " c.address AS customer_address, c.phone AS customer_phone, o.status, o.created_at"
                + " FROM orders o JOIN customers c ON c.id = o.customer_id ORDER BY o.id")).isEqualTo(orders);
        assertThat(jdbc.queryForList("SELECT i.id, i.order_id, p.name AS product_name,"
                + " p.price AS product_price, i.quantity FROM order_items i"
                + " JOIN products p ON p.id = i.product_id ORDER BY i.id")).isEqualTo(items);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM products", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT customer_id) FROM orders WHERE id IN (1,2)",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT product_id FROM order_items WHERE id = 1", Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT description FROM products WHERE id = 1", String.class))
                .isEqualTo("Keep description");
        assertForeignKeys(jdbc);
        jdbc.update("INSERT INTO customers(full_name) VALUES ('Future Lab3 seed')");
        Long customerId = jdbc.queryForObject("SELECT id FROM customers WHERE full_name='Future Lab3 seed'", Long.class);
        assertThat(customerId).isGreaterThan(5L);
        Long productId = jdbc.queryForObject("INSERT INTO products(name, price) VALUES ('New', 1) RETURNING id", Long.class);
        assertThat(productId).isGreaterThan(6L);
    }

    private void seedLegacyData(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO products(name, price, description) VALUES"
                + " ('Same', 10, 'Keep description'), ('Same', 10, 'Duplicate'),"
                + " ('Same', 20, NULL), ('Changed', 99, NULL)");
        jdbc.update("INSERT INTO orders(customer_full_name, customer_address, customer_phone, status) VALUES"
                + " ('Ivan', 'Moscow', NULL, 'new'), ('Ivan', 'Moscow', NULL, 'paid'),"
                + " ('Ivan', 'SPb', NULL, 'new'), ('Ivan', 'Moscow', '', 'new'),"
                + " ('Legacy', NULL, NULL, 'new')");
        jdbc.update("INSERT INTO order_items(order_id, product_name, product_price, quantity) VALUES"
                + " (1, 'Same', 10, 2), (1, 'Same', 20, 1), (2, 'Same', 10, 3),"
                + " (3, 'Changed', 15, 4), (4, 'Missing', 5, 1), (5, 'Missing', 5, 2)");
        ContactTestData.Contacts contacts = ContactTestData.maximumLengthUnicodeContacts();
        jdbc.update("INSERT INTO orders(customer_full_name, customer_address, customer_phone) VALUES (?, ?, ?)",
                contacts.fullName(), contacts.address(), contacts.phone());
    }

    private void assertForeignKeys(JdbcTemplate jdbc) {
        assertThatThrownBy(() -> jdbc.update("UPDATE orders SET customer_id = -1 WHERE id = 1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE order_items SET product_id = -1 WHERE id = 1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE orders SET customer_id = NULL WHERE id = 1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE order_items SET product_id = NULL WHERE id = 1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
