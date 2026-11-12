ALTER TABLE inventory.inventory_status_transition_requests
    ADD COLUMN caller_scope varchar(80) NOT NULL DEFAULT 'legacy:unauthenticated';

ALTER TABLE inventory.inventory_status_transition_requests
    ALTER COLUMN caller_scope DROP DEFAULT,
    ADD CONSTRAINT inventory_status_transition_requests_scope_check
        CHECK (caller_scope = 'legacy:unauthenticated' OR caller_scope ~ '^p1:[0-9a-f]{64}$'),
    DROP CONSTRAINT inventory_status_transition_requests_pkey,
    ADD PRIMARY KEY (caller_scope, idempotency_key);

DROP INDEX inventory.idx_inventory_status_transition_requests_expiry;
CREATE INDEX idx_inventory_status_transition_requests_expiry
    ON inventory.inventory_status_transition_requests (expires_at, caller_scope, idempotency_key);
