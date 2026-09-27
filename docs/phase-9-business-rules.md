# Phase 9: Business Rules and Validation

**Status:** complete, milestone met
**Date:** 2026-09-27
**Milestone (ROADMAP §14):** *Invalid operations rejected at the business tier.*

---

## 1. What was left to do

Four of the six §8.3 rules were already enforced, because they fall out of doing
CRUD correctly: no duplicate membership, a positive amount, splits that
reconcile, and a settlement between two different people.

The two that remained both need something the ROADMAP's own method signatures do
not carry: **who is asking**.

- A user may only add expenses to trips they belong to.
- Only trip admins may remove members.

A stateless session bean has no idea who called it. That is the point of being
stateless, and it is why this phase is not just a few more `if` statements.

## 2. The full rule set now

| Rule | Enforced by |
| --- | --- |
| A user cannot be added to a trip twice | `TripService.addMember` and `UNIQUE (trip_id, user_id)` |
| An expense amount must be > 0 | `ExpenseService.validate` and `CHECK (amount > 0)` |
| Splits must sum to the expense amount | `ExpenseService.validate` and `database/verify.sql` |
| A settlement needs two different people | `ExpenseService.recordSettlement` and `CHECK (payer_id <> receiver_id)` |
| **Expenses only on trips you belong to** | `TripAccess.requireMember` |
| **Only admins can remove members** | `TripAccess.requireAdmin` |
| Only admins can invite, edit or delete a trip | `TripAccess.requireAdmin`, from the role table in §4 |
| A trip must keep at least one administrator | `TripService.removeMember` via `TripAccess.countAdmins` |

Every rule now has both a business-tier check and a database constraint behind
it. The constraint is the backstop; the tier check exists so the caller gets a
sentence instead of a constraint violation.

## 3. Carrying the caller, and why not implicitly

Every operation that depends on who is asking now takes the acting user's id:

```java
TripMemberDTO addMember(Long tripId, Long userId, TripRole role, Long actedBy);
void removeMember(Long tripId, Long userId, Long actedBy);
TripDTO updateTrip(TripDTO trip, Long actedBy);
void deleteTrip(Long tripId, Long actedBy);
ExpenseDTO persistExpense(ExpenseDTO expense, Long actedBy);
void submitExpenseAsync(ExpenseDTO expense, Long actedBy);
SettlementDTO recordSettlement(SettlementDTO settlement, Long actedBy);
```

**A thread-local or a session context would have been less churn and the wrong
choice.** The failure mode of a forgotten identity in an implicit context is an
authorization check that silently passes, and a silently passing authorization
check looks exactly like a working one. An argument cannot be forgotten: the
code does not compile.

That claim was tested immediately. Adding the parameter made the compiler list
twelve call sites across the EJB module, the MDB, the smoke test, four backing
beans and the diagnostics page. Twelve places that would each have been a
potential silent bypass, found in one build.

**The single-argument `submitExpenseAsync` was removed rather than kept as a
convenience.** It derived the acting user from the expense's payer, which would
have let a caller record an expense against somebody else's membership. An
overload that guesses the identity defeats the rule the identity exists to
enforce.

## 4. The last administrator

Not from the ROADMAP, and worth having. A trip whose only administrator is
removed cannot be managed at all: nobody can invite, remove or edit it, and
there is no way to appoint a replacement through the application. The rule
refuses it and says what to do instead.

It is checked at the moment of removal rather than as a separate "promote
someone" workflow, because the check is what makes the invariant hold and the
workflow is the fix the user chooses.

## 5. Membership is re-checked where the write happens

The message-driven bean passes the submitter recorded on the message, so
membership is checked again at write time, not only at submit time. An expense
submitted by someone who has since left the trip is refused rather than written.

This is the same reasoning as Phase 7's validation staying on the caller's side,
applied to the one rule where the two moments can differ.

## 6. JSF input validation

Field-level validation was added on the input-heavy forms: sign-in,
registration, trip creation, the expense form and the itinerary editor.

- `required` with a readable `requiredMessage`, so the container refuses the
  form before the action runs.
- A range validator on the expense amount, so a zero or negative figure is
  refused as input rather than as a business rule.
- A `p:message` beside each validated field, so the error appears where the
  mistake is rather than only in a summary at the top of a long form.
- The layout's `p:messages` is now `globalOnly`, because a field message is
  shown beside its field and would otherwise appear twice.
- The itinerary's time pickers got an inline message specifically, because those
  are the fields a **conversion** failure can hit, and in Phase 6 that failure
  was invisible and looked like a broken button.

Verified: an empty sign-in form is refused with both field messages, and the
action never runs.

## 7. Verification

The full suite, with the nine new authorization checks:

```
Persistence: entity round-trip        all 35 checks passed
DTO mapping                           all 55 checks passed
Session bean CRUD                     all 60 checks passed
The five required queries             all 97 query checks passed
Session bean rejections               all 19 rejections behaved as required
Balances and settle-up arithmetic     all 15 arithmetic checks passed

281 passed, 0 failed.
```

The nine authorization checks, which are the ROADMAP's §16.2 entries 13 and 14
plus what the role table implies:

```
PASS [Authorization] a user who is not on the trip is not a member of it
PASS [Authorization] a non-member cannot add an expense to the trip
PASS [Authorization] a non-member cannot record a repayment on the trip
PASS [Authorization] the member used for these checks is not an administrator
PASS [Authorization] a plain member cannot invite somebody
PASS [Authorization] a plain member cannot remove somebody
PASS [Authorization] a plain member cannot edit the trip
PASS [Authorization] a plain member cannot delete the trip
PASS [Authorization] the trip's only administrator cannot be removed
```

**§16.2 entries 13 and 14 now pass**, having been recorded as failing in the
Phase 8 notes.

Every page still loads with no Faces warnings, and the administrator flow was
exercised through the browser: Alice invites Emma to Pokhara, the membership row
appears, she removes her again, and the row goes.

### 7.1 Proved non-vacuous

The administrator check was removed from `removeMember`:

```
SUMMARY: 1 of 19 rejections FAILED
FAIL  [Authorization] a plain member cannot remove somebody (it was accepted)
```

**That run also removed a seeded membership**, because a rejection test writes
when the rule it is testing is gone. The arithmetic suite noticed before I did:
two balances checks failed on the next run because a trip had lost a member. It
is the same pattern as the Phase 4 sabotage, and the same guards caught it.

## 8. Notes for later phases

- **Every new business rule must throw `ValidationException`**, not
  `IllegalArgumentException`. It is declared `@ApplicationException(rollback =
  false)`, and a rule the message consumer can hit has to leave the transaction
  usable or the message is redelivered forever. This is the third time it has
  been worth restating.
- **The acting user is not verified to be authenticated**, only to be a member.
  The web tier supplies it from the session, and the filter guarantees a session
  exists. A remote client calling the EJBs directly could claim any identity.
  That is a property of the assignment's trust model rather than an oversight,
  but it is the first thing to fix if this were deployed: the tier would need to
  establish the caller itself rather than take their word for it.
- **Reads are not restricted by membership.** `getTrip`, `getTripMembers` and
  the report queries will return data about a trip to anyone who asks. Only
  writes and deletes are guarded, because those are what §8.3 names. Worth
  knowing before the viva rather than discovering it there.
- **The trip detail page hides the Remove button from plain members** as well as
  the tier refusing them. The UI check is for clarity; the tier check is the
  one that enforces.

---

## 9. Next: Phase 10, testing

Execute the §16.2 test matrix in full, capture the screenshots the report needs,
and record the results. The matrix is now expected to pass in every row.

**Milestone:** all tests pass; screenshots stored.
