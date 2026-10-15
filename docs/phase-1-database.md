# Phase 1 — Database

**Status:** schema, sample data and verification complete; ready to load into the cluster
**Date:** 2026-09-27
**Milestone (ROADMAP §14):** *All 7 tables exist with sample data.*

---

## 1. Deliverables

| File | Purpose |
| --- | --- |
| `database/bootstrap.sql` | Creates the `tripmate` role and database. Run once, as a superuser. |
| `database/schema.sql` | Creates the seven tables and nine indexes. |
| `database/seed.sql` | Loads the sample data. Idempotent — safe to re-run. |
| `database/reset.sql` | Drops all seven tables in reverse dependency order. |
| `database/verify.sql` | Asserts cross-row integrity rules; fails loudly. |
| `database/load.sh` | `reset` → `schema` → `seed` → `verify`, in one command. |

## 2. Schema design decisions

### 2.1 Money is `DECIMAL(12,2)`, never floating point

A balance calculation is a running sum of many small values. Binary floating
point cannot represent `0.10` exactly, so rounding error accumulates until the
balances stop reconciling and the splits no longer sum to the expense. `numeric`
is exact, and the split totals in the seed data are asserted to the paisa.

### 2.2 Dates and times are separate from audit timestamps

`item_date`/`start_time`/`end_time` and `expense_date` are `DATE`/`TIME`, not
`TIMESTAMP`. JPQL can then order and group by calendar day without a truncation
expression, which is what queries 1 and 2 need. `created_at` and friends stay
`TIMESTAMP` because they record an instant, not a schedule.

### 2.3 Constraints enforce what a rule can be expressed in one row

The database carries the half of business rules that fit in a single row:

| Rule (ROADMAP §8.3) | Enforced by |
| --- | --- |
| A user cannot be added to a trip twice | `UNIQUE (trip_id, user_id)` |
| An expense amount must be > 0 | `CHECK (amount > 0)` |
| A settlement must reference two different users | `CHECK (payer_id <> receiver_id)` |
| Status / role must be a known value | `CHECK (status IN (...))`, `CHECK (role IN (...))` |

The remaining rule — *the splits of an expense must sum to its amount* — spans
rows within a group and cannot be a `CHECK`. It is enforced in `ExpenseService`
in Phase 9, and `database/verify.sql` asserts it over the data.

Each rule has both a database constraint and an EJB check. The database
constraint is the backstop; the EJB check exists so the user gets a readable
message instead of a constraint-violation stack trace.

### 2.4 Referential actions are chosen per relationship

- **Trip-owned rows CASCADE from `trips`** — memberships, itinerary items,
  expenses, settlements. They have no meaning without their trip, and deleting
  a trip should not require the application to delete its children by hand.
- **Nullable user attributions SET NULL** — `trips.created_by`,
  `itinerary_items.created_by`, `expenses.paid_by`. Removing a user must not
  erase a trip or a financial record; only the attribution goes.
- **Identity-defining user references CASCADE** — a membership, a split, or a
  settlement party. Without the user these rows refer to nothing.

### 2.5 Indexes follow the required queries, not the tables

Nine indexes, each traceable to an access path in ROADMAP §9: itinerary by
trip and day (Q1), expenses by trip newest-first (Q2), `trip_members.user_id`
(Q3, Q5), `expenses(trip_id, paid_by)` for Q4's "total paid" subquery, and
`expense_splits.user_id` for the "total owed" subquery in Q4 and Q5. PostgreSQL
indexes primary keys and `UNIQUE` constraints automatically, so no index
duplicates those.

## 3. Sample data

Actual loaded counts, which differ slightly from the ROADMAP §5.10 suggestions:

| Table | Loaded | ROADMAP suggested |
| --- | --- | --- |
| `users` | 5 | 5 |
| `trips` | 3 | 3 |
| `trip_members` | 12 | ~12 |
| `itinerary_items` | 15 | ~15 |
| `expenses` | 25 | ~25 |
| `expense_splits` | **100** | ~80 |
| `settlements` | 6 | ~6 |

The split count is higher because 25 expenses shared across groups of three to
five people naturally produce 100 parts. Forcing it down to 80 would have meant
inventing implausible subset splits, so the suggested figure was not enforced.
More rows only make the balance and category aggregates more representative.

Composition:

- **Trips** — `Pokhara Weekend` (ONGOING, 4 members, NPR 53,300),
  `Everest Base Camp Trek` (PLANNING, 3 members, NPR 52,500),
  `Chitwan Jungle Safari` (COMPLETED, 5 members, NPR 54,500).
  Grand total NPR 160,300 across 25 expenses.
- **Roles are per-trip.** Alice is ADMIN on Pokhara and a plain MEMBER on the
  other two, which is the point of the role living on `trip_members` rather than
  on `users`.
- **Split shapes** — even division, subset splits (only three of four went
  paragliding; only Emma and Bob rented gear), and **two custom unequal
  splits** (#11 and #24) so the balance query has genuinely mixed data rather
  than only averages.
- **Settlement state** — the COMPLETED trip is squared up (`is_settled = TRUE`
  on its splits, with four settlement rows); the ONGOING and PLANNING trips
  carry two partial repayments and remain open.
- **Categories** — seven in use: ACTIVITY 7, FOOD 7, TRANSPORT 5,
  ACCOMMODATION 2, OTHER 2, EQUIPMENT 1, SHOPPING 1.
- **Bob paying Alice NPR 1,200 is deliberately not seeded.** That is the step
  the viva demo performs live (ROADMAP §16.3 step 7), so the seeded Pokhara
  settlements use different pairs.

### 3.1 Passwords

Each user's password is `tripmate123`, stored as PBKDF2-HMAC-SHA256 with a
per-user random 16-byte salt:

```
pbkdf2_sha256$210000$<salt-b64>$<hash-b64>
```

Not a placeholder. The format is fixed now so Phase 4's login has something real
to verify against, and PBKDF2 is in the JDK (`SecretKeyFactory`), so it needs no
extra dependency. The hashes were round-tripped to confirm they verify.

## 4. Verification

### 4.1 Milestone evidence

```
users 5 | trips 3 | trip_members 12 | itinerary_items 15
expenses 25 | expense_splits 100 | settlements 6
```

### 4.2 Integrity checks

`database/verify.sql` passed every check:

```
row counts present
splits reconcile with expense amounts
every expense has at least one split
all expense participants are trip members
settlements reference two distinct trip members
trip date ranges are ordered
all BIGSERIAL sequences are ahead of their data
ALL INTEGRITY CHECKS PASSED
```

The verifier was itself tested by deliberately corrupting the data three ways —
inflating a split by 1.00, rolling the `expenses` sequence back to 1, and
inserting a 0.00 split for a non-member. Each corruption was caught with the
right message, and the verifier passed again once the data was restored.

### 4.3 Constraint behaviour

Fifteen negative cases were run against the live schema and all fifteen were
rejected: duplicate email, duplicate trip membership, invalid trip status,
invalid member role, reversed trip dates, zero and negative expense amounts,
negative split, settlement paying yourself, zero settlement, reversed itinerary
times, and three foreign-key violations.

Referential actions were confirmed directly: deleting a trip took its 2
memberships, 1 itinerary item, 1 expense, 2 splits and 1 settlement to zero
while both users survived; deleting a user set `trips.created_by` and
`expenses.paid_by` to NULL and the trip survived.

### 4.4 The data supports the required queries

Both complex queries were run as SQL against the loaded data.

**Q4 — net balance per member, Pokhara Weekend:**

| Member | Paid | Owed | Net |
| --- | --- | --- | --- |
| Alice Sharma | 13,000.00 | 14,175.00 | −1,175.00 |
| Bob Gurung | 14,700.00 | 9,625.00 | +5,075.00 |
| Carol Thapa | 6,400.00 | 15,625.00 | −9,225.00 |
| David Rai | 19,200.00 | 13,875.00 | +5,325.00 |

**A trip's net balances must sum to zero** — every paisa someone is owed comes
from someone who owes it. All three trips satisfy this exactly, which is the
strongest single check that the seed data is internally consistent:

```
Pokhara Weekend             0.00
Everest Base Camp Trek      0.00
Chitwan Jungle Safari       0.00
```

**Q5 — category spending across Alice's trips:** seven categories returned,
ordered by spend, with correct distinct trip counts (TRANSPORT 41,500.00 across
3 trips, ACTIVITY 38,000.00 across 2, … SHOPPING 3,500.00 across 1). Q5 joins
`splits → expense → trip → trip_members`, so the counts confirm the four-entity
navigation resolves correctly.

### 4.5 Idempotency and sequences

- `seed.sql` run twice produces identical counts, not duplicates or key errors.
- An application-style insert after seeding received `user_id = 6` and
  `trip_id = 4`, confirming `setval()` left the sequences past the seeded ids.
  Without that step the next insert would have collided on id 1.
- `bootstrap.sql` was run twice against a clean cluster: the second run created
  nothing and did not error.

## 5. Reproducing

```bash
# 1. Create the role and database (once, needs superuser)
sudo -u postgres psql -v ON_ERROR_STOP=1 -f database/bootstrap.sql

# 2. Create schema, load data, verify
./database/load.sh
```

`bootstrap.sql` reads the password from a psql variable, so a different one can
be supplied with `-v tripmate_password='...'`; if you change it, export
`TM_DB_PASSWORD` to match before running `load.sh`.

### 5.1 How this was verified

The machine's PostgreSQL superuser requires a password, and `pg_hba.conf` is not
readable, so the whole chain was verified against a **throwaway cluster** on
port 5433, created with `initdb` under the operator's own account:

```bash
/usr/lib/postgresql/18/bin/initdb -D /tmp/tmdb -U srijan --auth=trust --encoding=UTF8 --locale=C
/usr/lib/postgresql/18/bin/pg_ctl -D /tmp/tmdb -o "-p 5433 -k /tmp/tmdb_sock" -l /tmp/tmdb.log start
```

`bootstrap.sql` and `load.sh` were then exercised through the real workflow
against that cluster, including creating the role and database from scratch and
running the loader as the non-superuser `tripmate` role — which confirmed the
non-superuser can create tables in `public` on PostgreSQL 18.

The same commands apply unchanged to the real cluster on port 5432; only the
`--host`/`--port` passed to `psql` differ. The results in §4 are therefore
expected to reproduce exactly, and `./database/load.sh` re-runs `verify.sql` to
confirm it.

## 6. Notes for later phases

- **`is_settled` and Q4 measure different things.** Q4 sums every split
  regardless of `is_settled`, so it reports the *gross* position — paid versus
  owed, before repayments. Settlements reduce *outstanding* amounts in the
  Settle Up flow. Confusing the two is the most likely source of a wrong balance
  in Phase 8; the distinction is also recorded in `seed.sql`'s header.
- **No GlassFish JDBC resource yet.** ROADMAP §14 puts `persistence.xml` and the
  connection pool in Phase 2. The pool must use the credentials in
  `scripts/env.sh` (`TM_DB_USER` / `TM_DB_PASSWORD`), which `database/load.sh`
  already reads.
- **`database/verify.sql` is the seed of the Phase 10 test story.** It is the
  data-layer half of the test matrix; the EJB-layer tests in Phase 9 have to
  cover the rules that need the business tier, chiefly that splits sum to the
  expense amount at write time rather than only in the seed.

---

## 7. Next — Phase 2: JPA Entities

Seven entity classes with the relationships from ROADMAP §6.2, a
`META-INF/persistence.xml`, a GlassFish JDBC resource and connection pool, and a
round-trip test that reads and writes each entity.

**Milestone:** entities round-trip against PostgreSQL.
