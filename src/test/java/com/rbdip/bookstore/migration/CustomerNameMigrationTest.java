package com.rbdip.bookstore.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rbdip.bookstore.support.ContactTestData;
import java.util.Arrays;
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
class CustomerNameMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void preservesNamesAndIdentityAcrossDualWriteBackfillAndContract() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(source);
        assertThat(Arrays.stream(Flyway.configure().dataSource(source).load().info().all())
                .filter(m -> m.getDescription().contains("lab3")).count()).isGreaterThanOrEqualTo(3);
        migrate(source, "2");
        List<String> names = List.of("Anna Sidorova", "Single", " Leading  Name ", "Trailing ",
                "", "  ", "A\tB", ContactTestData.maximumLengthUnicodeContacts().fullName());
        for (String name : names) {
            jdbc.update("INSERT INTO customers(full_name, address, phone) VALUES (?, 'Address', NULL)", name);
        }
        List<Map<String, Object>> original = jdbc.queryForList(
                "SELECT id, full_name, address, phone, encode(contact_key, 'hex') AS key FROM customers ORDER BY id");

        migrate(source, "3");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers WHERE first_name IS NULL", Integer.class))
                .isEqualTo(names.size());
        assertThat(snapshot(jdbc)).isEqualTo(original);
        Long oldWriter = jdbc.queryForObject("INSERT INTO customers(full_name) VALUES ('Old Writer') RETURNING id", Long.class);
        assertParts(jdbc, oldWriter, "Old", "Writer", " ");
        jdbc.update("UPDATE customers SET full_name = 'Updated  Name ' WHERE id = ?", oldWriter);
        assertParts(jdbc, oldWriter, "Updated", " Name ", " ");
        Long newWriter = resolve(jdbc, "New Writer", null);
        assertThat(jdbc.queryForObject("SELECT full_name FROM customers WHERE id = ?", String.class, newWriter))
                .isEqualTo("New Writer");
        jdbc.update("UPDATE customers SET first_name = 'Changed', last_name = 'Writer' WHERE id = ?", newWriter);
        assertThat(jdbc.queryForObject("SELECT full_name FROM customers WHERE id = ?", String.class, newWriter))
                .isEqualTo("Changed Writer");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE customers SET full_name = 'A B', first_name = 'Wrong' WHERE id = ?", newWriter))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(resolve(jdbc, names.getFirst(), "Address")).isEqualTo(1L);

        migrate(source, "4");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers"
                + " WHERE first_name IS NULL OR last_name IS NULL OR name_separator IS NULL", Integer.class)).isZero();
        assertThat(snapshot(jdbc).subList(0, names.size())).isEqualTo(original);
        migrate(source, "5");
        migrate(source, "6");
        List<Map<String, Object>> beforeContract = snapshot(jdbc);

        migrate(source, "7");

        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns"
                + " WHERE table_name = 'customers'", String.class))
                .contains("first_name", "last_name").doesNotContain("full_name");
        assertThat(snapshot(jdbc)).isEqualTo(beforeContract);
        assertThat(resolve(jdbc, "Changed Writer", null)).isEqualTo(newWriter);
        Long afterContract = resolve(jdbc, "After Contract", null);
        assertParts(jdbc, afterContract, "After", "Contract", " ");
        jdbc.update("UPDATE customers SET last_name = 'Updated' WHERE id = ?", afterContract);
        assertThat(resolve(jdbc, "After Updated", null)).isEqualTo(afterContract);
        assertThatThrownBy(() -> resolve(jdbc, "x".repeat(256), null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void migrate(PGSimpleDataSource source, String version) {
        Flyway.configure().dataSource(source).target(version).load().migrate();
    }

    private Long resolve(JdbcTemplate jdbc, String name, String address) {
        return jdbc.queryForObject("SELECT resolve_customer(?, ?, NULL)", Long.class, name, address);
    }

    private List<Map<String, Object>> snapshot(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT v.id, v.full_name, v.address, v.phone, encode(c.contact_key, 'hex') AS key"
                + " FROM customer_read_model v JOIN customers c ON c.id = v.id ORDER BY v.id");
    }

    private void assertParts(JdbcTemplate jdbc, Long id, String first, String last, String separator) {
        assertThat(jdbc.queryForMap("SELECT first_name, last_name, name_separator FROM customers WHERE id = ?", id))
                .isEqualTo(Map.of("first_name", first, "last_name", last, "name_separator", separator));
    }
}
