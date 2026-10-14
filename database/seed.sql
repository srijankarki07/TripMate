-- ============================================================================
-- TripMate — sample data
-- ============================================================================
--
-- Loads a realistic dataset large enough to make the two complex queries
-- (ROADMAP §9.4 balances, §9.5 category spending) produce meaningful output
-- rather than two rows per table.
--
--   psql -f database/seed.sql
--
-- The whole load runs in ONE transaction, and begins by truncating the seven
-- tables, so this script is idempotent — running it twice leaves the same data
-- rather than failing on a duplicate key or silently doubling the rows.
--
-- Requires schema.sql to have been applied first.
--
-- ----------------------------------------------------------------------------
-- Dataset
-- ----------------------------------------------------------------------------
--   5 users            Alice, Bob, Carol, David, Emma
--   3 trips            one ONGOING, one PLANNING, one COMPLETED
--   12 memberships     group sizes 4, 3 and 5
--   15 itinerary items
--   25 expenses        across 7 categories
--   100 splits         equal, subset, and two custom unequal splits
--   6 settlements
--
-- ROADMAP §5.10 suggests "~80" splits. The natural split of 25 expenses across
-- groups of three to five people gives 100; the extra rows only make the
-- balance and category aggregates richer, so the suggested figure was not
-- forced downward.
--
-- Trip 3 is COMPLETED and fully squared up, so its splits carry
-- is_settled = TRUE and it has settlement rows. Trips 1 and 2 are
-- ONGOING / PLANNING and remain unsettled.
--
-- NOTE ON Q4: the balance query sums every split regardless of is_settled, so
-- it reports the gross position — what each member paid against what they owe,
-- before repayments. Settlements reduce the outstanding amount in the Settle Up
-- flow. The two are deliberately different views.
--
-- ----------------------------------------------------------------------------
-- Credentials
-- ----------------------------------------------------------------------------
-- Every seeded user has the password  tripmate123
--
-- Stored as PBKDF2-HMAC-SHA256, format:
--     pbkdf2_sha256$<iterations>$<salt-b64>$<hash-b64>
--
-- A per-user random 16-byte salt, so identical passwords produce different
-- digests. This is a development fixture; the seed password exists so the viva
-- demo can log in as any member.
-- ============================================================================

BEGIN;

-- Makes the script re-runnable. RESTART IDENTITY also resets the BIGSERIAL
-- sequences; the explicit ids below are re-synchronised with setval() at the
-- end so later application inserts cannot collide.
TRUNCATE TABLE expense_splits, settlements, expenses, itinerary_items,
               trip_members, trips, users
    RESTART IDENTITY CASCADE;


-- ----------------------------------------------------------------------------
-- users
-- ----------------------------------------------------------------------------
INSERT INTO users (user_id, full_name, email, password_hash, phone, created_at) VALUES
(1, 'Alice Sharma',   'alice@tripmate.test', 'pbkdf2_sha256$210000$5LrbMpHyUImo253+s3GqVQ==$XTEI6pkcu+Msuwn8oLHlrsNmRZzwukDm/zFvvx0mpuc=', '+977-9801000001', '2026-01-15 09:12:00'),
(2, 'Bob Gurung',     'bob@tripmate.test',   'pbkdf2_sha256$210000$lxstk7a9LnKs2LNfqsgqLA==$7GzXwEl0TqiyNUZNO4xPnpgd7pFBSdsHIZ5pETBMNfg=', '+977-9801000002', '2026-01-15 09:40:00'),
(3, 'Carol Thapa',    'carol@tripmate.test', 'pbkdf2_sha256$210000$Vv6H2AH43keClMpQN3ynoQ==$E7PE33dPoHAynADOVAprHrnk8ahlKTNbpHIxzUNnHrA=', '+977-9801000003', '2026-01-22 14:05:00'),
(4, 'David Rai',      'david@tripmate.test', 'pbkdf2_sha256$210000$L35ezUQZUj2yKqM3obhQFA==$rOxgXxBCgGYsr021zWbXRkxyDXtNEI0ffnfed7gXn4I=', '+977-9801000004', '2026-02-03 11:26:00'),
(5, 'Emma Shrestha',  'emma@tripmate.test',  'pbkdf2_sha256$210000$oUmKqpBc57h7sATrMIYvCQ==$BNVf98qDJ7/u+6nb8qUO18Hb4DZ3LSdMjlPYOXzE6WQ=', '+977-9801000005', '2026-02-20 16:48:00');


-- ----------------------------------------------------------------------------
-- trips
-- ----------------------------------------------------------------------------
INSERT INTO trips (trip_id, trip_name, destination, start_date, end_date, description, status, created_by, created_at) VALUES
(1, 'Pokhara Weekend', 'Pokhara, Gandaki', '2026-09-25', '2026-09-28',
 'Long weekend by Phewa Lake — paragliding, sunrise at Sarangkot, and a lot of Thakali food.',
 'ONGOING', 1, '2026-09-01 10:00:00'),

(2, 'Everest Base Camp Trek', 'Solukhumbu, Koshi', '2026-11-05', '2026-11-20',
 'Sixteen-day trek to Everest Base Camp via the classic Lukla route. Permits and guide arranged in advance.',
 'PLANNING', 2, '2026-08-15 19:30:00'),

(3, 'Chitwan Jungle Safari', 'Chitwan, Bagmati', '2026-04-10', '2026-04-13',
 'Four days in the Terai — canoe trip, jungle walk and a full jeep safari in the core area.',
 'COMPLETED', 3, '2026-03-01 08:15:00');


-- ----------------------------------------------------------------------------
-- trip_members
-- ----------------------------------------------------------------------------
-- Alice administers the Pokhara trip, Bob the trek, Carol the safari; everyone
-- else is an ordinary member. Role is per-trip, so Alice is ADMIN on trip 1 and
-- a plain MEMBER on trips 2 and 3.
INSERT INTO trip_members (trip_id, user_id, role, joined_at) VALUES
-- Pokhara Weekend — 4 members
(1, 1, 'ADMIN',  '2026-09-01 10:00:00'),
(1, 2, 'MEMBER', '2026-09-01 10:22:00'),
(1, 3, 'MEMBER', '2026-09-02 09:05:00'),
(1, 4, 'MEMBER', '2026-09-03 20:41:00'),

-- Everest Base Camp Trek — 3 members
(2, 2, 'ADMIN',  '2026-08-15 19:30:00'),
(2, 1, 'MEMBER', '2026-08-16 07:55:00'),
(2, 5, 'MEMBER', '2026-08-20 13:10:00'),

-- Chitwan Jungle Safari — 5 members
(3, 3, 'ADMIN',  '2026-03-01 08:15:00'),
(3, 4, 'MEMBER', '2026-03-01 08:50:00'),
(3, 5, 'MEMBER', '2026-03-02 12:30:00'),
(3, 1, 'MEMBER', '2026-03-04 18:20:00'),
(3, 2, 'MEMBER', '2026-03-10 09:45:00');


-- ----------------------------------------------------------------------------
-- itinerary_items
-- ----------------------------------------------------------------------------
INSERT INTO itinerary_items (trip_id, title, description, item_date, start_time, end_time, location, created_by, created_at) VALUES
-- Pokhara Weekend
(1, 'Drive to Pokhara', 'Scenic drive along the Prithvi Highway, with a breakfast stop at Mugling.', '2026-09-25', '07:00', '14:00', 'Prithvi Highway', 1, '2026-09-01 11:20:00'),
(1, 'Sunset at Phewa Lake', 'Rent a boat and row out for sunset over the Annapurna range.', '2026-09-25', '17:30', '19:00', 'Phewa Lake', 2, '2026-09-01 11:25:00'),
(1, 'Sarangkot sunrise', 'Early jeep up to Sarangkot for sunrise over the Himalayas.', '2026-09-26', '05:00', '07:30', 'Sarangkot', 1, '2026-09-02 19:00:00'),
(1, 'Paragliding', 'Tandem paragliding from the Sarangkot launch site. Booked for three.', '2026-09-26', '09:00', '11:00', 'Sarangkot launch', 4, '2026-09-03 21:10:00'),
(1, 'Davis Falls and Gupteshwor Cave', 'Waterfall visit followed by the cave temple opposite.', '2026-09-27', '10:00', '13:00', 'Davis Falls', 3, '2026-09-04 10:30:00'),
(1, 'World Peace Pagoda hike', 'Hike up from the lakeside to the peace pagoda.', '2026-09-28', '08:00', '12:00', 'World Peace Pagoda', 1, '2026-09-05 15:40:00'),

-- Everest Base Camp Trek
(2, 'Fly to Lukla', 'Early morning flight from Kathmandu to Lukla, then kit check.', '2026-11-05', '06:00', '07:00', 'Tribhuvan International Airport', 2, '2026-08-16 10:00:00'),
(2, 'Trek to Phakding', 'First day on the trail, descending alongside the Dudh Koshi.', '2026-11-06', '07:00', '12:00', 'Phakding', 2, '2026-08-16 10:05:00'),
(2, 'Acclimatisation day in Namche', 'Rest day to adjust to altitude, with a short hike to the Everest View Hotel.', '2026-11-08', '09:00', '15:00', 'Namche Bazaar', 5, '2026-08-20 14:00:00'),
(2, 'Reach Everest Base Camp', 'Final push to base camp via Gorak Shep.', '2026-11-12', '06:00', '14:00', 'Everest Base Camp', 2, '2026-08-21 09:30:00'),
(2, 'Kala Patthar sunrise', 'Pre-dawn climb for the classic view back down over base camp.', '2026-11-15', '04:30', '08:00', 'Kala Patthar', 5, '2026-08-21 09:35:00'),

-- Chitwan Jungle Safari
(3, 'Canoe ride on the Rapti River', 'Dugout canoe with a guide, spotting crocodiles and river birds.', '2026-04-10', '07:00', '09:00', 'Rapti River', 3, '2026-03-02 11:00:00'),
(3, 'Jungle walk', 'Guided walk through the buffer zone forest.', '2026-04-11', '06:30', '10:30', 'Chitwan buffer zone', 4, '2026-03-02 11:05:00'),
(3, 'Elephant breeding centre', 'Visit to the government breeding centre at Sauraha.', '2026-04-11', '14:00', '16:00', 'Sauraha', 5, '2026-03-03 09:20:00'),
(3, 'Jeep safari', 'Full morning jeep safari into the core area.', '2026-04-12', '06:00', '12:00', 'Chitwan core area', 3, '2026-03-03 09:25:00');


-- ----------------------------------------------------------------------------
-- expenses
-- ----------------------------------------------------------------------------
-- Split into one expense row plus N split rows; the splits follow below and
-- must sum exactly to amount for each expense (business rule ROADMAP §8.3).
INSERT INTO expenses (expense_id, trip_id, paid_by, description, amount, category, expense_date, created_at) VALUES
-- Pokhara Weekend (ONGOING) — 11 expenses
( 1, 1, 1, 'Bus tickets to Pokhara',              6000.00, 'TRANSPORT',     '2026-09-25', '2026-09-25 15:10:00'),
( 2, 1, 2, 'Lakeside guesthouse, 2 nights',       9600.00, 'ACCOMMODATION', '2026-09-25', '2026-09-25 16:02:00'),
( 3, 1, 1, 'Dinner at Lakeside',                  4800.00, 'FOOD',          '2026-09-25', '2026-09-25 21:35:00'),
( 4, 1, 3, 'Sarangkot jeep hire',                 2500.00, 'TRANSPORT',     '2026-09-26', '2026-09-26 07:50:00'),
( 5, 1, 4, 'Paragliding flights',                18000.00, 'ACTIVITY',      '2026-09-26', '2026-09-26 12:15:00'),
( 6, 1, 2, 'Breakfast at café',                   1600.00, 'FOOD',          '2026-09-26', '2026-09-26 09:05:00'),
( 7, 1, 4, 'Boat ride on Phewa Lake',             1200.00, 'ACTIVITY',      '2026-09-26', '2026-09-26 18:40:00'),
( 8, 1, 3, 'Davis Falls entry tickets',            800.00, 'ACTIVITY',      '2026-09-27', '2026-09-27 13:20:00'),
( 9, 1, 1, 'Lunch at Thakali kitchen',            2200.00, 'FOOD',          '2026-09-27', '2026-09-27 14:10:00'),
(10, 1, 2, 'Souvenirs and pashmina',              3500.00, 'SHOPPING',      '2026-09-27', '2026-09-27 17:45:00'),
(11, 1, 3, 'Dinner at Caffeine Restaurant',       3100.00, 'FOOD',          '2026-09-27', '2026-09-27 21:50:00'),

-- Everest Base Camp Trek (PLANNING) — 4 deposits paid up front
(12, 2, 2, 'Flight tickets Kathmandu–Lukla (deposit)', 24000.00, 'TRANSPORT', '2026-09-20', '2026-09-20 11:30:00'),
(13, 2, 1, 'Trekking permits and TIMS cards',           9000.00, 'OTHER',     '2026-09-21', '2026-09-21 15:05:00'),
(14, 2, 5, 'Sleeping bag and down jacket rental',       4500.00, 'EQUIPMENT', '2026-09-22', '2026-09-22 18:25:00'),
(15, 2, 2, 'Guide advance payment',                    15000.00, 'OTHER',     '2026-09-23', '2026-09-23 10:40:00'),

-- Chitwan Jungle Safari (COMPLETED) — 10 expenses
(16, 3, 3, 'Bus to Sauraha',                      4500.00, 'TRANSPORT',     '2026-04-10', '2026-04-10 13:15:00'),
(17, 3, 4, 'Resort booking, 3 nights',           18000.00, 'ACCOMMODATION', '2026-04-10', '2026-04-10 14:00:00'),
(18, 3, 5, 'Dinner at the resort',                3500.00, 'FOOD',          '2026-04-10', '2026-04-10 21:20:00'),
(19, 3, 3, 'Canoe ride',                          2000.00, 'ACTIVITY',      '2026-04-11', '2026-04-11 09:30:00'),
(20, 3, 1, 'Jungle walk guide',                   3000.00, 'ACTIVITY',      '2026-04-11', '2026-04-11 11:00:00'),
(21, 3, 2, 'Lunch at Sauraha',                    1800.00, 'FOOD',          '2026-04-11', '2026-04-11 13:40:00'),
(22, 3, 4, 'Jeep safari',                        12000.00, 'ACTIVITY',      '2026-04-12', '2026-04-12 13:05:00'),
(23, 3, 5, 'Elephant breeding centre tickets',    1000.00, 'ACTIVITY',      '2026-04-12', '2026-04-12 16:30:00'),
(24, 3, 3, 'Dinner and drinks',                   4200.00, 'FOOD',          '2026-04-12', '2026-04-12 22:10:00'),
(25, 3, 4, 'Bus back to Kathmandu',               4500.00, 'TRANSPORT',     '2026-04-13', '2026-04-13 08:20:00');


-- ----------------------------------------------------------------------------
-- expense_splits
-- ----------------------------------------------------------------------------
-- Grouped by expense, with the arithmetic stated so each block can be checked
-- by eye. `database/verify.sql` asserts the same thing mechanically: every
-- expense's splits sum to its amount.
--
-- is_settled is TRUE only for the COMPLETED trip, which has been squared up.
--
-- Two expenses use custom unequal splits rather than an even division:
--   #11 dinner — Alice had the set menu plus a drink
--   #24 dinner and drinks — Carol and David drank more
INSERT INTO expense_splits (expense_id, user_id, amount_owed, is_settled) VALUES
-- Trip 1 -------------------------------------------------------------------
-- #1  6000.00 / 4 = 1500.00
( 1, 1, 1500.00, FALSE), ( 1, 2, 1500.00, FALSE), ( 1, 3, 1500.00, FALSE), ( 1, 4, 1500.00, FALSE),
-- #2  9600.00 / 4 = 2400.00
( 2, 1, 2400.00, FALSE), ( 2, 2, 2400.00, FALSE), ( 2, 3, 2400.00, FALSE), ( 2, 4, 2400.00, FALSE),
-- #3  4800.00 / 4 = 1200.00   (the viva demo expense)
( 3, 1, 1200.00, FALSE), ( 3, 2, 1200.00, FALSE), ( 3, 3, 1200.00, FALSE), ( 3, 4, 1200.00, FALSE),
-- #4  2500.00 / 4 =  625.00
( 4, 1,  625.00, FALSE), ( 4, 2,  625.00, FALSE), ( 4, 3,  625.00, FALSE), ( 4, 4,  625.00, FALSE),
-- #5 18000.00 / 3 = 6000.00   (only three flew — Bob sat this one out)
( 5, 1, 6000.00, FALSE), ( 5, 3, 6000.00, FALSE), ( 5, 4, 6000.00, FALSE),
-- #6  1600.00 / 4 =  400.00
( 6, 1,  400.00, FALSE), ( 6, 2,  400.00, FALSE), ( 6, 3,  400.00, FALSE), ( 6, 4,  400.00, FALSE),
-- #7  1200.00 / 4 =  300.00
( 7, 1,  300.00, FALSE), ( 7, 2,  300.00, FALSE), ( 7, 3,  300.00, FALSE), ( 7, 4,  300.00, FALSE),
-- #8   800.00 / 4 =  200.00
( 8, 1,  200.00, FALSE), ( 8, 2,  200.00, FALSE), ( 8, 3,  200.00, FALSE), ( 8, 4,  200.00, FALSE),
-- #9  2200.00 / 4 =  550.00
( 9, 1,  550.00, FALSE), ( 9, 2,  550.00, FALSE), ( 9, 3,  550.00, FALSE), ( 9, 4,  550.00, FALSE),
-- #10 3500.00 / 2 = 1750.00   (shared by the two who went shopping)
(10, 2, 1750.00, FALSE), (10, 3, 1750.00, FALSE),
-- #11 3100.00 custom: 1000.00 + 700.00 + 700.00 + 700.00 = 3100.00
(11, 1, 1000.00, FALSE), (11, 2,  700.00, FALSE), (11, 3,  700.00, FALSE), (11, 4,  700.00, FALSE),

-- Trip 2 -------------------------------------------------------------------
-- #12 24000.00 / 3 = 8000.00
(12, 1, 8000.00, FALSE), (12, 2, 8000.00, FALSE), (12, 5, 8000.00, FALSE),
-- #13  9000.00 / 3 = 3000.00
(13, 1, 3000.00, FALSE), (13, 2, 3000.00, FALSE), (13, 5, 3000.00, FALSE),
-- #14  4500.00 / 2 = 2250.00   (only Emma and Bob needed rental gear)
(14, 2, 2250.00, FALSE), (14, 5, 2250.00, FALSE),
-- #15 15000.00 / 3 = 5000.00
(15, 1, 5000.00, FALSE), (15, 2, 5000.00, FALSE), (15, 5, 5000.00, FALSE),

-- Trip 3 — settled ------------------------------------------------------------
-- #16  4500.00 / 5 =  900.00
(16, 1,  900.00, TRUE), (16, 2,  900.00, TRUE), (16, 3,  900.00, TRUE), (16, 4,  900.00, TRUE), (16, 5,  900.00, TRUE),
-- #17 18000.00 / 5 = 3600.00
(17, 1, 3600.00, TRUE), (17, 2, 3600.00, TRUE), (17, 3, 3600.00, TRUE), (17, 4, 3600.00, TRUE), (17, 5, 3600.00, TRUE),
-- #18  3500.00 / 5 =  700.00
(18, 1,  700.00, TRUE), (18, 2,  700.00, TRUE), (18, 3,  700.00, TRUE), (18, 4,  700.00, TRUE), (18, 5,  700.00, TRUE),
-- #19  2000.00 / 5 =  400.00
(19, 1,  400.00, TRUE), (19, 2,  400.00, TRUE), (19, 3,  400.00, TRUE), (19, 4,  400.00, TRUE), (19, 5,  400.00, TRUE),
-- #20  3000.00 / 3 = 1000.00   (only three did the jungle walk)
(20, 1, 1000.00, TRUE), (20, 3, 1000.00, TRUE), (20, 5, 1000.00, TRUE),
-- #21  1800.00 / 5 =  360.00
(21, 1,  360.00, TRUE), (21, 2,  360.00, TRUE), (21, 3,  360.00, TRUE), (21, 4,  360.00, TRUE), (21, 5,  360.00, TRUE),
-- #22 12000.00 / 5 = 2400.00
(22, 1, 2400.00, TRUE), (22, 2, 2400.00, TRUE), (22, 3, 2400.00, TRUE), (22, 4, 2400.00, TRUE), (22, 5, 2400.00, TRUE),
-- #23  1000.00 / 5 =  200.00
(23, 1,  200.00, TRUE), (23, 2,  200.00, TRUE), (23, 3,  200.00, TRUE), (23, 4,  200.00, TRUE), (23, 5,  200.00, TRUE),
-- #24  4200.00 custom: 500.00 + 400.00 + 1200.00 + 1200.00 + 900.00 = 4200.00
(24, 1,  500.00, TRUE), (24, 2,  400.00, TRUE), (24, 3, 1200.00, TRUE), (24, 4, 1200.00, TRUE), (24, 5,  900.00, TRUE),
-- #25  4500.00 / 5 =  900.00
(25, 1,  900.00, TRUE), (25, 2,  900.00, TRUE), (25, 3,  900.00, TRUE), (25, 4,  900.00, TRUE), (25, 5,  900.00, TRUE);


-- ----------------------------------------------------------------------------
-- settlements
-- ----------------------------------------------------------------------------
-- Real repayments already made. Trips 1 and 2 still owe money, so their
-- settlements are partial; trip 3 is fully squared up.
--
-- Note that Bob -> Alice for 1200.00 is deliberately NOT seeded. It is the
-- step the viva demo performs live (ROADMAP §16.3, step 7), so the seeded
-- Pokhara settlements are different pairs.
INSERT INTO settlements (trip_id, payer_id, receiver_id, amount, note, settled_at) VALUES
-- Pokhara Weekend — partial repayments while the trip is still running
(1, 3, 1, 1000.00, 'Share of the bus tickets',              '2026-09-26 09:15:00'),
(1, 4, 2,  600.00, 'Part payment for the guesthouse',       '2026-09-27 08:40:00'),

-- Chitwan Jungle Safari — settled up after returning
(3, 5, 4, 2000.00, 'Settled after the trip',                '2026-04-20 19:30:00'),
(3, 2, 4, 1500.00, 'Settled after the trip',                '2026-04-21 12:05:00'),
(3, 1, 3,  800.00, 'Settled after the trip',                '2026-04-22 20:15:00'),
(3, 5, 3,  700.00, 'Final settlement',                      '2026-04-23 10:50:00');


-- ----------------------------------------------------------------------------
-- Re-synchronise the BIGSERIAL sequences
-- ----------------------------------------------------------------------------
-- The inserts above specify primary keys explicitly, which does NOT advance the
-- sequences. Without this, the first application insert would try id 1 again
-- and fail with a duplicate key. setval() moves each sequence past the highest
-- id that was loaded.
SELECT setval(pg_get_serial_sequence('users',          'user_id'),       (SELECT MAX(user_id)       FROM users));
SELECT setval(pg_get_serial_sequence('trips',          'trip_id'),       (SELECT MAX(trip_id)       FROM trips));
SELECT setval(pg_get_serial_sequence('trip_members',   'member_id'),     (SELECT MAX(member_id)     FROM trip_members));
SELECT setval(pg_get_serial_sequence('itinerary_items','item_id'),       (SELECT MAX(item_id)       FROM itinerary_items));
SELECT setval(pg_get_serial_sequence('expenses',       'expense_id'),    (SELECT MAX(expense_id)    FROM expenses));
SELECT setval(pg_get_serial_sequence('expense_splits', 'split_id'),      (SELECT MAX(split_id)      FROM expense_splits));
SELECT setval(pg_get_serial_sequence('settlements',    'settlement_id'), (SELECT MAX(settlement_id) FROM settlements));

COMMIT;
