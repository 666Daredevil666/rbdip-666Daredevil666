ALTER TABLE customers
    ADD COLUMN first_name VARCHAR(255),
    ADD COLUMN last_name VARCHAR(255),
    ADD COLUMN name_separator VARCHAR(1);

-- Preserve the original spelling, including a trailing separator or repeated
-- spaces. An empty last_name is valid for a single-token name.
CREATE FUNCTION split_customer_name(p_full_name TEXT,
    OUT first_name TEXT, OUT last_name TEXT, OUT name_separator TEXT)
RETURNS RECORD LANGUAGE SQL IMMUTABLE STRICT AS $$
    SELECT split_part(p_full_name, ' ', 1),
           CASE WHEN strpos(p_full_name, ' ') > 0
                THEN substr(p_full_name, strpos(p_full_name, ' ') + 1) ELSE '' END,
           CASE WHEN strpos(p_full_name, ' ') > 0 THEN ' ' ELSE '' END;
$$;

CREATE FUNCTION customer_contact_key(p_name TEXT, p_address TEXT, p_phone TEXT)
RETURNS BYTEA LANGUAGE SQL STABLE AS $$
    SELECT sha256(
        int4send(octet_length(convert_to(p_name, 'UTF8'))) || convert_to(p_name, 'UTF8')
        || CASE WHEN p_address IS NULL THEN int4send(-1)
                ELSE int4send(octet_length(convert_to(p_address, 'UTF8'))) || convert_to(p_address, 'UTF8') END
        || CASE WHEN p_phone IS NULL THEN int4send(-1)
                ELSE int4send(octet_length(convert_to(p_phone, 'UTF8'))) || convert_to(p_phone, 'UTF8') END
    );
$$;

-- The existing trigger now accepts both legacy and new writers. Updates of
-- only one representation propagate to the other within the same row write.
CREATE OR REPLACE FUNCTION set_customer_contact_key() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    parts RECORD;
    legacy_changed BOOLEAN;
BEGIN
    legacy_changed := (TG_OP = 'INSERT' AND NEW.full_name IS NOT NULL)
        OR (TG_OP = 'UPDATE' AND NEW.full_name IS DISTINCT FROM OLD.full_name);
    IF legacy_changed THEN
        IF NEW.full_name IS NULL THEN
            RAISE EXCEPTION 'full_name is required' USING ERRCODE = '23502';
        END IF;
        SELECT * INTO parts FROM split_customer_name(NEW.full_name);
        IF TG_OP = 'INSERT' THEN
            IF (NEW.first_name IS NOT NULL AND NEW.first_name IS DISTINCT FROM parts.first_name)
                OR (NEW.last_name IS NOT NULL AND NEW.last_name IS DISTINCT FROM parts.last_name)
                OR (NEW.name_separator IS NOT NULL AND NEW.name_separator IS DISTINCT FROM parts.name_separator) THEN
                RAISE EXCEPTION 'conflicting customer name representations' USING ERRCODE = '23514';
            END IF;
        ELSE
            IF (NEW.first_name IS DISTINCT FROM OLD.first_name AND NEW.first_name IS DISTINCT FROM parts.first_name)
                OR (NEW.last_name IS DISTINCT FROM OLD.last_name AND NEW.last_name IS DISTINCT FROM parts.last_name)
                OR (NEW.name_separator IS DISTINCT FROM OLD.name_separator
                    AND NEW.name_separator IS DISTINCT FROM parts.name_separator) THEN
                RAISE EXCEPTION 'conflicting customer name representations' USING ERRCODE = '23514';
            END IF;
        END IF;
        NEW.first_name := parts.first_name;
        NEW.last_name := parts.last_name;
        NEW.name_separator := parts.name_separator;
    ELSE
        IF NEW.first_name IS NULL OR NEW.last_name IS NULL OR NEW.name_separator IS NULL THEN
            SELECT * INTO parts FROM split_customer_name(NEW.full_name);
            NEW.first_name := coalesce(NEW.first_name, parts.first_name);
            NEW.last_name := coalesce(NEW.last_name, parts.last_name);
            NEW.name_separator := coalesce(NEW.name_separator, parts.name_separator);
        END IF;
        NEW.full_name := NEW.first_name || NEW.name_separator || NEW.last_name;
    END IF;
    NEW.contact_key := customer_contact_key(NEW.full_name, NEW.address, NEW.phone);
    RETURN NEW;
END;
$$;

ALTER TABLE customers
    ADD CONSTRAINT customers_split_name_length
        CHECK (char_length(first_name || name_separator || last_name) <= 255) NOT VALID,
    ADD CONSTRAINT customers_name_separator CHECK (name_separator IN ('', ' ')) NOT VALID;

-- A stable projection lets one running application read existing rows before
-- backfill and continue reading after full_name is physically removed.
CREATE VIEW customer_read_model AS
SELECT id, coalesce(first_name || name_separator || last_name, full_name)::VARCHAR(255) AS full_name,
       address, phone
FROM customers;

CREATE FUNCTION resolve_customer(p_full_name TEXT, p_address TEXT, p_phone TEXT)
RETURNS BIGINT LANGUAGE SQL AS $$
    INSERT INTO customers(first_name, last_name, name_separator, address, phone)
    SELECT n.first_name, n.last_name, n.name_separator, p_address, p_phone
    FROM split_customer_name(p_full_name) n
    ON CONFLICT ON CONSTRAINT customers_contact_key
    DO UPDATE SET first_name = EXCLUDED.first_name,
                  last_name = EXCLUDED.last_name,
                  name_separator = EXCLUDED.name_separator
    -- Read the conflicting row directly: a concurrent winner need not be
    -- visible to a separate SELECT's snapshot yet. Only untouched legacy rows
    -- need the view fallback; every new write already has all split fields.
    WHERE coalesce(customers.first_name || customers.name_separator || customers.last_name,
                   (SELECT v.full_name FROM customer_read_model v WHERE v.id = customers.id)) = p_full_name
      AND customers.address IS NOT DISTINCT FROM EXCLUDED.address
      AND customers.phone IS NOT DISTINCT FROM EXCLUDED.phone
    RETURNING id;
$$;
