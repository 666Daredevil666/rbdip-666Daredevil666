-- Run only after expand/backfill and deployment of the compatible application.
-- Neither the view nor resolve_customer depends on the physical full_name now.
CREATE OR REPLACE FUNCTION set_customer_contact_key() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    NEW.contact_key := customer_contact_key(
        NEW.first_name || NEW.name_separator || NEW.last_name, NEW.address, NEW.phone);
    RETURN NEW;
END;
$$;

ALTER TABLE customers DROP COLUMN full_name;
