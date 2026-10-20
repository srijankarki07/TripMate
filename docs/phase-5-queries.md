# Phase 5: The Five Queries Verified

**Status:** complete, milestone met
**Date:** 2026-09-27
**Milestone (ROADMAP §14):** *All 5 queries return correct, verified results.*

---

## 1. What this phase was

The five queries were implemented in Phase 4, as recorded in
`docs/phase-4-session-beans.md` §2.3: Q1 to Q3 with the services that naturally
expose them, Q4 and Q5 in `ReportService`. That decision was taken deliberately
and is documented there.

This phase is therefore the verification the milestone actually asks for. It
turned out to be considerably more than a formality.

## 2. The approach, and why it matters

A query cannot be checked against itself. Asserting that `getMemberBalances`
returns what the developer expected from reading the JPQL would only confirm
that the same misunderstanding appears in both places.

Every expected value here is therefore computed in **native SQL**, issued
through `EntityManager.createNativeQuery`. That path bypasses entity mapping,
association resolution and JPQL translation completely. If the object path
`es.expense.trip.tripId` resolves to the wrong join, or a fetch silently drops
rows, the two answers differ and the check fails.

Three further choices strengthen it:

**Every trip and every user is checked, not just the demonstration ones.** A
query that happens to be right for a group of four is not a correct query.

**Ordering is verified as a property of the returned sequence**, not by
comparing against a SQL-ordered list. SQL does not define the relative order of
rows that share a sort key, so comparing ordered lists would fail at random
whenever two rows tie. The set of rows is compared against SQL, which proves the
filter and the navigation; the order is then checked separately.

**Cross-query invariants are asserted**, which catch errors no single
query-to-SQL comparison would. The strongest is that a trip's net balances sum
to exactly zero: every paisa someone is owed comes from someone who owes it.
That holds whatever the queries do, so it stands on its own.

| Query | Method | Rows checked |
| --- | --- | --- |
| Q1 itinerary ordered by day and time | `getItineraryForTrip` | all 3 trips |
| Q2 expenses newest first | `getExpensesForTrip` | all 3 trips |
| Q3 trips a user belongs to | `getTripsForUser` | all 5 users |
| Q4 net balance per member | `getMemberBalances` | all 3 trips |
| Q5 category spending | `getCategorySpendingForUser` | all 5 users |

## 3. Query 5 was measuring the wrong thing

**The first run failed on eight checks**, and this was the reason.

ROADMAP §9.5 specifies:

```jpql
FROM ExpenseSplit es JOIN es.expense e JOIN e.trip t JOIN t.members tm
WHERE tm.user.userId = :userId
```

The `WHERE` filters on `tm.user`, the member reached through the join, and then
sums **every** split found. That measures what the *group* spent on the trips the
user was part of, not what the user spent. The join to `TripMember` exists to
satisfy the assignment's "3 or more related entities" requirement, and filtering
on it changes what the query means.

The numbers make it plain. For the seeded data:

| Measure | User 1 |
| --- | --- |
| Sum of every split on trips user 1 belongs to (ROADMAP §9.5) | **160,300.00** |
| User 1's own share (their splits) | **41,135.00** |

160,300.00 is the entire database. User 1 happens to be a member of all three
seeded trips, so the query returns everything. For any user on every trip, it
returns the whole database. And the report it feeds is called **"Where Your
Money Went"**.

The corrected query filters on the split's own owner as well:

```jpql
FROM ExpenseSplit es JOIN es.expense e JOIN e.trip t JOIN t.members tm
WHERE es.user.userId = :userId AND tm.user.userId = :userId
```

The `TripMember` join is kept. It still gives the four-entity navigation the
assignment requires, and it now serves a second purpose as a membership guard:
a split only counts if the user is on the trip it belongs to. Filtering on the
split's owner is also what keeps the row count right, since the join to members
would otherwise multiply each split once per member of the trip.

The corrected figure agrees exactly with Q4's `totalOwed` for the same user,
which is a good sign that it now measures what the balances view measures.

**This follows the ROADMAP's own guidance.** §21 states: *"If a design decision
makes the app more useful to real users without breaking the assignment
requirements, choose it."* The assignment requirement, navigating three or more
related entities, is still met. The report's name, its position on the Reports
page, and the balances it sits beside all say it should measure the user's own
spending.

The correction is documented on the method itself, in `ReportService`, so a
reader comparing the code against §9.5 finds the explanation rather than an
apparent typo.

## 4. A bug in the verification itself

The second failure was mine, not the application's. **Q2's ordering check failed
for trip 1**, and the query was correct.

The composite sort key was built by joining its parts into a string, so an
expense's key looked like `2026-09-27|11|`. Compared as text, `"11"` sorts before
`"9"`, because `'1'` precedes `'9'`. A correctly ordered list of ids was
therefore reported as unordered the moment any id passed nine, which for trip 1
it did.

The fix is a `CompositeKey` that compares element by element. The lesson is
worth recording because it is the same class of error the phase exists to catch:
the check was wrong, not the thing being checked, and only running it revealed
which was which.

## 5. The checks were proved non-vacuous

The trip filter was removed from Q4's total-owed subquery, so each member's
"owed" figure would include their splits from every other trip:

```
SUMMARY: all 35 checks passed
SUMMARY: all 55 checks passed
SUMMARY: all 60 checks passed
SUMMARY: 13 of 97 query checks FAILED
SUMMARY: all 10 rejections behaved as required
```

Thirteen checks failed, across three independent kinds of assertion:

- the direct comparison against native SQL (`total owed matches SQL`)
- the arithmetic property (`net balance is paid minus owed`)
- the standalone ledger invariant (`net balances sum to exactly zero`)

The other four suites were unaffected. Because these queries are read-only, this
run left no residue, unlike the Phase 4 sabotage.

## 6. Results

```
SECTION: Persistence: entity round-trip   SUMMARY: all 35 checks passed
SECTION: DTO mapping                      SUMMARY: all 55 checks passed
SECTION: Session bean CRUD                SUMMARY: all 60 checks passed
SECTION: The five required queries        SUMMARY: all 97 query checks passed
SECTION: Session bean rejections          SUMMARY: all 10 rejections behaved as required

257 passed, 0 failed.
```

The sample data is unchanged: 5 users, 3 trips, 12 memberships, 15 itinerary
items, 25 expenses, 100 splits, 6 settlements.

## 7. Reproducing

```bash
./scripts/deploy.sh
xdg-open http://localhost:8080/tripmate/diagnostics.xhtml
```

## 8. Notes for later phases

- **Query 5's semantics differ from ROADMAP §9.5, deliberately.** If the report
  is ever meant to show group spending rather than personal spending, revert the
  `WHERE` clause; the reasoning is on the method. The same correction has to be
  made to the SQL yardstick in `QueryVerificationSmokeTest`, or the verification
  will disagree with the implementation.
- **Q1's ordering depends on nulls sorting last.** PostgreSQL puts nulls last in
  an ascending sort by default and EclipseLink inherits that. No seeded item has
  a null `start_time`, so the behaviour is untested; if items are ever allowed
  without a start time, check what the ordering does with them.
- **Q4 remains the gross position.** It counts every split regardless of
  `is_settled` and does not net off settlements. The verification checks it
  against SQL on those terms, so it would still pass if settlements were
  recorded; the Settle Up flow in Phase 8 asks the other question.
- **The verification is tied to the seed data's shape.** The invariant checks
  assert 3 trips, 25 expenses and 100 splits. Changing `database/seed.sql`
  means updating those numbers, or the suite will report the data as damaged.

---

## 9. Next: Phase 6, the web tier

JSF pages one to twelve from ROADMAP §11.2, beginning with login and the
dashboard, with PrimeFaces and the navigation chart. This is where the
diagnostics page stops being the only way to exercise the application.

**Milestone:** full CRUD usable from the browser.
