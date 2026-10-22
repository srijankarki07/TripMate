# TripMate Runbook

Everything needed to build, run, test and debug TripMate from a machine that
already has the toolchain installed. This is the operational companion to
`ROADMAP.md`, which is the plan, and to `docs/phase-*.md`, which are the records
of what was built and why.

The commands below are the ones that were actually run during development, with
the real paths for this machine.

---

## 1. The toolchain on this machine

| Tool | Version | Where |
| --- | --- | --- |
| JDK | OpenJDK **17.0.20.1** | `/usr/lib/jvm/java-17-openjdk-amd64` |
| Maven | Apache Maven **3.9.12** | `/usr/share/maven` |
| PostgreSQL | **18.6** | cluster `18/main` on port 5432 |
| GlassFish | Eclipse GlassFish **7.0.18** | `../tools/glassfish7` (a sibling of this repo) |
| Messaging | Open MQ, bundled inside GlassFish | broker on port 7676 |

**Two things about this machine are not standard, and both cost time if
forgotten.**

`JAVA_HOME` is not exported by the shell profile, and `asadmin` is not on
`PATH`. Every command that needs either has to be given them explicitly, or run
through the scripts in `scripts/`, which resolve both.

```bash
# The long form, if you ever need to run asadmin directly
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
/home/srijan/Masters-III/NPMF2511ITM/CT066-3-M-EA/tools/glassfish7/bin/asadmin version
```

`sudo` requires a password, so anything touching system services, or the
PostgreSQL superuser, has to be run by you rather than by a script.

---

## 2. First-time setup

Three things need to exist before the application can deploy: a database with a
role, a datasource, and a message queue. Each is a one-off.

### 2.1 The database role and database

Needs the PostgreSQL superuser, so run it yourself. It creates a `tripmate` role
and a `tripmate` database, and is safe to re-run.

```bash
sudo -u postgres psql -v ON_ERROR_STOP=1 -f database/bootstrap.sql
```

To use a different password:

```bash
sudo -u postgres psql -v ON_ERROR_STOP=1 \
     -v tripmate_password='something-else' -f database/bootstrap.sql
```

If you change the password, export `TM_DB_PASSWORD` to match before running
anything else, or edit it in `scripts/env.sh`.

### 2.2 Schema and sample data

```bash
./database/load.sh
```

That runs four steps in order and stops if any of them fails:

| Step | File | What it does |
| --- | --- | --- |
| 1 | `database/reset.sql` | Drops all seven tables |
| 2 | `database/schema.sql` | Creates them, with constraints and indexes |
| 3 | `database/seed.sql` | Loads the sample data |
| 4 | `database/verify.sql` | Asserts the data's integrity rules |

**This is destructive.** It drops everything in the seven TripMate tables. That
is the point, and it is why the script is separate from deployment.

### 2.3 The datasource and the queue

Both are created on GlassFish, both are idempotent, and `deploy.sh` runs both
automatically. Run them by hand only if you want to see what they do.

```bash
./scripts/glassfish-setup.sh   # JDBC connection pool + jdbc/tripmate
./scripts/glassfish-jms.sh     # jms/ExpenseQueue + its connection factory
```

**The PostgreSQL JDBC driver must be in the container's lib directory**, not in
the EAR. A driver loaded by a web application's classloader cannot be shared by
the connection pool. It is already there:

```bash
ls ../tools/glassfish7/glassfish/domains/domain1/lib/
# postgresql-42.7.3.jar
```

If it is ever missing:

```bash
mvn dependency:get -Dartifact=org.postgresql:postgresql:42.7.4
cp ~/.m2/repository/org/postgresql/postgresql/42.7.4/postgresql-42.7.4.jar \
   ../tools/glassfish7/glassfish/domains/domain1/lib/
```

---

## 3. Everyday commands

### 3.1 Build

```bash
mvn clean package
# or
./scripts/build.sh
```

The deployable artifact is:

```
tripmate-ear/target/tripmate-ear-1.0.0-SNAPSHOT.ear
```

### 3.2 Deploy

```bash
./scripts/deploy.sh
```

That builds, makes sure the datasource and queue exist, starts the domain if it
is not running, and deploys. It forces redeployment, so it is safe to run
repeatedly.

The long form, if you want the steps separately:

```bash
mvn clean package
./scripts/glassfish-setup.sh
./scripts/glassfish-jms.sh
./scripts/domain.sh start
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
../tools/glassfish7/bin/asadmin deploy --force=true --name=tripmate \
    tripmate-ear/target/tripmate-ear-1.0.0-SNAPSHOT.ear
```

### 3.3 Open it

<http://localhost:8080/tripmate/>

Sign in as `alice@tripmate.test` with `tripmate123`. Every seeded user has that
same password, so you can sign in as Bob, Carol, David or Emma too, which is
useful for seeing what a plain member sees.

### 3.4 The admin console

<http://localhost:4848> is GlassFish's own console, useful for inspecting the
connection pool and the queue.

### 3.5 Stop and start the domain

```bash
./scripts/domain.sh start
./scripts/domain.sh stop
./scripts/domain.sh status
```

### 3.6 Remove the application without stopping the server

```bash
./scripts/undeploy.sh
```

Useful when a redeploy misbehaves and you want the application fully gone before
putting it back.

---

## 4. Testing

### 4.1 The ROADMAP test matrix

```bash
./scripts/test-matrix.sh
```

Runs all sixteen rows of the §16.2 matrix through the real user interface and
checks the effect in PostgreSQL. It creates its own trip and user and removes
them afterwards, so it leaves the sample data exactly as it found it and can be
run immediately before a demonstration.

It takes about a minute. Output looks like:

```
 1. Register a new user                      PASS  user row created, hash stored
 ...
16. Cold deploy on a clean database          PASS  schema, sample data and integrity checks pass from empty

  16 passed, 0 failed
```

If it fails partway, the leftovers are named `Matrix ...` and
`matrix-...@tripmate.test`, so they are easy to find and delete.

### 4.2 The diagnostics page

<http://localhost:8080/tripmate/diagnostics.xhtml>

Six suites, 281 checks, run in the browser: entity round-trip, DTO mapping,
session bean CRUD, the five required queries, the rejection rules, and the
balances arithmetic. Click **Run all checks**.

### 4.3 Verifying the data directly

```bash
PGPASSWORD=tripmate_dev_pw psql -h localhost -p 5432 -U tripmate -d tripmate \
  -f database/verify.sql
```

Expect `ALL INTEGRITY CHECKS PASSED`.

---

## 5. Database commands worth knowing

All of these connect as the `tripmate` role, which owns its own database and
holds no superuser rights.

Set up a shorthand first, so the rest read cleanly:

```bash
tm() { PGPASSWORD=tripmate_dev_pw psql -h localhost -p 5432 -U tripmate -d tripmate "$@"; }
```

### 5.1 Row counts

```bash
tm -c "SELECT 'users' t, count(*) FROM users
       UNION ALL SELECT 'trips', count(*) FROM trips
       UNION ALL SELECT 'trip_members', count(*) FROM trip_members
       UNION ALL SELECT 'itinerary_items', count(*) FROM itinerary_items
       UNION ALL SELECT 'expenses', count(*) FROM expenses
       UNION ALL SELECT 'expense_splits', count(*) FROM expense_splits
       UNION ALL SELECT 'settlements', count(*) FROM settlements
       ORDER BY 1;"
```

Baseline is 5, 3, 12, 15, 25, 100, 6.

### 5.2 Confirm the splits reconcile

The rule everything else depends on: every expense's splits must sum to its
amount. This query should return no rows.

```bash
tm -c "
SELECT e.expense_id, e.amount, SUM(s.amount_owed) AS split_total
FROM expenses e JOIN expense_splits s ON s.expense_id = e.expense_id
GROUP BY e.expense_id, e.amount
HAVING SUM(s.amount_owed) <> e.amount;"
```

### 5.3 Complex query 4, as raw SQL

Net balance per member of a trip. This is the same question the balances page
asks, written in SQL so it can be compared against what the page shows.

```bash
tm -c "
SELECT u.full_name,
       COALESCE((SELECT SUM(e.amount) FROM expenses e
                 WHERE e.paid_by = u.user_id AND e.trip_id = 1), 0) AS paid,
       COALESCE((SELECT SUM(s.amount_owed) FROM expense_splits s
                 JOIN expenses e2 ON e2.expense_id = s.expense_id
                 WHERE s.user_id = u.user_id AND e2.trip_id = 1), 0) AS owed
FROM trip_members tm JOIN users u ON u.user_id = tm.user_id
WHERE tm.trip_id = 1 ORDER BY u.full_name;"
```

### 5.4 Complex query 5, as raw SQL

Spending by category for one user.

```bash
tm -c "
SELECT e.category, SUM(s.amount_owed) AS total, COUNT(DISTINCT e.trip_id) AS trips
FROM expense_splits s
  JOIN expenses e ON e.expense_id = s.expense_id
  JOIN trips t ON t.trip_id = e.trip_id
  JOIN trip_members tm ON tm.trip_id = t.trip_id
WHERE tm.user_id = 1
GROUP BY e.category ORDER BY 2 DESC;"
```

### 5.5 Reset everything

```bash
./database/load.sh
```

### 5.6 Back up and restore

```bash
pg_dump -h localhost -U tripmate tripmate > /tmp/tripmate-backup.sql
psql -h localhost -U tripmate -d tripmate -f /tmp/tripmate-backup.sql
```

---

## 6. GlassFish and messaging commands

Set up a shorthand:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
as() { ../tools/glassfish7/bin/asadmin "$@"; }
```

### 6.1 What is deployed and what exists

```bash
as list-applications
as list-jdbc-connection-pools
as list-jdbc-resources
as list-jms-resources
as list-jmsdest --desttype queue
```

Expect to see `tripmate <ear, ejb, web>` deployed, `tripmatePool`, `jdbc/tripmate`,
`jms/ExpenseQueue`, `jms/ExpenseQueueFactory` and the physical destination
`jms_ExpenseQueue`.

### 6.2 Is the datasource reachable?

```bash
as ping-connection-pool tripmatePool
```

This is the fastest way to tell whether a deployment failure is a database
problem.

### 6.3 The server log

```bash
tail -f ../tools/glassfish7/glassfish/domains/domain1/logs/server.log
```

Two lines are worth knowing how to find:

```bash
# Every expense the message consumer has written, with its correlation id
grep "Expense written from the queue" \
     ../tools/glassfish7/glassfish/domains/domain1/logs/server.log | tail -5

# Anything the consumer refused, and why
grep "Expense rejected by the business rules" \
     ../tools/glassfish7/glassfish/domains/domain1/logs/server.log | tail -5
```

### 6.4 Sending a message by hand

Useful for exercising the consumer without going through the browser. This
reaches the broker directly, so it needs no naming service and no running client
container.

```bash
GF=../tools/glassfish7
CP="$GF/mq/lib/imq.jar:$GF/mq/lib/imqutil.jar:$GF/glassfish/lib/gf-client.jar:tripmate-ejb/target/classes"
javac -cp "$CP" -d /tmp/badclient /tmp/SendBadMessage.java
java -cp "$CP:/tmp/badclient" SendBadMessage
```

### 6.5 Inspecting the broker

```bash
../tools/glassfish7/mq/bin/imqcmd query dst -t q -n jms_ExpenseQueue
```

`imqcmd` will not accept `-p` for the password on this version; use
`-passfile` if you need to authenticate.

### 6.6 When a message is stuck in a redelivery loop

Symptom: the server log grows by thousands of lines a second. Undeploying stops
it immediately, because the consumer stops.

```bash
./scripts/undeploy.sh     # stops the loop
./scripts/deploy.sh       # puts it back
```

---

## 7. Driving the application from the command line

The browser is the normal way, but curl is how the automated checks work and it
is useful for reproducing a bug precisely. Two things make it awkward, and both
have helpers.

**PrimeFaces generates ids like `j_idt7:j_idt14`, and they shift whenever a
sibling component is added.** Never hardcode them.

**A JSF POST needs the form's own id, the button's field name, and the view
state**, or the action silently does not run and the page simply redisplays.

### 7.1 Sign in and keep the session

```bash
/tmp/tm-login.sh /tmp/session.jar
```

The helper derives the field and button names from the rendered page, so it does
not break when the form changes. Once you have the cookie jar:

```bash
curl -s -b /tmp/session.jar http://localhost:8080/tripmate/dashboard.xhtml
```

### 7.2 The shape of a form POST

```bash
PAGE=$(curl -s -b /tmp/session.jar -c /tmp/session.jar "$URL")
FORM=$(printf '%s' "$PAGE" | grep -oE 'name="[^"]*:someField"' | head -1 | sed -E 's/name="([^"]*):someField"/\1/')
VS=$(printf '%s' "$PAGE" | grep -oE 'name="jakarta.faces.ViewState"[^>]*value="[^"]*"' | head -1 | sed -E 's/.*value="([^"]*)".*/\1/')

curl -s -b /tmp/session.jar -c /tmp/session.jar \
  --data-urlencode "${FORM}=${FORM}" \
  --data-urlencode "${FORM}:someField=value" \
  --data-urlencode "${FORM}:theButton=label" \
  --data-urlencode "jakarta.faces.ViewState=${VS}" \
  "$URL"
```

### 7.3 A trap worth knowing

With server-side view state, a view state goes stale quickly. If you fetch a
page, run other commands, and only then POST, you get
`ViewExpiredException` and the page 302s to sign-in. **Fetch and post in the
same command.** This looks exactly like a bug in the application and is not one.

---

## 8. Troubleshooting

The failures that actually happened, and what each one means.

| Symptom | Cause | Fix |
| --- | --- | --- |
| `NoClassDefFoundError: javax/servlet/ServletRequestListener` at deploy | PrimeFaces' default artifact is built against javax | Use the `jakarta` classifier, as the POM already does |
| Deploy fails naming a missing resource | The datasource or queue does not exist | `./scripts/glassfish-setup.sh` and `./scripts/glassfish-jms.sh` |
| `ping-connection-pool` fails | Wrong credentials, or the database is missing | Re-run `database/bootstrap.sql`, check `scripts/env.sh` |
| A button does nothing, page just redraws, no error in the log | A conversion or validation failure skips the action | Check for a `p:messages` on the page; the field-level message says which field |
| `ViewExpiredException` | A stale view state, usually from a scripted POST | Fetch and post in one command |
| "Could not resolve NavigationCase for outcome: X" | A relative outcome resolved against the current directory | Navigation outcomes must be absolute view ids |
| A 500 with "The string `--` is not permitted within comments" | An XML comment contains a double hyphen | Use equals signs in comment rules |
| The chart is blank but the table below it is fine | `p:chart` in PrimeFaces 14 takes a raw JSON `value`, not a model | Pass the Chart.js config as JSON |
| The log grows by thousands of lines a second | A message is being redelivered in a loop | Undeploy; check the consumer is throwing `ValidationException` |
| Buttons are the theme's blue rather than Rausch | An override lost to the theme's `body .ui-button` specificity | Prefix overrides with `body` |

---

## 9. Environment variables

Everything the scripts read, all with sensible defaults, all overridable.

| Variable | Default | Used for |
| --- | --- | --- |
| `JAVA_HOME` | resolved from a candidate list | GlassFish needs JDK 17 |
| `GLASSFISH_HOME` | `../tools/glassfish7` | Where GlassFish lives |
| `TM_DOMAIN` | `domain1` | Which domain to configure |
| `TM_DB_NAME` | `tripmate` | Database name |
| `TM_DB_USER` | `tripmate` | Database role |
| `TM_DB_PASSWORD` | `tripmate_dev_pw` | Development credential |
| `TM_DB_HOST` | `localhost` | |
| `TM_DB_PORT` | `5432` | |
| `TM_POOL_NAME` | `tripmatePool` | GlassFish connection pool |
| `TM_JNDI_NAME` | `jdbc/tripmate` | Datasource JNDI name |
| `TM_JMS_QUEUE` | `jms/ExpenseQueue` | Queue JNDI name |
| `TM_JMS_FACTORY` | `jms/ExpenseQueueFactory` | Connection factory JNDI name |
| `TM_JMS_PHYSICAL` | `jms_ExpenseQueue` | Physical destination on the broker |

`scripts/env.sh` resolves the JDK and GlassFish paths by probing a list of
candidates, so the scripts work even though neither is on `PATH`.

---

## 10. Starting from absolutely nothing

The whole sequence, in order, on a machine with the toolchain installed.

```bash
cd /home/srijan/Masters-III/NPMF2511ITM/CT066-3-M-EA/TripMate

# 1. Database role and database (needs your password)
sudo -u postgres psql -v ON_ERROR_STOP=1 -f database/bootstrap.sql

# 2. Schema, sample data, integrity checks
./database/load.sh

# 3. Build, create the datasource and queue, deploy
./scripts/deploy.sh

# 4. Check it is up
./scripts/test-matrix.sh

# 5. Open it
xdg-open http://localhost:8080/tripmate/
```

Sign in as `alice@tripmate.test` with `tripmate123`.

Every subsequent code change is:

```bash
./scripts/deploy.sh
```

---

## 11. Where things are

```
TripMate/
├── DESIGN.md                  Design tokens governing the web tier
├── ROADMAP.md                 The plan, phase by phase
├── README.md                  Short orientation
├── pom.xml                    Parent POM
├── tripmate-ejb/              Entities, DTOs, remote interfaces, session beans, MDB
├── tripmate-web/              JSF pages, CDI backing beans, CSS
├── tripmate-ear/              Packages the EJB jar and WAR into an EAR
├── database/
│   ├── bootstrap.sql          Role and database, run once as superuser
│   ├── schema.sql             Seven tables, constraints, indexes
│   ├── seed.sql               Sample data
│   ├── reset.sql              Drops the seven tables
│   ├── verify.sql             Asserts the integrity rules
│   └── load.sh                reset, schema, seed, verify
├── scripts/
│   ├── env.sh                 Resolves JAVA_HOME, GlassFish, database settings
│   ├── build.sh               mvn clean package
│   ├── deploy.sh              build, configure, deploy
│   ├── undeploy.sh            Remove the application
│   ├── domain.sh              start, stop, status
│   ├── glassfish-setup.sh     JDBC pool and datasource
│   ├── glassfish-jms.sh       Queue, factory, physical destination
│   └── test-matrix.sh         The ROADMAP section 16.2 matrix
└── docs/
    ├── phase-*.md             What each phase built and why
    ├── report.md              Part Two design report
    ├── serverless-report.md   Part One evaluation
    ├── screenshots/           Captured from the running application
    └── runbook.md             This file
```
