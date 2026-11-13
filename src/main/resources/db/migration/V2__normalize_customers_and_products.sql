CREATE TABLE customers (
    id BIGSERIAL PRIMARY KEY,
    full_name VARCHAR(255) NOT NULL,
    address VARCHAR(500),
    phone VARCHAR(50),
    contact_key BYTEA NOT NULL,
    CONSTRAINT customers_contact_key UNIQUE (contact_key)
);

-- A fixed-size key fits long multibyte contact values. Length-prefixed UTF-8
-- bytes distinguish field boundaries and NULL from empty strings. A trigger
-- keeps the key consistent for application writes, backfill and direct SQL.
CREATE FUNCTION set_customer_contact_key() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.contact_key := sha256(
        int4send(octet_length(convert_to(NEW.full_name, 'UTF8'))) || convert_to(NEW.full_name, 'UTF8')
        || CASE WHEN NEW.address IS NULL THEN int4send(-1)
                ELSE int4send(octet_length(convert_to(NEW.address, 'UTF8')))
                     || convert_to(NEW.address, 'UTF8') END
        || CASE WHEN NEW.phone IS NULL THEN int4send(-1)
                ELSE int4send(octet_length(convert_to(NEW.phone, 'UTF8')))
                     || convert_to(NEW.phone, 'UTF8') END
    );
    RETURN NEW;
END;
$$;

CREATE TRIGGER customers_contact_key_trigger
BEFORE INSERT OR UPDATE ON customers
FOR EACH ROW EXECUTE FUNCTION set_customer_contact_key();

INSERT INTO customers (full_name, address, phone)
SELECT DISTINCT customer_full_name, customer_address, customer_phone
FROM orders;

ALTER TABLE orders ADD COLUMN customer_id BIGINT REFERENCES customers(id);

UPDATE orders o
SET customer_id = c.id
FROM customers c
WHERE c.full_name = o.customer_full_name
  AND c.address IS NOT DISTINCT FROM o.customer_address
  AND c.phone IS NOT DISTINCT FROM o.customer_phone;

ALTER TABLE orders ALTER COLUMN customer_id SET NOT NULL;
CREATE INDEX orders_customer_id_idx ON orders(customer_id);

-- V1 did not retain product IDs. Reconstruct absent historical name/price
-- pairs before linking items so a changed/deleted catalog entry loses no data.
INSERT INTO products (name, price)
SELECT DISTINCT i.product_name, i.product_price
FROM order_items i
WHERE NOT EXISTS (
    SELECT 1 FROM products p
    WHERE p.name = i.product_name AND p.price = i.product_price
);

ALTER TABLE order_items ADD COLUMN product_id BIGINT REFERENCES products(id);

-- Identical catalog entries are indistinguishable in V1; choose one
-- deterministically without duplicating or removing any order items.
UPDATE order_items i
SET product_id = (
    SELECT min(p.id) FROM products p
    WHERE p.name = i.product_name AND p.price = i.product_price
);

ALTER TABLE order_items ALTER COLUMN product_id SET NOT NULL;
CREATE INDEX order_items_product_id_idx ON order_items(product_id);
CREATE INDEX order_items_order_id_idx ON order_items(order_id);

ALTER TABLE orders
    DROP COLUMN customer_full_name,
    DROP COLUMN customer_address,
    DROP COLUMN customer_phone;

ALTER TABLE order_items
    DROP COLUMN product_name,
    DROP COLUMN product_price;
