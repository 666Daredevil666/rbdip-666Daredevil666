UPDATE customers
SET (first_name, last_name, name_separator) = (
    SELECT n.first_name, n.last_name, n.name_separator FROM split_customer_name(customers.full_name) n
)
WHERE first_name IS NULL OR last_name IS NULL OR name_separator IS NULL;

-- Commit row updates before any exclusive table/view DDL. An API writer may
-- hold a compatible table lock while waiting for one of these updated rows.
