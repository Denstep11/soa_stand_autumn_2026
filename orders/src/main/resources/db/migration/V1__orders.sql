CREATE TABLE orders (
 id UUID PRIMARY KEY,
 user_id TEXT NOT NULL,
 amount_cents BIGINT NOT NULL CHECK (amount_cents >= 0),
 items JSONB NOT NULL,
 status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','paid','rejected')),
 phase TEXT NOT NULL DEFAULT 'RESERVE' CHECK (phase IN ('RESERVE','PAY','CANCEL','DONE')),
 attempts INTEGER NOT NULL DEFAULT 0,
 next_attempt TIMESTAMPTZ NOT NULL DEFAULT now(),
 lease_until TIMESTAMPTZ,
 lease_token UUID,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX orders_pending ON orders(next_attempt) WHERE status = 'pending';
CREATE TABLE outbox (
 id UUID PRIMARY KEY,
 order_id UUID NOT NULL UNIQUE REFERENCES orders(id),
 occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 sent BOOLEAN NOT NULL DEFAULT false,
 attempts INTEGER NOT NULL DEFAULT 0,
 next_attempt TIMESTAMPTZ NOT NULL DEFAULT now(),
 lease_until TIMESTAMPTZ,
 lease_token UUID
);
CREATE INDEX outbox_pending ON outbox(next_attempt) WHERE NOT sent;
CREATE TABLE delivery_inbox (id TEXT PRIMARY KEY, received_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE bonuses (user_id TEXT PRIMARY KEY, points BIGINT NOT NULL CHECK(points >= 0));
