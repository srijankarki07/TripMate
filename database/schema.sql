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
    user_id           BIGSERIAL    PRIMARY KEY,
    full_name         VARCHAR(100) NOT NULL,
    email             VARCHAR(150) NOT NULL,
    password_hash     VARCHAR(255) NOT NULL,
    phone             VARCHAR(20),
    email_verified_at TIMESTAMP,
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_users_email UNIQUE (email)
);

COMMENT ON TABLE  users                   IS 'Registered travellers.';
COMMENT ON COLUMN users.email             IS 'Login identifier; unique and case-sensitive as stored.';
COMMENT ON COLUMN users.password_hash     IS 'PBKDF2-SHA256 digest, format: pbkdf2_sha256$<iterations>$<salt-b64>$<hash-b64>. Never a plaintext or unsalted digest.';
COMMENT ON COLUMN users.email_verified_at IS 'When the address was proved to belong to this person. NULL means the account exists but has not been confirmed, and cannot be used until it is.';


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

    -- The zone the trip's wall-clock times are in, as an IANA id such as
    -- Asia/Kathmandu. Itinerary times are stored as a date and two local times
    -- with no zone of their own, which is the right thing to store and is not
    -- enough to place them on a timeline: 07:00 is a different moment in
    -- Kathmandu than in London.
    --
    -- It was added when the calendar feed made the difference visible. A
    -- calendar file may carry a time as a bare wall clock, and Google's reader
    -- takes that to mean UTC, so a 07:00 trek out of Lukla appeared at 12:45 in
    -- a Kathmandu calendar, which is the fifth of a day the zone is worth.
    --
    -- Nullable, and read as the server's own zone when it is unset. That is
    -- right for a trip planned where the server is and wrong for one planned
    -- somewhere else, which is why new trips are given the server's zone
    -- explicitly rather than left to fall through to this.
    timezone    VARCHAR(64),

    -- Where the trip is, for the map.
    --
    -- Nullable, and null is the normal state for a trip that has never been
    -- placed: the destination is free text entered by a person, and nothing in
    -- this application turns text into a coordinate. The reader puts a trip on
    -- the map by clicking where it was, which is both accurate and free of any
    -- dependency on a geocoding service being reachable.
    --
    -- NUMERIC(9,6) rather than a float: six decimal places is about ten
    -- centimetres, which is far more than a trip needs, and it is exact, so two
    -- readers comparing coordinates are comparing the same numbers.
    latitude    NUMERIC(9,6),
    longitude   NUMERIC(9,6),

    -- The boundary of the place, as GeoJSON text, from the same lookup that
    -- names it, and used only to draw it. The map fills the place rather than
    -- pinning a dot in it.
    --
    -- Text rather than a geometry column. Nothing in this application reads a
    -- point of it: it is fetched by the lookup, stored, handed to Leaflet, and
    -- drawn. PostGIS would be a second dependency and a second thing to install
    -- for a column whose only reader is a JavaScript map library. Simplified to
    -- a few hundred points by the lookup, so it is kilobytes rather than the
    -- hundreds of kilobytes an unsimplified district runs to.
    area_geojson TEXT,

    CONSTRAINT fk_trips_created_by FOREIGN KEY (created_by)
        REFERENCES users (user_id) ON DELETE SET NULL,

    CONSTRAINT ck_trips_status CHECK (
        status IN ('PLANNING', 'ONGOING', 'COMPLETED', 'CANCELLED')
    ),

    -- A trip cannot end before it starts. Either date may be unknown, but both
    -- are known they must be ordered.
    CONSTRAINT ck_trips_date_order CHECK (
        start_date IS NULL OR end_date IS NULL OR end_date >= start_date
    ),

    -- A placed trip is placed on both axes or neither. Half a coordinate is not
    -- a place, and the map would have to guess which half was meant.
    CONSTRAINT ck_trips_coordinates CHECK (
        (latitude IS NULL AND longitude IS NULL)
        OR (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180)
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

    -- How many times this item has been edited, and when it last was.
    --
    -- Both exist for the calendar feed. iCalendar identifies one event by its
    -- UID and decides whether a newer copy of it has arrived by comparing
    -- SEQUENCE, which has to increase on every change. The item's id gives the
    -- UID and never changes; the revision gives the SEQUENCE, and updated_at
    -- gives the LAST-MODIFIED and DTSTAMP that go with it.
    --
    -- They are columns rather than something derived because nothing else in
    -- the row records that an edit happened. created_at is set once and stays
    -- put, so without these a calendar that had already read the feed would
    -- have no way to tell an edited item from one it had seen before.
    revision    INTEGER      NOT NULL DEFAULT 0,
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_itinerary_trip FOREIGN KEY (trip_id)
        REFERENCES trips (trip_id) ON DELETE CASCADE,
    CONSTRAINT fk_itinerary_created_by FOREIGN KEY (created_by)
        REFERENCES users (user_id) ON DELETE SET NULL,

    CONSTRAINT ck_itinerary_time_order CHECK (
        start_time IS NULL OR end_time IS NULL OR end_time >= start_time
    )
);

COMMENT ON TABLE  itinerary_items          IS 'One scheduled activity within a trip.';
COMMENT ON COLUMN itinerary_items.revision IS
    'Edit count, written as the iCalendar SEQUENCE of the event for this item. Never decreases.';



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

-- Login and password reset look a user up without regard to case, written as
-- LOWER(email) = :email. A plain btree on email cannot serve that predicate, so
-- both lookups were sequential scans of the whole users table, and one of them
-- is the forgot-password endpoint that any anonymous caller can reach. An index
-- on the same expression is what turns it into an index scan.
--
-- It sits beside uq_users_email rather than replacing it. The constraint still
-- enforces uniqueness of the stored value, and the direct `email = :email`
-- lookup elsewhere still uses it.
--
-- On a database that already exists this has to be added by hand, because the
-- schema is loaded rather than migrated:
--
--   CREATE INDEX idx_users_email_lower ON users (LOWER(email));
CREATE INDEX idx_users_email_lower ON users (LOWER(email));

-- The dashboard's "you have paid" figure totals one user's expenses as payer,
-- with no trip in the predicate: WHERE paid_by = :id. idx_expenses_trip_payer
-- cannot serve that, because its leading column is trip_id, so the query read
-- every row of expenses to produce a single number.
--
-- As above, on a database that already exists:
--
--   CREATE INDEX idx_expenses_payer ON expenses (paid_by);
CREATE INDEX idx_expenses_payer ON expenses (paid_by);

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
-- email_verifications
-- ----------------------------------------------------------------------------
-- One row per 6-digit code sent to confirm an address. Rows for codes that have
-- been used or have run out are kept, so the record shows how often an account
-- was asked to confirm and how many tries it took.
--
-- code_hash is a PBKDF2-SHA256 digest, not a SHA-256 one, and that is the
-- opposite of the choice made for password_resets above. The two look alike and
-- are not: a reset token is 256 random bits, so there is nothing to guess and a
-- fast hash is enough to make the stored form useless. A six-digit code has a
-- million possible values, which a fast hash leaves brute-forceable from a copy
-- of this table in under an hour. The slow hash costs about a tenth of a second
-- per guess, which the application pays once per verification attempt and an
-- attacker pays a million times.
--
-- attempts counts the wrong guesses made against this code, and is the other
-- half of that defence: a code is spent after five of them, so the online
-- search space is five values rather than a million.

CREATE TABLE email_verifications (
    verification_id BIGSERIAL    PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    code_hash       VARCHAR(255) NOT NULL,
    requested_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMP    NOT NULL,
    consumed_at     TIMESTAMP,
    attempts        INT          NOT NULL DEFAULT 0,
    requested_ip    VARCHAR(45),
    CONSTRAINT fk_email_verifications_user FOREIGN KEY (user_id)
        REFERENCES users (user_id) ON DELETE CASCADE,
    CONSTRAINT ck_email_verifications_attempts CHECK (attempts >= 0)
);

-- No unique index on code_hash, unlike password_resets.token_hash. The code is
-- looked up by the account it belongs to and checked against that one row, so
-- two accounts holding the same six digits is ordinary rather than a conflict.
-- Cleaning up is by age, as with a reset.
CREATE INDEX idx_email_verifications_user ON email_verifications (user_id);
CREATE INDEX idx_email_verifications_expiry ON email_verifications (expires_at);


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


-- ----------------------------------------------------------------------------
-- trip_calendar_feeds
-- ----------------------------------------------------------------------------
-- The address a calendar application subscribes to, so that a trip's plan
-- keeps appearing in it without anybody exporting a file again. One row per
-- trip, created when somebody asks for the address and deleted when the trip
-- is.
--
-- The token is what authorises the read. Google's servers fetch the address on
-- their own schedule and have no session and no cookie, so the URL cannot rely
-- on either, and a secret in the path is the only thing left. It is 32 bytes
-- from SecureRandom, which is not guessable, and it is unique across the table.
--
-- Stored in the clear, which for a credential of this kind is a decision rather
-- than an oversight. A token that has to be shown to its owner again cannot be
-- hashed, and this one does: people lose the address, and the alternative is
-- that the only way to get it back is to rotate it and re-add the calendar in
-- Google. What hashing would buy here is protection against a leak of this
-- table, and that is worth nothing, because the itinerary the token guards sits
-- in the same database two tables away. The control that does matter is
-- rotation, and the feed can be reset to a new token at any time.
CREATE TABLE trip_calendar_feeds (
    trip_id    BIGINT      PRIMARY KEY,
    token      VARCHAR(64) NOT NULL,
    -- Whoever last issued this address, which is not always whoever first
    -- asked for one: replacing it records the administrator who did that, so
    -- a subscriber whose calendar stopped updating can see who to ask.
    created_by BIGINT,
    created_at TIMESTAMP   NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_calendar_feeds_trip FOREIGN KEY (trip_id)
        REFERENCES trips (trip_id) ON DELETE CASCADE,
    CONSTRAINT fk_calendar_feeds_created_by FOREIGN KEY (created_by)
        REFERENCES users (user_id) ON DELETE SET NULL,

    CONSTRAINT uq_calendar_feeds_token UNIQUE (token)
);

COMMENT ON TABLE trip_calendar_feeds IS
    'The subscribable address of one trip''s calendar feed, if one has been asked for.';
COMMENT ON COLUMN trip_calendar_feeds.token IS
    '32 random bytes, base64url without padding. Authorises reading one trip''s plan without a session.';

-- No index on token beyond the unique constraint above. The feed is looked up
-- by its token on every poll, and a unique constraint is already a btree index
-- on that column, so a second one would be maintained on every write and read
-- by nothing.


-- ----------------------------------------------------------------------------
-- google_calendar_links
-- ----------------------------------------------------------------------------
-- One row per user who has connected their Google Calendar, so that TripMate can
-- write their trips into it instead of asking them to paste a feed address into
-- another product.
--
-- The refresh token is a credential, and a far more powerful one than the feed
-- token above: it grants access to the reader's whole calendar, not to one
-- trip's itinerary. The two are therefore stored differently, and the difference
-- is the point.
--
-- The feed token is kept in the clear because hashing it would buy nothing. The
-- itinerary it guards is in the same database two tables away, so anyone who can
-- read one can read the other, and what actually matters is being able to revoke
-- it. That argument does not hold here at all. A refresh token grants access to
-- something this database does not contain and cannot leak, so a dump of these
-- tables hands over every connected calendar. It is therefore encrypted with a
-- key that lives in the container's configuration and not in the database, by
-- com.tripmate.security.TokenCipher.
--
-- A refresh token also has to be stored recoverably, unlike a password: the
-- application has to present it to Google. Hashing is not available and
-- encryption is the only option that keeps it out of a database dump.
CREATE TABLE google_calendar_links (
    user_id       BIGINT       PRIMARY KEY,
    -- AES-GCM, base64. See TokenCipher for the format.
    refresh_token VARCHAR(1024) NOT NULL,
    -- Which calendar to write into. "primary" is the reader's own.
    calendar_id   VARCHAR(255) NOT NULL DEFAULT 'primary',
    -- The Google account it was granted by, so a reader can see which one they
    -- connected rather than guessing.
    google_email  VARCHAR(255),
    connected_at  TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_google_links_user FOREIGN KEY (user_id)
        REFERENCES users (user_id) ON DELETE CASCADE
);

COMMENT ON TABLE google_calendar_links IS
    'A reader''s Google Calendar connection, with the refresh token encrypted at rest.';


-- ----------------------------------------------------------------------------
-- google_calendar_events
-- ----------------------------------------------------------------------------
-- Which Google event each itinerary item became, per connected reader. Without
-- it there is no way to update or remove an event that was written earlier: the
-- only handle Google gives back is the event's own id, and nothing else in this
-- schema knows it.
--
-- item_id is nullable and its foreign key is ON DELETE SET NULL, which looks
-- like the wrong choice and is the whole point. A cascade would delete this row
-- the moment the item went, taking with it the only record of an event that is
-- still sitting in somebody's calendar. The row has to outlive the item for
-- exactly one sync, so that the sync can notice the item is gone and remove the
-- event. A settled sync deletes the row itself.
--
-- The primary key is a surrogate rather than (user_id, item_id), because a
-- column in a primary key cannot be null and this one has to be.
CREATE TABLE google_calendar_events (
    link_id         BIGSERIAL     PRIMARY KEY,
    user_id         BIGINT        NOT NULL,
    item_id         BIGINT,
    calendar_id     VARCHAR(255)  NOT NULL DEFAULT 'primary',
    google_event_id VARCHAR(1024) NOT NULL,
    updated_at      TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_google_events_user FOREIGN KEY (user_id)
        REFERENCES users (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_google_events_item FOREIGN KEY (item_id)
        REFERENCES itinerary_items (item_id) ON DELETE SET NULL,

    -- One event per item per reader. A reader with two connections to the same
    -- calendar would be the bug this catches, and a second row would mean an
    -- item quietly written twice.
    CONSTRAINT uq_google_events UNIQUE (user_id, item_id)
);

COMMENT ON TABLE google_calendar_events IS
    'The Google event each itinerary item became, per connected reader. Kept after the item is deleted so the event can be removed.';

-- Every sync reads one reader's rows, and every sync that follows a deletion
-- finds the rows whose item has gone in the same read. A partial index for the
-- second would be a second index maintained on every write to answer a question
-- this one already answers: there are only ever a handful of orphans, and only
-- until the next sync.
CREATE INDEX idx_google_events_user ON google_calendar_events (user_id);


-- ----------------------------------------------------------------------------
-- past_trips
-- ----------------------------------------------------------------------------
-- A trip somebody took that was never planned here. The application is for
-- planning a trip forwards; the map is about everywhere a person has been, and
-- most of those places were not planned with this tool and never will be.
--
-- Deliberately a table of its own rather than a row in `trips` with a flag. A
-- trip in this application has members, an itinerary, expenses and balances,
-- and every query about trips assumes those exist. A past trip has none of
-- them: it is a place, roughly when, and a note. Folding the two into one table
-- would mean every one of those queries carrying a condition to exclude the
-- rows that are not trips, and the first one anybody forgot would be a
-- balance sheet counting a holiday from 2019.
--
-- Kept apart on the map too, drawn differently and listed separately, so that
-- what was planned here and what was merely remembered never look like the same
-- kind of thing.
CREATE TABLE past_trips (
    past_trip_id BIGSERIAL    PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    place_name   VARCHAR(150) NOT NULL,
    country      VARCHAR(100),
    -- Only ever a date. A trip somebody took years ago is remembered as a
    -- summer or a year, so the day is optional even when the trip is not.
    visited_on   DATE,
    note         TEXT,
    latitude     NUMERIC(9,6) NOT NULL,
    longitude    NUMERIC(9,6) NOT NULL,
    area_geojson TEXT,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_past_trips_user FOREIGN KEY (user_id)
        REFERENCES users (user_id) ON DELETE CASCADE,

    CONSTRAINT ck_past_trips_latitude  CHECK (latitude  BETWEEN -90  AND 90),
    CONSTRAINT ck_past_trips_longitude CHECK (longitude BETWEEN -180 AND 180)
);

COMMENT ON TABLE past_trips IS
    'A trip somebody took that was never planned in this application. Shown on the map, kept apart from trips.';

-- Read one reader's history, newest first.
CREATE INDEX idx_past_trips_user ON past_trips (user_id, visited_on DESC);
