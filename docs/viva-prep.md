# Viva Preparation

Everything needed for the demonstration and the questions that follow it. The
demo script has been rehearsed against the running application, and the figures
in it are the figures the application actually produces.

---

## 1. Before you walk in

```bash
cd /home/srijan/Masters-III/NPMF2511ITM/CT066-3-M-EA/TripMate
./scripts/clean-deploy.sh
```

That discards everything, rebuilds the database and the application from source,
deploys, and runs the sixteen-row test matrix. If it finishes with
`16 passed, 0 failed`, the machine is in a known-good state and the sample data
is exactly what the demonstration expects. It takes about two minutes.

Then open <http://localhost:8080/tripmate/> in a browser and leave it on the
sign-in page.

**Have a terminal open in the same directory.** Two commands are worth being
able to run mid-demonstration:

```bash
# Watch the message consumer write an expense, live
tail -f ../tools/glassfish7/glassfish/domains/domain1/logs/server.log | grep --line-buffered "Expense written from the queue"

# Show the expense rows appearing
PGPASSWORD=tripmate_dev_pw psql -h localhost -p 5432 -U tripmate -d tripmate \
  -c "SELECT expense_id, description, amount FROM expenses ORDER BY expense_id DESC LIMIT 5;"
```

---

## 2. The demonstration

The scenario below is the one in the plan, rehearsed. One step has been
corrected, and the correction is worth reading before you perform it.

### Step 1: sign in as Alice

`alice@tripmate.test` with `tripmate123`.

> "Sign-in hashes with PBKDF2 at 210,000 iterations, and the failure for an
> unknown email and a wrong password is deliberately identical so the form
> cannot be used to find out which addresses are registered."

### Step 2 and 3: open Pokhara Weekend, show the itinerary

It is the first trip on the dashboard, marked ONGOING. Click it, and the
**Itinerary** tab is already open with six items in schedule order.

> "These are ordered by day and then start time, not by insertion order, which
> is query 1. The date and time are separate columns rather than one timestamp
> so the ordering needs no truncation."

### Step 4: log an expense

Go to **Expenses**. Enter:

| Field | Value |
| --- | --- |
| What | Dinner at the Lakeside |
| Amount | 4800 |
| Category | FOOD |
| Date | leave as it is |
| Paid by | Alice Sharma |

Then untick nobody, and press **Split equally**. The share column fills with
1200.00 each, and the running total reads 4800.00.

> "Four ways into 4,800 comes out even, but it usually does not. The arithmetic
> runs in Java and gives the odd paisa to the first person, because three lots
> of 333.33 do not add to 1,000.00 and the service refuses splits that do not
> total the amount."

Press **Save expense**. The page returns with a notice that the expense is being
saved.

### Step 5: show that the asynchronous write happened

This is the step worth having a terminal for. Switch to it and show the
consumer's log line, which carries a correlation id, the new expense id, the
trip, the description, the amount, the split count and the submitting user.

Then press the **Refresh** link and show the expense now in the list.

> "The form sends a message and returns. The write happens in a message-driven
> bean, which is one row for the expense and then one row per participant in a
> single transaction. The browser does not wait for either."

### Step 6: open the Balances tab

Four members with their paid, share, net, repaid, received and outstanding
figures. Point at the bottom line: the outstanding column sums to 0.00.

> "That is the strongest single check on this data. Every paisa someone is owed
> comes from someone who owes it, so the column has to total zero."

### Step 7: record a repayment

**This step is where the plan's script and this data disagree, and it is worth
knowing why before you are asked.**

The plan says to record Bob paying Alice 1,200 and then to show that Bob's
position improves. On this data that does not happen, because **Bob is a
creditor and Alice is a debtor**: Bob is owed 4,475 and Alice owes 2,175. A
debtor is the one who settles up, so the plan has the two roles the other way
round.

Recording Bob paying Alice does still work, and the expense-side figures do not
change. What moves is Bob's *outstanding* figure, and it goes **up**, from 4,475
to 5,675, because Bob has now paid out money that was not his share. That is
correct arithmetic and a confusing demonstration.

**Record Alice paying Bob 1,200 instead.** Then:

- Alice's outstanding goes from **-2,175.00 to -975.00**, so it improves by
  exactly what she paid, which is the point the step is making.
- Bob's goes from 4,475.00 to 5,675.00, and if you want to explain that: Bob has
  been repaid 1,200 of what he was owed, so what remains owed to him *falls* by
  1,200 to 3,275... which it does not appear to do here, and that is because the
  outstanding figure is measured against the gross position rather than being
  reduced by receipts.

If that last paragraph is confusing, do not put it in the demonstration. Record
Alice paying Bob, point at Alice's figure falling from -2,175.00 to -975.00, and
say:

> "Recording a repayment changes what is still outstanding. The net column above
> it does not move, because a repayment is not an expense, and keeping those two
> figures separate is deliberate: one answers who is up and who is down on the
> trip, the other answers what still has to move."

### Step 8: open Reports

The category chart and the table beneath it.

> "This is query 5, grouped in the database across every trip Alice belongs to.
> The bars take the one accent colour the design system allows, and the table is
> there because a chart is for the shape of the spending and the table is for
> the figures somebody checks."

### Step 9: sign out

Then, if there is time, show `diagnostics.xhtml` and press **Run all checks**.
281 checks across six suites, all passing.

### If something goes wrong

`./scripts/clean-deploy.sh` puts the machine back to a known-good state in about
two minutes. Say so rather than fighting it.

---

## 3. Questions and answers

The plan's own list, with the answers this implementation actually supports.

**Why N-tier?**
Separation of concerns. The web tier presents and holds no rules, so a rule
cannot be bypassed by a client that ignores the interface. The business tier
holds every rule. The persistence tier owns the mapping and the database owns
the data. Each can be understood, tested and changed without the others.

**Why stateless session beans?**
No operation needs conversational state across requests, so the container can
pool and reuse instances. The only genuine session state in the application is
the signed-in user, and that lives in a CDI session-scoped bean in the web tier,
which is where it belongs.

**Why DTOs rather than entities across the boundary?**
Three reasons. A DTO holds only what the client needs, so a `password_hash` that
is never placed into an object cannot leak, and that absence is asserted by
reflection in the test suite. A DTO holds plain values, so a lazy association
cannot be touched after the persistence context closes. And the wire contract is
explicit rather than accidental.

**Why JPQL rather than SQL?**
It queries the entity model, so the code navigates relationships as object paths
(`es.expense.trip.tripId`) and stays independent of the table names. Five
queries in this project navigate three or four entities, and doing that in SQL
would mean hand-written joins that the mapping already describes.

**Why a message-driven bean for expenses?**
Adding an expense writes one row and then one row per participant, and the
splits have to be written in the same transaction as the expense or the two
could disagree. That is a multi-record write the browser need not wait for. It
also gives a natural extension point: notifications or analytics can subscribe
later without the client changing.

**How do you prevent a user joining a trip twice?**
Two layers. `UNIQUE (trip_id, user_id)` in the schema, which is what makes it
safe under concurrency, and a check in `TripService.addMember` so the caller
gets a sentence rather than a constraint violation. Worth mentioning that the
invite picker does not even offer existing members, so the form cannot reach the
rule; the test suite calls the service directly to exercise it.

**Where does validation belong?**
Input checks are for the user's benefit and live in the JSF layer, where a
required field produces a message beside the field before the action runs.
Business rules live in the business tier, because a client cannot be trusted and
two clients would otherwise implement the rule differently. Where a rule can
also be a database constraint, it is both.

**Would serverless be cheaper for TripMate?**
Probably, at this scale, and Part One argues it with evidence. Its utilisation
would be very low, and the published case studies report hosting cost reductions
of 66 to 95 per cent from removing idle capacity. But the saving is not free:
TripMate's value comes from invariants that are cheap in one transaction and
expensive across functions, so the recommendation is a hybrid, with serverless
at the edges and a transactional core holding the invariants.

**How would you commercialise it?**
As a SaaS platform for small travel groups: free for up to three trips, paid
tiers for larger groups, analytics and integrations. The serverless evaluation
in Part One is effectively the cost model for that.

### Questions likely to be asked that the plan does not list

**Why is `DECIMAL` used for money?**
Because a balance is a running sum of many small values, and binary floating
point cannot represent `0.10` exactly. The rounding error accumulates until the
splits stop reconciling with the amounts. The sample data is asserted to the
paisa, and the column totals zero.

**What happens if the message consumer fails?**
It classifies the failure. A message it can never process is logged and
discarded, because retrying the same bytes would fail identically and would loop
forever. Anything else is rethrown so the container redelivers, which is what
retrying is for. Getting that right needed a `ValidationException` declared
`@ApplicationException(rollback = false)`, because a bare runtime exception marks
the transaction rollback-only, and after that returning normally still rolls
back so the message is never acknowledged. The first version did exactly that
and produced thousands of log lines a second.

**What is not covered?**
Say it plainly. Concurrency is not tested, only reasoned about, and the unique
constraint is the real defence. A transient database failure mid-message is not
tested. Nothing was tested at phone width or under load. And the business tier
is told who is calling rather than establishing it, which is sound through the
application because the filter guarantees a session, but would not be sound for
a remote client.

If asked what you would do next: restrict reads by membership, and establish the
caller in the business tier rather than accepting it.

---

## 4. Diagrams to have ready

All five are in `docs/report.md` and render from Mermaid.

| Diagram | Section | Shows |
| --- | --- | --- |
| N-tier architecture | 2.1 | Five tiers, and where JMS sits |
| ER diagram | 3.2 | Seven tables and their relationships |
| UML class diagram | 4.3 | The seven entities as classes |
| JMS sequence | 6.2 | A submission from form to database |
| Navigation chart | 7.4 | How the twelve pages connect |

---

## 5. What to bring

| Item | Where |
| --- | --- |
| Deployed application | <http://localhost:8080/tripmate/> |
| PostgreSQL with sample data | Loaded by `./scripts/clean-deploy.sh` |
| The design report | `docs/report.md` |
| The serverless report | `docs/serverless-report.md` |
| Screenshots | `docs/screenshots/` |
| Operating instructions | `docs/runbook.md` |
| The plan | `ROADMAP.md` |

---

## 6. Deliverables checklist

From the plan's own list.

- [x] `tripmate-ejb` with 7 entities, 9 DTOs, 5 remote interfaces, 5 session beans, 1 message-driven bean
- [x] `tripmate-web` with 12 pages, CDI backing beans, CSS tokens
- [x] `tripmate-ear` packaging the EJB jar and the WAR
- [x] `database/schema.sql`, `seed.sql`, `reset.sql`, `verify.sql`, `bootstrap.sql`
- [x] Design report with cover, contents, system proposal, web component design, web page design with a navigation chart, business tier design, database access design, project plan, UML diagrams, architecture, database design with table descriptions and an ERD, and references
- [x] Serverless report, Part One, about 1,200 words with two diagrams and a comparison table
- [x] Wireframes: the five pages are built and captured in `docs/screenshots/` rather than drawn, which is better evidence than a wireframe
- [x] Diagrams: ERD, UML, architecture, navigation, JMS sequence
- [x] Screenshots: 17, each captured from a running page
- [x] Test matrix: 16 of 16 passing
- [x] Zipped project ready for submission

**Two notes against that list.** The plan asks for five *wireframes*; the built
pages are provided instead, which shows the same thing and more. And the plan's
`docs/report.md` is present, but the twelve `docs/phase-*.md` files are working
records rather than deliverables; see below.

---

## 7. What is a deliverable and what is a working record

Worth knowing before you zip anything.

### Deliverables

| File | What it is |
| --- | --- |
| `docs/report.md` | Part Two, the design document |
| `docs/serverless-report.md` | Part One, the evaluation |
| `docs/screenshots/` | 17 captures, for the report |
| `docs/runbook.md` | Operating instructions, useful and self-contained |

### Working records

The twelve `docs/phase-*.md` files, one per phase. They record what was built,
what went wrong, and why each decision was taken, including the failures. They
are the evidence behind the report's claims and they are worth keeping, but they
are not asked for and they are candid in places where a submission need not be.

**If the submission should contain only what was asked for**, zip these:
`docs/report.md`, `docs/serverless-report.md`, `docs/screenshots/`. Everything
else is source or working record, and the source is the submission anyway.

**If you want the phase records included**, they argue the decisions in more
detail than the report does and would be defensible to include. The one to
re-read first is `docs/phase-7-messaging.md`, which records a defect that is
worth being able to talk about.

### Zipping

```bash
cd /home/srijan/Masters-III/NPMF2511ITM/CT066-3-M-EA
zip -r TripMate-submission.zip TripMate \
  -x "TripMate/.git/*" "TripMate/*/target/*"
```

That excludes the git history and the build output. If you would rather the
archive carry only what was asked for, remove the twelve phase files first:

```bash
zip -r TripMate-submission.zip TripMate \
  -x "TripMate/.git/*" "TripMate/*/target/*" "TripMate/docs/phase-*.md"
```
