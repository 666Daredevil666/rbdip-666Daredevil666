package com.rbdip.bookstore.customer;

import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CustomerRepository {

    private static final String RESOLVE_CUSTOMER = """
            INSERT INTO customers (full_name, address, phone) VALUES (?, ?, ?)
            ON CONFLICT ON CONSTRAINT customers_contact_key
            DO UPDATE SET full_name = EXCLUDED.full_name
            WHERE customers.full_name = EXCLUDED.full_name
              AND customers.address IS NOT DISTINCT FROM EXCLUDED.address
              AND customers.phone IS NOT DISTINCT FROM EXCLUDED.phone
            RETURNING id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;

    public CustomerRepository(JdbcTemplate jdbcTemplate, EntityManager entityManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.entityManager = entityManager;
    }

    /** Атомарно переиспользует контактный набор, включая nullable-поля, в транзакции заказа. */
    public Customer findOrCreate(String fullName, String address, String phone) {
        Long id = jdbcTemplate.queryForObject(RESOLVE_CUSTOMER, Long.class, fullName, address, phone);
        return entityManager.find(Customer.class, id);
    }
}
