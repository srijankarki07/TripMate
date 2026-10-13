# TripMate

**Group Trip Planning & Expense Tracking System** — a distributed enterprise web
application built on Jakarta EE 10.

Groups of friends, families, and colleagues travel together and immediately hit
the same coordination problems: nobody remembers who paid for what, splitting
costs becomes an argument, settling debts is awkward, and the itinerary is
scattered across chat threads and notes apps. TripMate puts the plan and the
money in one place for a small, trusted group of travellers.

## Stack

| Layer | Technology |
| --- | --- |
| Presentation | Jakarta Server Faces (Faces 4.0) + XHTML, CDI backing beans |
| Business | Stateless session EJBs with `@Remote` interfaces, message-driven bean |
| Persistence | Jakarta Persistence (JPA 3.1) entities + JPQL |
| Messaging | Jakarta Messaging (JMS) queue → MDB |
| Data | PostgreSQL |
| Runtime | Eclipse GlassFish 7.0.18 (Jakarta EE 10 full profile) |
| Build | Maven multi-module |

## Prerequisites

| Tool | Version | Notes |
| --- | --- | --- |
| JDK | 17 | GlassFish 7 requires 17+ |
| Maven | 3.9+ | |
| PostgreSQL | 14+ | Database is created in Phase 1 |
| GlassFish | 7.0.x | Jakarta EE 10 full profile |

## Module layout

```
TripMate/
├── pom.xml              Parent POM — dependency and plugin management
├── tripmate-ejb/        Entities, DTOs, @Remote interfaces, session beans, MDB
├── tripmate-web/        JSF views, CDI backing beans, web resources
├── tripmate-ear/        Packages the EJB JAR and WAR into a deployable EAR
├── database/            schema.sql, seed.sql, reset.sql
├── scripts/             build / deploy / undeploy helpers
└── docs/                Design report, serverless report, diagrams, screenshots
```

## Build

```bash
mvn clean package
```

The deployable artifact is produced at:

```
tripmate-ear/target/tripmate-ear-1.0.0-SNAPSHOT.ear
```

## Deploy

```bash
# Start the domain
asadmin start-domain

# Deploy
asadmin deploy --force=true tripmate-ear/target/tripmate-ear-1.0.0-SNAPSHOT.ear
```

Then open <http://localhost:8080/tripmate/>.

`scripts/deploy.sh` wraps both steps; see `scripts/` for the full set.

## Documentation

| File | Contents |
| --- | --- |
| `ROADMAP.md` | Full project plan: schema, EJBs, queries, phases, assignment compliance matrix |
| `DESIGN.md` | Design tokens (colour, typography, spacing, radii, components) governing the web tier |
| `docs/phase-0-setup.md` | Environment verification and Phase 0 milestone evidence |
| `docs/report.md` | Part Two — design report *(later phase)* |
| `docs/serverless-report.md` | Part One — serverless evaluation *(later phase)* |

## Development workflow

Work is split into stacked feature branches — each branch is cut from the
previous one, so a branch's history contains everything it depends on.

```
main
 └── feat/phase0-scaffold
      └── feat/phase0-ejb-module
           └── feat/phase0-web-module
                └── feat/phase0-ear-module
                     └── feat/phase0-design-tokens
```

Branch naming: `feat/phase<N>-<feature>`. Commit messages are imperative with a
short bullet list of what changed.
