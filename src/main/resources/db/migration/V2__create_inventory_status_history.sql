CREATE TABLE inventory.inventory_status_history (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    serial_number varchar(64) NOT NULL,
    status_from varchar(32) NOT NULL,
    status_to varchar(32) NOT NULL,
    transitioned_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_inventory_status_history_serial_number
        CHECK (serial_number ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    CONSTRAINT chk_inventory_status_history_status_from
        CHECK (status_from IN (
            'AVAILABLE',
            'RESERVED',
            'RENTED',
            'INSPECTION_REQUIRED',
            'UNDER_MAINTENANCE',
            'RETIRED'
        )),
    CONSTRAINT chk_inventory_status_history_status_to
        CHECK (status_to IN (
            'AVAILABLE',
            'RESERVED',
            'RENTED',
            'INSPECTION_REQUIRED',
            'UNDER_MAINTENANCE',
            'RETIRED'
        )),
    CONSTRAINT chk_inventory_status_history_changed
        CHECK (status_from <> status_to)
);

CREATE INDEX idx_inventory_status_history_transitioned_at_id
    ON inventory.inventory_status_history (transitioned_at DESC, id DESC);
