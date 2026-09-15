CREATE TABLE inventory.inventory_reservation_cancellation_inbox (
    event_id uuid PRIMARY KEY,
    fingerprint varchar(64) NOT NULL,
    serial_number varchar(64) NOT NULL,
    occurred_at timestamptz NOT NULL,
    outcome varchar(32) NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT chk_inventory_reservation_cancellation_inbox_fingerprint
        CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_inventory_reservation_cancellation_inbox_serial
        CHECK (serial_number ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    CONSTRAINT chk_inventory_reservation_cancellation_inbox_outcome
        CHECK (outcome IN ('RELEASED', 'ALREADY_AVAILABLE'))
);
