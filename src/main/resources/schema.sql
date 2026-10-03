CREATE TABLE IF NOT EXISTS shows (
    id             uuid PRIMARY KEY,
    name           text NOT NULL,
    price_paise    integer NOT NULL CHECK (price_paise >= 0),
    per_user_limit integer NOT NULL DEFAULT 4 CHECK (per_user_limit > 0),
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS reservations (
    id              uuid PRIMARY KEY,
    show_id         uuid NOT NULL REFERENCES shows(id),
    user_id         text NOT NULL,
    idempotency_key text NOT NULL,
    request_hash    text NOT NULL,
    seats           text[] NOT NULL,
    amount_paise    bigint NOT NULL,
    status          text NOT NULL CHECK (status IN ('confirmed', 'cancelled')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    cancelled_at    timestamptz,
    -- backstop: one key == one reservation per user, even if the app logic had a bug
    UNIQUE (user_id, idempotency_key)
);

CREATE TABLE IF NOT EXISTS seats (
    show_id        uuid NOT NULL REFERENCES shows(id),
    label          text NOT NULL,
    status         text NOT NULL DEFAULT 'available'
                   CHECK (status IN ('available', 'held', 'confirmed')),
    user_id        text,
    reservation_id uuid REFERENCES reservations(id),
    PRIMARY KEY (show_id, label),
    CHECK ((status = 'available') = (user_id IS NULL))
);

CREATE INDEX IF NOT EXISTS seats_user_idx ON seats (show_id, user_id) WHERE user_id IS NOT NULL;
