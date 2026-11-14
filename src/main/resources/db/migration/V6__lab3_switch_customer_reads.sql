-- Replace the view in its own transaction: a SELECT can hold a view lock
-- while acquiring a table lock. Do not hold an exclusive table lock here.
CREATE OR REPLACE VIEW customer_read_model AS
SELECT id, (first_name || name_separator || last_name)::VARCHAR(255) AS full_name, address, phone
FROM customers;
