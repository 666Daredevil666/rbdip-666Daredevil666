package com.rbdip.bookstore.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/** Стабильная проекция имени; запись идёт через resolve_customer в физическую таблицу customers. */
@Entity
@Immutable
@Table(name = "customer_read_model")
public class Customer {

    private static final int MAX_ADDRESS_LENGTH = 500;
    private static final int MAX_PHONE_LENGTH = 50;

    @Id
    private Long id;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(length = MAX_ADDRESS_LENGTH)
    private String address;

    @Column(length = MAX_PHONE_LENGTH)
    private String phone;

    protected Customer() {
        // for JPA
    }

    public Long getId() {
        return id;
    }

    public String getFullName() {
        return fullName;
    }

    public String getAddress() {
        return address;
    }

    public String getPhone() {
        return phone;
    }
}
