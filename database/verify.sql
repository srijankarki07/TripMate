-- ============================================================================
-- TripMate — data integrity checks
-- ============================================================================
--
-- Asserts the invariants the application relies on. Every check raises an
-- exception on failure, so running with ON_ERROR_STOP makes this usable as a
-- pass/fail gate:
--
--   psql -v ON_ERROR_STOP=1 -f database/verify.sql
--
-- ./database/load.sh runs this automatically after loading.
--
-- These are cross-row rules that a CHECK constraint cannot express — the splits
-- of one expense must sum to that expense's amount, which spans rows within a
-- group. They are enforced by ExpenseService at runtime (ROADMAP §8.3), and
-- re-asserted here so a bad seed or a bad migration is caught immediately
-- rather than showing up as a wrong balance in the UI.
-- ============================================================================

\set ON_ERROR_STOP on

DO $$
DECLARE
    n bigint;
BEGIN
    -- ---------------------------------------------------------------- counts
    SELECT count(*) INTO n FROM users;
    IF n < 5 THEN RAISE EXCEPTION 'expected at least 5 users, found %', n; END IF;

    SELECT count(*) INTO n FROM trips;
    IF n < 3 THEN RAISE EXCEPTION 'expected at least 3 trips, found %', n; END IF;

    SELECT count(*) INTO n FROM expenses;
    IF n = 0 THEN RAISE EXCEPTION 'no expenses loaded'; END IF;

    SELECT count(*) INTO n FROM expense_splits;
    IF n = 0 THEN RAISE EXCEPTION 'no expense splits loaded'; END IF;

    RAISE NOTICE 'row counts present';

    -- ------------------------------------------- splits reconcile to amounts
    -- The headline business rule: for every expense, SUM(amount_owed) = amount.
    SELECT count(*) INTO n
    FROM (
        SELECT e.expense_id
        FROM expenses e
        LEFT JOIN expense_splits s ON s.expense_id = e.expense_id
        GROUP BY e.expense_id, e.amount
        HAVING COALESCE(SUM(s.amount_owed), 0) <> e.amount
    ) AS mismatched;
    IF n > 0 THEN
        RAISE EXCEPTION 'splits do not reconcile with expense amount for % expense(s)', n;
    END IF;

    RAISE NOTICE 'splits reconcile with expense amounts';

    -- ------------------------------------------------- every expense is split
    SELECT count(*) INTO n
    FROM expenses e
    WHERE NOT EXISTS (SELECT 1 FROM expense_splits s WHERE s.expense_id = e.expense_id);
    IF n > 0 THEN
        RAISE EXCEPTION '% expense(s) have no splits — nobody owes anything for them', n;
    END IF;

    RAISE NOTICE 'every expense has at least one split';

    -- ------------------------------------ participants belong to the trip
    -- Catches a split or a payer pointing at someone outside the trip, which
    -- would make the balance query report a debt to a non-member.
    SELECT count(*) INTO n
    FROM expense_splits s
    JOIN expenses e ON e.expense_id = s.expense_id
    WHERE NOT EXISTS (
        SELECT 1 FROM trip_members tm
        WHERE tm.trip_id = e.trip_id AND tm.user_id = s.user_id
    );
    IF n > 0 THEN
        RAISE EXCEPTION '% split(s) belong to a user who is not a member of the trip', n;
    END IF;

    SELECT count(*) INTO n
    FROM expenses e
    WHERE e.paid_by IS NOT NULL
      AND NOT EXISTS (
        SELECT 1 FROM trip_members tm
        WHERE tm.trip_id = e.trip_id AND tm.user_id = e.paid_by
    );
    IF n > 0 THEN
        RAISE EXCEPTION '% expense(s) were paid by a user who is not a member of the trip', n;
    END IF;

    RAISE NOTICE 'all expense participants are trip members';

    -- --------------------------------------------- settlement sanity
    SELECT count(*) INTO n
    FROM settlements s
    WHERE s.payer_id = s.receiver_id;
    IF n > 0 THEN
        RAISE EXCEPTION '% settlement(s) have the same payer and receiver', n;
    END IF;

    SELECT count(*) INTO n
    FROM settlements s
    WHERE NOT EXISTS (SELECT 1 FROM trip_members tm WHERE tm.trip_id = s.trip_id AND tm.user_id = s.payer_id)
       OR NOT EXISTS (SELECT 1 FROM trip_members tm WHERE tm.trip_id = s.trip_id AND tm.user_id = s.receiver_id);
    IF n > 0 THEN
        RAISE EXCEPTION '% settlement(s) involve a user who is not a member of the trip', n;
    END IF;

    RAISE NOTICE 'settlements reference two distinct trip members';

    -- --------------------------------------------------- trip date ordering
    SELECT count(*) INTO n
    FROM trips
    WHERE start_date IS NOT NULL AND end_date IS NOT NULL AND end_date < start_date;
    IF n > 0 THEN
        RAISE EXCEPTION '% trip(s) end before they start', n;
    END IF;

    RAISE NOTICE 'trip date ranges are ordered';

    -- ------------------------------------------- sequences ahead of the data
    -- The seed inserts explicit primary keys, which do not advance the
    -- sequences. If setval() were skipped, the next application insert would
    -- collide on id 1. Comparing the sequence's last value against MAX(id)
    -- catches that. pg_get_serial_sequence resolves the sequence name, so this
    -- keeps working if a table or column is ever renamed.
    SELECT count(*) INTO n
    FROM (
        SELECT 'users' AS t,
               (SELECT MAX(user_id) FROM users) AS mx,
               pg_sequence_last_value(pg_get_serial_sequence('users', 'user_id')::regclass) AS seq
        UNION ALL
        SELECT 'trips',
               (SELECT MAX(trip_id) FROM trips),
               pg_sequence_last_value(pg_get_serial_sequence('trips', 'trip_id')::regclass)
        UNION ALL
        SELECT 'trip_members',
               (SELECT MAX(member_id) FROM trip_members),
               pg_sequence_last_value(pg_get_serial_sequence('trip_members', 'member_id')::regclass)
        UNION ALL
        SELECT 'itinerary_items',
               (SELECT MAX(item_id) FROM itinerary_items),
               pg_sequence_last_value(pg_get_serial_sequence('itinerary_items', 'item_id')::regclass)
        UNION ALL
        SELECT 'expenses',
               (SELECT MAX(expense_id) FROM expenses),
               pg_sequence_last_value(pg_get_serial_sequence('expenses', 'expense_id')::regclass)
        UNION ALL
        SELECT 'expense_splits',
               (SELECT MAX(split_id) FROM expense_splits),
               pg_sequence_last_value(pg_get_serial_sequence('expense_splits', 'split_id')::regclass)
        UNION ALL
        SELECT 'settlements',
               (SELECT MAX(settlement_id) FROM settlements),
               pg_sequence_last_value(pg_get_serial_sequence('settlements', 'settlement_id')::regclass)
    ) AS s
    WHERE s.seq IS NULL OR s.seq < s.mx;
    IF n > 0 THEN
        RAISE EXCEPTION '% sequence(s) lag behind their table — setval() was missed', n;
    END IF;

    RAISE NOTICE 'all BIGSERIAL sequences are ahead of their data';

    RAISE NOTICE '--------------------------------------------';
    RAISE NOTICE 'ALL INTEGRITY CHECKS PASSED';
    RAISE NOTICE '--------------------------------------------';
END
$$;
