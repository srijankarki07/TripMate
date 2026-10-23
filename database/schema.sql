-- ============================================================================
-- TripMate — database schema
-- ============================================================================
--
-- Seven linked tables supporting group trip planning and expense tracking.
-- Exceeds the assignment minimum of three, and gives the two complex queries
-- (ROADMAP §9.4, §9.5) enough entities to navigate.
--
--   users -> trips -> trip_members -> users
--                  -> itinerary_items -> users
--                  -> expenses -> users
--                              -> expense_splits -> users
--                  -> settlements -> users (payer, receiver)
--
-- Run against an EMPTY database. To rebuild from scratch:
--
--   psql -f database/reset.sql      -- drops everything
--   psql -f database/schema.sql     -- creates tables and indexes
--   psql -f database/seed.sql       -- loads sample data
--
-- or simply:  ./database/load.sh
--
-- ----------------------------------------------------------------------------
-- Column types
-- ----------------------------------------------------------------------------
-- Surrogate keys are BIGSERIAL. Money is DECIMAL(12,2), never floating point —
-- binary floats cannot represent 0.10 exactly, and a balance calculation that
-- drifts by a hundredth of a rupee per expense stops reconciling. Dates are
-- DATE and clock times are TIME, kept separate from the TIMESTAMP audit
-- columns so JPQL can order and group by day without truncating.
--
-- ----------------------------------------------------------------------------
-- Referential actions
-- ----------------------------------------------------------------------------
-- Trip-owned rows (members, itinerary, expenses, settlements) CASCADE from
-- trips: they are meaningless without their trip. Rows referencing a user
-- SET NULL where the column is nullable (created_by, paid_by), so removing a
-- user does not erase the trip or the expense — only the attribution. Where
-- the reference is the row's whole purpose (a membership, a split, a
-- settlement party) it CASCADEs.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- users
-- ----------------------------------------------------------------------------
CREATE TABLE users (
    user_id       BIGSERIAL    PRIMARY KEY,
    full_name     VARCHAR(100) NOT NULL,
    email         VARCHAR(150) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    phone         VARCHAR(20),
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_users_email UNIQUE (email)
);

COMMENT ON TABLE  users               IS 'Registered travellers.';
COMMENT ON COLUMN users.email         IS 'Login identifier; unique and case-sensitive as stored.';
COMMENT ON COLUMN users.password_hash IS 'PBKDF2-SHA256 digest, format: pbkdf2_sha256$<iterations>$<salt-b64>$<hash-b64>. Never a plaintext or unsalted digest.';


-- ----------------------------------------------------------------------------
-- trips
-- ----------------------------------------------------------------------------
CREATE TABLE trips (
    trip_id     BIGSERIAL    PRIMARY KEY,
    trip_name   VARCHAR(150) NOT NULL,
    destination VARCHAR(150),
    start_date  DATE,
    end_date    DATE,
    description TEXT,
    status      VARCHAR(30)  NOT NULL,
    created_by  BIGINT,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_trips_created_by FOREIGN KEY (created_by)
        REFERENCES users (user_id) ON DELETE SET NULL,

    CONSTRAINT ck_trips_status CHECK (
        status IN ('PLANNING', 'ONGOING', 'COMPLETED', 'CANCELLED')
    ),

    -- A trip cannot end before it starts. Either date may be unknown, but both
    -- are known they must be ordered.
    CONSTRAINT ck_trips_date_order CHECK (
        start_date IS NULL OR end_date IS NULL OR end_date >= start_date
    )
);

COMMENT ON TABLE  trips        IS 'A planned or completed group trip.';
COMMENT ON COLUMN trips.status IS 'Lifecycle state: PLANNING, ONGOING, COMPLETED or CANCELLED.';


-- ----------------------------------------------------------------------------
-- trip_members
-- ----------------------------------------------------------------------------
-- Role is per-trip, not global: the same user can administer one trip and be
-- an ordinary member of another.
CREATE TABLE trip_members (
    member_id BIGSERIAL   PRIMARY KEY,
    trip_id   BIGINT      NOT NULL,
    user_id   BIGINT      NOT NULL,
    role      VARCHAR(20) NOT NULL,
    joined_at TIMESTAMP   NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_trip_members_trip FOREIGN KEY (trip_id)
        REFERENCES trips (trip_id) ON DELETE CASCADE,
    CONSTRAINT fk_trip_members_user FOREIGN KEY (user_id)
        REFERENCES users (user_id) ON DELETE CASCADE,

    -- A user joins a given trip at most once. This is the database half of the
    -- business rule in ROADMAP §8.3; TripService enforces it too, so the client
    -- gets a readable message rather than a constraint violation.
    CONSTRAINT uq_trip_members UNIQUE (trip_id, user_id),

    CONSTRAINT ck_trip_members_role CHECK (role IN ('ADMIN', 'MEMBER'))
);

COMMENT ON TABLE  trip_members      IS 'Membership of a user in a trip, with a per-trip role.';
COMMENT ON COLUMN trip_members.role IS 'ADMIN may manage members and edit the trip; MEMBER may not.';


-- ----------------------------------------------------------------------------
-- itinerary_items
-- ----------------------------------------------------------------------------
CREATE TABLE itinerary_items (
    item_id     BIGSERIAL    PRIMARY KEY,
    trip_id     BIGINT       NOT NULL,
    title       VARCHAR(200) NOT NULL,
    description TEXT,
    item_date   DATE         NOT NULL,
    start_time  TIME,
    end_time    TIME,
    location    VARCHAR(200),
    created_by  BIGINT,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_itinerary_trip FOREIGN KEY (trip_id)
        REFERENCES trips (trip_id) ON DELETE CASCADE,
    CONSTRAINT fk_itinerary_created_by FOREIGN KEY (created_by)
        REFERENCES users (user_id) ON DELETE SET NULL,

    CONSTRAINT ck_itinerary_time_order CHECK (
        start_time IS NULL OR end_time IS NULL OR end_time >= start_time
    )
);

COMMENT ON TABLE itinerary_items IS 'One scheduled activity within a trip.';


-- ----------------------------------------------------------------------------
-- expenses
-- ----------------------------------------------------------------------------
CREATE TABLE expenses (
    expense_id   BIGSERIAL     PRIMARY KEY,
    trip_id      BIGINT        NOT NULL,
    paid_by      BIGINT,
    description  VARCHAR(255)  NOT NULL,
    amount       DECIMAL(12,2) NOT NULL,
    category     VARCHAR(50),
    expense_date DATE          NOT NULL,
    created_at   TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_expenses_trip FOREIGN KEY (trip_id)
        REFERENCES trips (trip_id) ON DELETE CASCADE,
    CONSTRAINT fk_expenses_paid_by FOREIGN KEY (paid_by)
        REFERENCES users (user_id) ON DELETE SET NULL,

    -- Business rule ROADMAP §8.3: an expense amount must be greater than zero.
    CONSTRAINT ck_expenses_amount_positive CHECK (amount > 0)
);

COMMENT ON TABLE  expenses          IS 'A single cost incurred during a trip, paid by one member.';
COMMENT ON COLUMN expenses.amount   IS 'Total paid, in NPR. Equals the sum of the expense''s splits.';
COMMENT ON COLUMN expenses.category IS 'Free-text grouping used by the category spending report, e.g. TRANSPORT, FOOD, ACCOMMODATION, ACTIVITY, SHOPPING, EQUIPMENT, OTHER.';


-- ----------------------------------------------------------------------------
-- expense_splits
-- ----------------------------------------------------------------------------
-- How much of an expense each participant owes. Kept as its own table rather
-- than a column on expenses because a split is per-participant, not per-expense.
CREATE TABLE expense_splits (
    split_id    BIGSERIAL     PRIMARY KEY,
    expense_id  BIGINT        NOT NULL,
    user_id     BIGINT        NOT NULL,
    amount_owed DECIMAL(12,2) NOT NULL,
    is_settled  BOOLEAN       NOT NULL DEFAULT FALSE,

    CONSTRAINT fk_splits_expense FOREIGN KEY (expense_id)
        REFERENCES expenses (expense_id) ON DELETE CASCADE,
    CONSTRAINT fk_splits_user FOREIGN KEY (user_id)
        REFERENCES users (user_id) ON DELETE CASCADE,

    -- One split per participant per expense. Without this a double-submitted
    -- expense could silently double-charge someone.
    CONSTRAINT uq_expense_splits UNIQUE (expense_id, user_id),

    CONSTRAINT ck_splits_amount_non_negative CHECK (amount_owed >= 0)
);

COMMENT ON TABLE  expense_splits            IS 'Per-participant share of an expense.';
COMMENT ON COLUMN expense_splits.amount_owed IS 'This participant''s share. The splits of an expense must sum to expenses.amount — enforced in ExpenseService, not by a constraint, because the rule spans rows.';


-- ----------------------------------------------------------------------------
-- settlements
-- ----------------------------------------------------------------------------
CREATE TABLE settlements (
    settlement_id BIGSERIAL     PRIMARY KEY,
    trip_id       BIGINT        NOT NULL,
    payer_id      BIGINT        NOT NULL,
    receiver_id   BIGINT        NOT NULL,
    amount        DECIMAL(12,2) NOT NULL,
    note          VARCHAR(255),
    settled_at    TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_settlements_trip FOREIGN KEY (trip_id)
        REFERENCES trips (trip_id) ON DELETE CASCADE,
    CONSTRAINT fk_settlements_payer FOREIGN KEY (payer_id)
        REFERENCES users (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_settlements_receiver FOREIGN KEY (receiver_id)
        REFERENCES users (user_id) ON DELETE CASCADE,

    CONSTRAINT ck_settlements_amount_positive CHECK (amount > 0),

    -- Business rule ROADMAP §8.3: a settlement must reference two different
    -- users. Paying yourself is not a settlement.
    CONSTRAINT ck_settlements_distinct_parties CHECK (payer_id <> receiver_id)
);

COMMENT ON TABLE settlements IS 'A real-world repayment between two members, recorded after the fact.';


-- ============================================================================
-- Indexes
-- ============================================================================
-- PostgreSQL indexes primary keys and UNIQUE constraints automatically, and
-- the leading column of a composite unique index is covered. What follows are
-- the access paths the five required queries actually need.

-- Q3 (trips a user belongs to) and Q5 (both join trip_members on user).
CREATE INDEX idx_trip_members_user ON trip_members (user_id);

-- Q1: itinerary for a trip, ordered by day then start time.
CREATE INDEX idx_itinerary_trip_when ON itinerary_items (trip_id, item_date, start_time);

-- Q2: expenses for a trip, newest first.
CREATE INDEX idx_expenses_trip_when ON expenses (trip_id, expense_date DESC);

-- Q4: the "total paid" subquery filters on trip AND payer together.
CREATE INDEX idx_expenses_trip_payer ON expenses (trip_id, paid_by);

-- Q4 and Q5: the "total owed" subquery starts from splits by user.
CREATE INDEX idx_splits_user ON expense_splits (user_id);

-- Settlements are always read per trip, and per party when reconciling.
CREATE INDEX idx_settlements_trip ON settlements (trip_id);
CREATE INDEX idx_settlements_payer ON settlements (payer_id);
CREATE INDEX idx_settlements_receiver ON settlements (receiver_id);

-- Dashboard filters trips by lifecycle state.
CREATE INDEX idx_trips_status ON trips (status);

-- ============================================================================
-- Password resets
-- ============================================================================
--
-- One row per outstanding reset request. Deliberately holds no plaintext token:
-- token_hash is the SHA-256 of the value that was emailed, so a copy of this
-- table does not let anyone reset anybody's password. The token itself exists
-- only in the message the user received.
--
-- consumed_at marks a token as spent rather than deleting the row, so a
-- replayed link can be recognised as used rather than as unknown. An unknown
-- token and a used one are answered the same way to the user; the difference
-- matters for the log, not the response.

CREATE TABLE password_resets (
    reset_id      BIGSERIAL PRIMARY KEY,
    user_id       BIGINT      NOT NULL,
    token_hash    VARCHAR(64) NOT NULL,
    requested_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    expires_at    TIMESTAMP   NOT NULL,
    consumed_at   TIMESTAMP,
    requested_ip  VARCHAR(45),
    CONSTRAINT uq_password_resets_token UNIQUE (token_hash),
    CONSTRAINT fk_password_resets_user FOREIGN KEY (user_id)
        REFERENCES users (user_id) ON DELETE CASCADE
);

-- Lookups are always by the hash of what was presented, and cleaning up is
-- always by age.
CREATE INDEX idx_password_resets_user ON password_resets (user_id);
CREATE INDEX idx_password_resets_expiry ON password_resets (expires_at);


-- ----------------------------------------------------------------------------
-- trip_summary_emails
-- ----------------------------------------------------------------------------
-- A record of every time somebody asked for the closing summary to be mailed
-- out. Its purpose is answerable from nothing else in the schema: the summary
-- is sent by email and leaves no trace in the application, so a member who did
-- not receive one, or who received two, had no way to find out whether it was
-- ever sent and when.
--
-- Denormalised on purpose in two places. The sender's name is stored rather
-- than joined, because the log has to keep reading correctly after that person
-- leaves the trip or the account; and the recipient count is stored rather
-- than counted, because it records what actually happened at that moment
-- rather than what the membership happens to be now.
--
-- Visible to every member of the trip, not only its administrators. Knowing
-- whether the summary has gone out is not privileged information: it is about
-- a message those members were themselves sent.

-- kind says which half of the trip the send was about. The itinerary and the
-- balances are separate sends, because a trip usually has an answer to only one
-- of them: a trip being planned has a plan and nothing spent, and a finished one
-- has the reverse.
--
-- failed_count and status are what make the entry honest. The recipient count
-- alone reads as "this arrived", so a send refused by a mail server that was
-- down produced exactly the same line as one that was delivered, and the
-- administrator asked why nobody received it was told by the application that
-- everybody had.
CREATE TABLE trip_summary_emails (
    summary_id      BIGSERIAL    PRIMARY KEY,
    trip_id         BIGINT       NOT NULL,
    sent_by         BIGINT,
    sent_by_name    VARCHAR(120) NOT NULL,
    kind            VARCHAR(16)  NOT NULL DEFAULT 'BALANCES',
    recipient_count INTEGER      NOT NULL,
    -- Who the message was actually accepted for, by name. Denormalised for the
    -- same reason as sent_by_name: the log has to keep reading after somebody
    -- leaves the trip, and "who did this reach" is the question it exists to
    -- answer. Truncated to the column if a trip ever grows past it.
    recipient_names VARCHAR(1000) NOT NULL DEFAULT '',
    failed_count    INTEGER      NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'SENT',
    sent_at         TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_trip_summary_emails_kind
        CHECK (kind IN ('ITINERARY', 'BALANCES')),
    CONSTRAINT ck_trip_summary_emails_status
        CHECK (status IN ('SENT', 'PARTIAL', 'FAILED', 'NOT_CONFIGURED')),

    -- Nullable rather than cascading: the log entry outlives the account that
    -- triggered it, because the send still happened and the other members may
    -- still be looking for it.
    CONSTRAINT fk_trip_summary_emails_user FOREIGN KEY (sent_by)
        REFERENCES users (user_id) ON DELETE SET NULL,

    CONSTRAINT fk_trip_summary_emails_trip FOREIGN KEY (trip_id)
        REFERENCES trips (trip_id) ON DELETE CASCADE
);

COMMENT ON TABLE trip_summary_emails IS
    'One row per time the trip summary was emailed, so members can see whether and when it went out.';

-- Always read newest first, for one trip.
CREATE INDEX idx_trip_summary_emails_trip ON trip_summary_emails (trip_id, sent_at DESC);
