-- The backfill transaction has committed, so there are no retained row locks
-- to invert against concurrent API writers when taking the table DDL lock.
ALTER TABLE customers
    ALTER COLUMN first_name SET NOT NULL,
    ALTER COLUMN last_name SET NOT NULL,
    ALTER COLUMN name_separator SET NOT NULL,
    VALIDATE CONSTRAINT customers_split_name_length,
    VALIDATE CONSTRAINT customers_name_separator;
