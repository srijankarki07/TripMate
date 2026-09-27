# Phase 10: Testing

**Status:** complete, milestone met
**Date:** 2026-09-27
**Milestone (ROADMAP §14):** *All tests pass; screenshots stored.*

---

## 1. Deliverables

| Piece | What it is |
| --- | --- |
| `scripts/test-matrix.sh` | Runs all sixteen §16.2 rows and prints a pass/fail table |
| `docs/screenshots/` | Seventeen screenshots, each from a running page |
| The diagnostics page | Six suites, 281 checks, run from the browser |

## 2. The §16.2 matrix

Every row was driven through the real user interface and the effect checked in
PostgreSQL afterwards. Nothing is asserted from the page text alone: a screen
saying an expense was saved is not evidence that a row exists.

```
TripMate: ROADMAP section 16.2 test matrix
==========================================

Sample data: trip_id=1 for Pokhara Weekend

 1. Register a new user                      PASS  user row created, hash stored
 2. Login with a wrong password              PASS  refused with a message, dashboard still redirects
 3. Create a trip                            PASS  trip created with its creator as ADMIN
 4. Add a member to a trip                   PASS  membership row created
 5. Add a duplicate member                   PASS  4 of 5 users selectable, 2 memberships, none duplicated
 6. Add an itinerary item                    PASS  row created with its date and both times
 7. Add an expense with an equal split       PASS  written asynchronously, 2 splits totalling 600.00
 8. Add an expense with an unequal split     PASS  splits stored as given (750.00,250.00)
 9. Splits do not sum to the amount          PASS  refused with a message naming both figures, nothing written
10. View balances (query 4)                  PASS  rendered, positions sum to 0.00
11. Record a settlement                      PASS  settlement row created
12. View the category report (query 5)       PASS  chart carries the query's series
13. A non-member tries to add an expense     PASS  page refuses to load the form for a non-member
14. Remove a member as a non-admin           PASS  no Remove control offered
15. The JMS message was processed            PASS  writes logged by the consumer, each with a correlation id
16. Cold deploy on a clean database          PASS  schema, sample data and integrity checks pass from empty

==========================================
  16 passed, 0 failed
```

Rows 13 and 14 are the two recorded as failing in the Phase 8 notes, before the
Phase 9 rules existed. They pass now.

The script signs in as three different users, because two rows are about what a
non-member and a plain member are refused, and those refusals cannot be observed
while signed in as an administrator.

### 2.1 The cold-deploy row is different

Rows 1 to 15 exercise a running application. Row 16 is a property of the build:
that the schema, the sample data and the integrity checks all succeed from an
empty database, and that the application then serves. It is checked by running
`database/load.sh` from empty rather than by a request, because no request can
demonstrate it.

## 3. Two findings from running the matrix

### 3.1 The duplicate-member rule is not reachable from the form

Row 5 failed at first, and the reason is more interesting than the failure.

The invite picker offers only people who are **not already on the trip**:

```
Pokhara's members: Alice (admin), Bob, Carol, David
options the picker offers:  "Choose someone",  Emma Shrestha
```

So a duplicate cannot be selected at all. Posting an id that the picker does not
offer is an invalid selection, which raises a validation error, and JSF then
skips the action entirely, so `addMember`'s own duplicate check never runs.

**The UI defends before the tier does.** That is the right order, and it means
this row tests the UI defence, not the business rule. The rule itself is
exercised directly by the diagnostics suite, which calls `addMember` and expects
it to refuse.

The row now checks what is actually reachable: that existing members are not
offered, and that no membership is duplicated.

### 3.2 A test that passed for the wrong reason

Rewriting row 5 introduced a worse problem than the original failure. The
rewritten check read a page it had not fetched, so the count came back empty,
and in bash `[[ "" -lt 5 ]]` is true, because an empty string compares as zero.

The row printed **PASS** with no number in its detail. That is a vacuous pass,
which is more dangerous than a failure: a failure gets investigated, and a pass
does not.

The count is now validated as a number before it is compared, and an unreadable
value fails the row with the value printed. It is the third time this project
has caught a test passing for a reason other than the one it claims, after the
Phase 6 stale view state and the Phase 8 badly aimed sabotage.

## 4. The six diagnostics suites

The matrix covers what a user can do. These cover what the tiers do, and are
re-runnable from `diagnostics.xhtml`:

```
Persistence: entity round-trip        all 35 checks passed
DTO mapping                           all 55 checks passed
Session bean CRUD                     all 60 checks passed
The five required queries             all 97 query checks passed
Session bean rejections               all 19 rejections behaved as required
Balances and settle-up arithmetic     all 15 arithmetic checks passed

281 passed, 0 failed.
```

## 5. Screenshots

Seventeen, each captured from a running page with real seeded data. The ROADMAP
asks for twelve.

| File | Shows |
| --- | --- |
| `phase-0-deployment-check.png` | The Phase 0 deployment check, styled by the design tokens |
| `phase-2-entity-roundtrip.png` | 35 entity round-trip checks against PostgreSQL |
| `phase-3-dto-mapping.png` | 55 entity-to-DTO mapping checks |
| `phase-4-session-beans.png` | The consolidated diagnostics page |
| `phase-6-login.png` | Sign in |
| `phase-6-register.png` | Account creation |
| `phase-6-dashboard.png` | Totals, the user's trips, recent spending |
| `phase-6-trip-list.png` | Every trip the user belongs to |
| `phase-6-trip-detail.png` | The four-tab trip overview |
| `phase-6-itinerary.png` | The itinerary editor |
| `phase-6-expenses.png` | The expense form with its split editor |
| `phase-6-balances.png` | Balances, before the outstanding-position fix |
| `phase-6-settle.png` | Recording a repayment |
| `phase-6-reports-chart.png` | Category spending, chart and table |
| `phase-6-profile.png` | Details and password |
| `phase-8-balances-ledger.png` | The ledger, showing net and outstanding separately |
| `phase-9-diagnostics.png` | All six suites including the authorization checks |

## 6. Reproducing

```bash
./scripts/deploy.sh
./scripts/test-matrix.sh
```

The matrix leaves the sample data as it found it, so it can be run immediately
before a demonstration without changing what the demonstration shows. Verified:
after a run the counts are back at 5 users, 3 trips, 12 memberships, 15
itinerary items, 25 expenses, 100 splits and 6 settlements, and
`database/verify.sql` passes.

## 7. What is not tested

Worth stating plainly before the viva rather than being asked.

- **Concurrency.** Nothing exercises two requests writing at once. The
  `UNIQUE (trip_id, user_id)` constraint and the duplicate check in
  `addMember` are two layers of defence against a race, and only the second has
  been reasoned about, not tested. The unique constraint is what makes it safe.
- **Failure of the database mid-transaction.** The message consumer's
  redelivery path was exercised for a *permanent* failure, in Phase 7. The
  transient path, where the database is briefly unreachable and the message
  should be redelivered and then succeed, has not been.
- **The UI on a phone.** The layouts are responsive by construction, with grid
  columns that reduce, but no screenshot was taken at a narrow width.
- **Load.** Everything was measured with a handful of rows. Query 4's correlated
  subqueries and the trip list's grouped count would need revisiting at a scale
  this project never reaches.

## 8. Notes for later phases

- **`scripts/test-matrix.sh` creates and deletes its own trip and user.** If it
  fails partway the leftovers are named `Matrix ...` and
  `matrix-...@tripmate.test`, so they are easy to find.
- **The screenshots show the seeded data**, so they can be regenerated by
  re-running the loader and capturing again. `phase-6-balances.png` deliberately
  predates the Phase 8 fix and is kept as the record of what that page looked
  like; `phase-8-balances-ledger.png` is its replacement and the one to use in
  the report.

---

## 9. Next: Phase 11, the reports

Write `docs/report.md`, the Part Two design document, and
`docs/serverless-report.md`, the Part One evaluation. Export the diagrams.
Phase 12 packages the EAR, rehearses the demonstration and prepares the viva
answers.

**Milestone:** both reports complete.
