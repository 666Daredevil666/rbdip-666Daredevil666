package com.rbdip.bookstore.customer;

import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CustomerRepository {

    private static final String RESOLVE_CUSTOMER = "SELECT resolve_customer(?, ?, ?)";

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
