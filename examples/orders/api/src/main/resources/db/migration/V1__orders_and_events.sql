CREATE TABLE orders (
    id UUID PRIMARY KEY,
    item VARCHAR(120) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('pending','shipped','cancelled')),
    version INTEGER NOT NULL CHECK (version > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE order_outbox (
    event_id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders(id),
    order_version INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL,
    sent BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (order_id, order_version)
);
CREATE INDEX ix_order_outbox_pending ON order_outbox(sent, order_id, order_version);
CREATE TABLE order_event_receipts (
    event_id UUID PRIMARY KEY,
    consumed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
