CREATE TABLE inventory.inventory_items (
    serial_number varchar(64) PRIMARY KEY,
    type varchar(100) NOT NULL,
    name varchar(200) NOT NULL,
    status varchar(32) NOT NULL,
    CONSTRAINT chk_inventory_serial_number
        CHECK (serial_number ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    CONSTRAINT chk_inventory_type
        CHECK (
            type <> ''
            AND type !~ '^[[:space:]]'
            AND type !~ '[[:space:]]$'
        ),
    CONSTRAINT chk_inventory_name
        CHECK (
            name <> ''
            AND name !~ '^[[:space:]]'
            AND name !~ '[[:space:]]$'
        ),
    CONSTRAINT chk_inventory_status
        CHECK (status IN (
            'AVAILABLE',
            'RESERVED',
            'RENTED',
            'INSPECTION_REQUIRED',
            'UNDER_MAINTENANCE',
            'RETIRED'
        ))
);

CREATE INDEX idx_inventory_items_status
    ON inventory.inventory_items (status);

CREATE INDEX idx_inventory_items_type_lower
    ON inventory.inventory_items (lower(type));

