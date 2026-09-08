CREATE TABLE inventory.inventory_status_transition_requests (
    idempotency_key uuid PRIMARY KEY,
    fingerprint varchar(64) NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    http_status integer NOT NULL CHECK (http_status IN (204, 404, 409)),
    outcome jsonb NOT NULL CHECK (jsonb_typeof(outcome) = 'object' AND outcome ? 'status' AND jsonb_typeof(outcome->'status') = 'number' AND (outcome->>'status')::integer = http_status),
    recorded_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK (expires_at = recorded_at + INTERVAL '168 hours')
);

CREATE INDEX idx_inventory_status_transition_requests_expiry
    ON inventory.inventory_status_transition_requests (expires_at, idempotency_key);
