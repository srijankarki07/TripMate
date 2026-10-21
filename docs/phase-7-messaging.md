# Phase 7: Messaging

**Status:** complete, milestone met
**Date:** 2026-09-27
**Milestone (ROADMAP §14):** *Expense saved via MDB, verified in logs and DB.*

---

## 1. Deliverables

| Piece | What it does |
| --- | --- |
| `scripts/glassfish-jms.sh` | Creates the connection factory, the queue and its physical destination |
| `ExpenseMessage` | The payload: the expense to write, plus who submitted it, when, and a correlation id |
| `ExpenseMessageBean` | The message-driven bean that writes it |
| `ExpenseService.submitExpenseAsync` | Now sends to the queue instead of writing |
| `ValidationException` | Refused input, declared not to roll back |
| `trips/expenses.xhtml` | Submits asynchronously and says so |

## 2. The resources

```
jms/ExpenseQueueFactory  ->  a dedicated connection factory
jms/ExpenseQueue         ->  Name = jms_ExpenseQueue
jms_ExpenseQueue         ->  the physical destination on the broker
```

A dedicated factory rather than the container's default, so the queue's
connection settings can change without affecting anything else on the server.

Two details cost time. The resource's `Name` property is the physical
destination, and creating the resource does **not** create it: the queue
resource came up pointing at a destination that did not exist, and the failure
would only have appeared on first use. And the subcommand is
`create-jmsdest --desttype`, not `--type`.

`deploy.sh` now runs the script, because the bean binds to the queue during
deployment and a missing queue fails the deployment.

## 3. The flow

```
Browser  ->  ExpenseBean.save()
         ->  ExpenseService.submitExpenseAsync(dto, userId)
               validate(dto)                        <- refuses bad input here
               jmsContext.send(jms/ExpenseQueue, new ExpenseMessage(...))
         <-  returns immediately
         ->  redirect with "your expense is being saved"

ExpenseMessageBean.onMessage(Message)
         ->  message.getBody(ExpenseMessage.class)
         ->  ExpenseService.persistExpense(dto)     <- the same method the sync path uses
         ->  one expenses row, N expense_splits rows
```

The consumer adds nothing to the write path. It exists to move the write off the
request thread, which is what ROADMAP §10.1 asks for: several rows are involved
and the browser should not be blocked on them.

**Validation stays on the caller's side.** The asynchronous route applies the
same rules before sending, in the caller's own transaction, so an expense that
could never be stored is refused while the user is still looking at the form,
rather than being put on a queue to be rejected later by a consumer with no way
to tell anyone. This is why the validation was extracted out of `write` into a
`validate` method both paths call: two copies would have drifted.

The JMS context is injected rather than built from a connection factory, so it
participates in the bean's transaction and the send commits with the caller's.
A context created by hand would be separate and would also have to be closed.

## 4. The redelivery loop

This is the part worth reading.

**The first version rejected messages forever, thousands of times a second.**

The design was right in outline: a message that can never succeed should be
logged and discarded, and a message that failed for a transient reason should be
redelivered. The code caught `IllegalArgumentException`, logged, and returned
from `onMessage` without throwing, on the reasoning that returning normally
acknowledges the message.

Two things were wrong, and neither was visible from reading the code.

**The catch never matched.** A runtime exception thrown out of a session bean
does not reach the caller as itself; the container wraps it, and what arrives at
`onMessage` is an `EJBTransactionRolledbackException` with the original several
links down. So every failure, permanent or not, took the rethrow path.

**Catching would not have been enough anyway.** A runtime exception out of a
session bean is a *system* exception to the container, which marks the caller's
transaction rollback-only. Once that has happened, returning normally from
`onMessage` still ends in a rollback, the message is never acknowledged, and it
is redelivered immediately. The consumer rejects the same message and the
container hands it straight back.

The symptom was 4,883 log lines in about five seconds, and it only stops when
the application is undeployed.

### The fix

Two changes, both needed:

1. **`ValidationException`, declared `@ApplicationException(rollback = false)`.**
   A business outcome rather than a fault. The container leaves the transaction
   usable, so the consumer can acknowledge the message it has just rejected.
   All 35 throw sites across the four services now use it. Through the web tier
   the change is invisible: each request is its own transaction and it was going
   to end anyway.
2. **Classify on the cause chain, not the exception that arrives**, because the
   container's wrapping means the top-level type is never the one that was
   thrown.

After the fix the same bad message is rejected exactly once, with its cause
logged, and does not come back.

### Why this was only findable by sending a bad message

Nothing the application does can produce one. `submitExpenseAsync` validates
before sending, so the queue only ever receives payloads that will be accepted.
A bad message had to be put on the queue from outside, which is what
`/tmp/SendBadMessage.java` did, talking to the broker directly over
`mq://localhost:7676` so it needed no naming service, no ORB and no running
client container.

The lesson is worth stating plainly: a failure-handling path that the
application cannot reach is a path that has never been run.

## 5. Verification

### 5.1 The milestone

An expense submitted through the browser form:

```
submit -> 302  Location: /tripmate/trips/expenses.xhtml?submitted=1&tripId=1

Expense written from the queue
  [correlationId=011c2980-9283-487e-9fdb-6ee3310d3c65]
  expenseId=63 tripId=1 description='Async happy path'
  amount=600.00 splits=2 submittedBy=1 submittedAt=2026-09-27T19:24:...
```

and in the database:

| expense_id | description | amount | splits | split total |
| --- | --- | --- | --- | --- |
| 63 | Async happy path | 600.00 | 2 | 600.00 |

The log line is the part that matters: it carries the correlation id, so the
submission and the write can be tied together, and it records the split count,
so a write that stored the expense but lost its splits would be visible.

### 5.2 The rejection path

A message for a trip that does not exist, sent straight to the queue:

```
Expense rejected by the business rules, discarding
  [correlationId=aec33fd7-6ac6-4395-b564-af9691083d8e]
  description='Message with no such trip'
  reason=no such trip: 999999
```

Counted before and after an eight-second wait: **one** rejection, no
redelivery. Nothing was written to the database.

### 5.3 Nothing else regressed

```
Persistence: entity round-trip   all 35 checks passed
DTO mapping                      all 55 checks passed
Session bean CRUD                all 60 checks passed
The five required queries        all 97 query checks passed
Session bean rejections          all 10 rejections behaved as required

257 passed, 0 failed.
```

`database/verify.sql` passes and the sample data is back at baseline. Two checks
did fail during this phase, both of them the "sample data is unchanged" guards,
which is exactly what they are for: they caught the test expenses before I did.

## 6. Reproducing

```bash
./scripts/deploy.sh          # now creates the JMS resources too
xdg-open http://localhost:8080/tripmate/trips/expenses.xhtml?tripId=1
```

Sign in as `alice@tripmate.test` with `tripmate123`, log an expense, then watch:

```bash
tail -f .../glassfish/domains/domain1/logs/server.log | grep "Expense written from the queue"
```

## 7. Notes for later phases

- **The queue names live in `scripts/env.sh`** and are also written into the
  bean's `@ActivationConfigProperty` and the `@Resource` lookup, which are
  compile-time constants and cannot read the script. Changing the name means
  changing three places.
- **`ValidationException` is now the contract for refused input** across all
  five services. Phase 9's business rules should throw it rather than
  `IllegalArgumentException`, or they will mark transactions rollback-only and
  reintroduce the loop for any rule that a consumer can hit.
- **The consumer discards anything it classifies as permanently unprocessable.** There
  is no dead-letter handling and no record of a discarded message beyond the log
  line. For the assignment that is proportionate; a real deployment would want
  the rejected messages kept somewhere a person can look at them.
- **The expense form tells the user the write is asynchronous** rather than
  waiting for it. Phase 8 and the viva demo should refresh the page rather than
  assume the expense is already listed.
- **The message payload uses Java serialisation**, which is safe here because
  the only producer and consumer are both this application, from the same EAR.
  It would not be safe for a queue anything outside the application could write
  to.

---

## 8. Next: Phase 8, complex UI features

The balances tab, the category spending chart and the settle-up flow, already
built in Phase 6 as part of the page work. What remains is to check them against
the ROADMAP's §16.2 test matrix and capture the demonstration flow end to end.

**Milestone:** both complex queries visible in the UI.
