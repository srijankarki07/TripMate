# Phase 0 — Environment Setup

**Status:** complete — milestone met
**Date:** 2026-09-27
**Milestone (ROADMAP §14):** *Empty EAR runs on GlassFish.*

---

## 1. Verified toolchain

Every version below was read from the installed binary, not assumed.

| Tool | Version | Path / notes |
| --- | --- | --- |
| JDK | OpenJDK **17.0.20.1** | `/usr/lib/jvm/java-17-openjdk-amd64` |
| Maven | Apache Maven **3.9.12** | `/usr/share/maven` |
| PostgreSQL | **18.6** | cluster `18/main`, port 5432 |
| GlassFish | Eclipse GlassFish **7.0.18** | `../../tools/glassfish7` (sibling of this repo) |
| Editor | VS Code | `/usr/bin/code` |

**Why these versions are coupled.** GlassFish 7.x implements the Jakarta EE 10
full profile, which requires JDK 17 or later. Jakarta EE 10 is therefore the
platform level for every descriptor and POM in this project, and the parent POM
pins `maven.compiler.release` to 17 and `jakarta.platform:jakarta.jakartaee-api`
to 10.0.0. Upgrading GlassFish to 8.x would move the target to Jakarta EE 11 and
require revisiting the descriptors.

---

## 2. Deviations from the ROADMAP

| ROADMAP assumption | Actual | Effect |
| --- | --- | --- |
| IntelliJ or NetBeans | VS Code | None on the build — Maven is driven from the CLI. `.vscode/settings.json` and `extensions.json` configure Java/Maven/XML support. |
| GlassFish under a conventional path with `asadmin` on `PATH` | GlassFish lives beside this repo; `asadmin` is **not** on `PATH` | `scripts/env.sh` resolves the install by probing candidate paths and exports `ASADMIN` and `JAVA_HOME`. |
| `JAVA_HOME` set by the shell profile | `JAVA_HOME` was empty | `scripts/env.sh` pins JDK 17 explicitly. |

`sudo` requires a password on this machine, so starting the PostgreSQL service
and any system-level install are manual steps performed by the operator.

---

## 3. What Phase 0 built

A Maven multi-module skeleton matching ROADMAP §13 that proves all four tiers
are wired, before any domain code exists.

```
TripMate/
├── pom.xml                 Parent POM — Java 17, Jakarta EE 10, plugin management
├── tripmate-ejb/           packaging `ejb` — business tier
│   └── com.tripmate.remote.HelloServiceRemote   @Remote interface
│   └── com.tripmate.ejb.HelloService            @Stateless implementation
├── tripmate-web/           packaging `war` — presentation tier
│   ├── index.xhtml                              Faces 4.0 view
│   ├── WEB-INF/{web,faces-config,beans}.xml     Servlet 6.0 / Faces 4.0 / CDI 4.0
│   └── com.tripmate.web.backing.HelloBean       CDI backing bean
├── tripmate-ear/           packaging `ear` — assembles EJB + WAR
└── scripts/                env.sh, build.sh, deploy.sh, undeploy.sh, domain.sh
```

### Dependency scoping, and why it matters

This is the part of Phase 0 that later phases depend on:

- `jakarta.jakartaee-api` is `provided` in both modules. GlassFish already ships
  every one of those classes; bundling them into the EAR causes classloader
  conflicts at deploy time.
- `tripmate-web` depends on `tripmate-ejb` with scope `provided`, so the shared
  `@Remote` interface exists in **exactly one place** inside the archive — the
  EJB jar. Verified: the 5.3 KB WAR contains no `com/tripmate/ejb` or
  `com/tripmate/remote` classes, and the 3.6 KB EJB jar contains no `jakarta/`
  classes.
- `tripmate-ear` sets the context root to `/tripmate` via the generated
  `application.xml`; without it the WAR would deploy under its artifact id.

---

## 4. Milestone evidence

### 4.1 Build

```
TripMate :: Parent ................................. SUCCESS
TripMate :: EJB .................................... SUCCESS
TripMate :: Web .................................... SUCCESS
TripMate :: EAR .................................... SUCCESS
BUILD SUCCESS
```

Artifact: `tripmate-ear/target/tripmate-ear-1.0.0-SNAPSHOT.ear` containing
`tripmate-ejb.jar`, `tripmate-web.war`, and `META-INF/application.xml` at
descriptor version 10.

### 4.2 Deploy

From `domain1/logs/server.log`:

```
Portable JNDI names for EJB HelloService:
  [java:global/tripmate/tripmate-ejb/HelloService!com.tripmate.remote.HelloServiceRemote,
   java:global/tripmate/tripmate-ejb/HelloService]
tripmate was successfully deployed in 542 milliseconds.
```

`asadmin list-applications` → `tripmate <ear, ejb, web>`

### 4.3 End-to-end verification

The deployment-check page is not decorative: rendering it forces a real call
from the web tier into the business tier, so a green page proves the chain.

**`GET http://localhost:8080/tripmate/`** → `200`, body contains:

```
Hello, traveller — the TripMate business tier is live.
Stateless session bean reachable | Java 17.0.20.1 | Ubuntu
```

The second line is produced *inside the EJB container*, confirming the call
crossed into the business tier rather than being served by the web tier alone.

**`POST` with the name field set to `Srijan`** → `200`, body contains:

```
Hello, Srijan — the TripMate business tier is live.
```

The POST exercises the full Faces lifecycle — decode, validate, invoke
application, render — with server-side view state, and re-invokes the EJB with
the submitted value.

### 4.4 Scripts

`./scripts/undeploy.sh` then `./scripts/deploy.sh` were run end to end:
the application was removed, rebuilt, redeployed, and served correctly.

---

## 5. Reproducing

```bash
# Build the EAR
./scripts/build.sh

# Start the domain (if not already running)
./scripts/domain.sh start

# Build, start if needed, and deploy
./scripts/deploy.sh

# Open
xdg-open http://localhost:8080/tripmate/

# Remove
./scripts/undeploy.sh
```

Override `GLASSFISH_HOME` or `JAVA_HOME` before invoking any script to point at
a different installation.

---

## 6. Notes carried into later phases

- **`HelloService` is scaffolding.** Phase 4 replaces it with the five real
  session beans (`UserService`, `TripService`, `ItineraryService`,
  `ExpenseService`, `ReportService`) and deletes `HelloServiceRemote`. The
  `index.xhtml` deployment-check page is replaced by `login.xhtml` in Phase 6.
- **No `persistence.xml` yet.** The EJB module deliberately has no datasource,
  so Phase 0 can be verified without touching PostgreSQL. Phase 2 adds
  `META-INF/persistence.xml` and the JDBC resource.
- **PrimeFaces and Bootstrap are not yet added.** ROADMAP §11.1 places them in
  Phase 6. Phase 0 uses plain Faces components so the deployment check has no
  third-party dependency to debug.
- **`faces-config.xml` pins spec level 4.0** and carries a locale configuration;
  it is otherwise empty because Faces 4.0 auto-registers its servlet and CDI
  resolves `@Named` beans without declaration.
- **A separate `mediqueue` application is deployed on the same domain.** It is
  unrelated to TripMate and was left untouched.

---

## 7. Next — Phase 1: Database

Write `database/schema.sql` (7 tables), `database/seed.sql` (5 users, 3 trips,
~25 expenses, ~80 splits, ~6 settlements), and `database/reset.sql`; then create
the `tripmate` role and database and verify with SQL queries.

**Milestone:** all 7 tables exist with sample data.
