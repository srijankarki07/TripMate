# Branches

Each feature is one branch, stacked: a branch is cut from the one before it,
so its history already contains everything it depends on. That is what lets the
pull requests be opened in order without rebasing anything.

**This file is the push order.** Push top to bottom. A branch whose parent does
not exist on the remote yet is rejected, because the commit it builds on is not
there.

---

## How to push

The remote's default branch is `main`, and only `main` is on it so far. From the
repository root:

```bash
while read -r branch; do
    git push -u origin "$branch" || break
done <<'BRANCHES'
feat/phase0-scaffold
feat/phase0-ejb-module
feat/phase0-web-module
feat/phase0-ear-module
feat/phase0-design-tokens
feat/phase1-schema
feat/phase1-seed
feat/phase1-setup
feat/phase2-entities
feat/phase2-persistence
feat/phase2-roundtrip
feat/phase3-dtos
feat/phase3-mapping-test
feat/phase4-user-service
feat/phase4-trip-service
feat/phase4-itinerary-service
feat/phase4-expense-service
feat/phase4-report-service
feat/phase4-session-bean-checks
feat/phase4-docs
feat/phase5-query-verification
feat/phase5-docs
feat/phase6-primefaces-setup
feat/phase6-auth
feat/phase6-dashboard
feat/phase6-trips
feat/phase6-trip-detail
feat/phase6-itinerary
feat/phase6-expenses
feat/phase6-balances-settle
feat/phase6-reports
feat/phase6-profile
feat/phase7-jms-setup
feat/phase7-expense-mdb
feat/phase7-docs
feat/phase8-outstanding-position
feat/phase8-docs
feat/phase9-authorization
feat/phase9-jsf-validation
feat/phase9-docs
feat/phase10-test-matrix
feat/phase10-docs
feat/phase11-runbook
feat/phase11-serverless-report
feat/phase11-design-report
feat/phase12-clean-deploy
feat/phase12-viva-prep
feat/phase13-design-system
feat/phase13-app-shell
feat/phase13-form-controls
feat/phase13-feedback
feat/phase13-data-table
feat/phase13-workspace-pages
feat/phase13-auth-profile
feat/phase14-reports-analytics
feat/phase13-status-chips
feat/phase15-security
feat/phase14-invites
feat/phase14-expense-breakdown
feat/phase13-trip-pages
feat/phase14-email
feat/phase14-password-reset
feat/phase14-trip-summary
feat/phase15-docs
feat/phase16-theme-toggle
feat/phase16-mail-delivery
feat/phase16-ui-fixes
feat/phase17-remaining-work
feat/phase18-email-verification
feat/phase19-paginator-select
BRANCHES
```

`|| break` stops at the first failure rather than pushing the rest out of order
and leaving the stack half built.

### The same thing, derived rather than listed

The list above is generated from the history, so it goes stale the moment a
branch is added. This recomputes the order instead, by walking the commits
oldest first and printing whatever branch points at each one:

```bash
for sha in $(git rev-list --topo-order --reverse --all --not main); do
    git for-each-ref --format='%(refname:short)' --points-at "$sha" refs/heads
done
```

`--points-at` is what makes it correct: `git for-each-ref` on its own lists the
branches in ref order, which is alphabetical and has nothing to do with the
stack.

### Opening the pull requests

Each branch targets the one before it, named in the *Base* column below, not
`main`. The last one targets `feat/phase18-email-verification`.

---

## The order

| # | Branch | Base | Commits | Subject |
| ---: | --- | --- | ---: | --- |
| 1 | `feat/phase0-scaffold` | `main` | 1 | build: add Maven parent POM and repository scaffold |
| 2 | `feat/phase0-ejb-module` | `feat/phase0-scaffold` | 1 | feat(ejb): add business tier module with Hello Enterprise smoke-test bean |
| 3 | `feat/phase0-web-module` | `feat/phase0-ejb-module` | 1 | feat(web): add presentation tier module with Faces deployment-check page |
| 4 | `feat/phase0-ear-module` | `feat/phase0-web-module` | 1 | feat(ear): assemble deployable EAR and verify Phase 0 milestone on GlassFish |
| 5 | `feat/phase0-design-tokens` | `feat/phase0-ear-module` |  **2** | docs: add rendered deployment-check screenshot to Phase 0 record |
| 6 | `feat/phase1-schema` | `feat/phase0-design-tokens` | 1 | feat(db): add seven-table PostgreSQL schema and reset script |
| 7 | `feat/phase1-seed` | `feat/phase1-schema` | 1 | feat(db): add realistic seed data for all seven tables |
| 8 | `feat/phase1-setup` | `feat/phase1-seed` | 1 | feat(db): add role bootstrap, load script, and integrity verifier |
| 9 | `feat/phase2-entities` | `feat/phase1-setup` | 1 | feat(ejb): add seven JPA entity classes mapping the TripMate schema |
| 10 | `feat/phase2-persistence` | `feat/phase2-entities` | 1 | feat(ejb): add the JTA persistence unit and GlassFish datasource setup |
| 11 | `feat/phase2-roundtrip` | `feat/phase2-persistence` | 1 | feat(ejb): verify entity mappings round-trip against PostgreSQL |
| 12 | `feat/phase3-dtos` | `feat/phase2-roundtrip` | 1 | feat(ejb): add nine DTOs as the remote boundary contract |
| 13 | `feat/phase3-mapping-test` | `feat/phase3-dtos` | 1 | test(ejb): verify entity to DTO mapping against seeded data |
| 14 | `feat/phase4-user-service` | `feat/phase3-mapping-test` | 1 | feat(ejb): add UserService with PBKDF2 password hashing |
| 15 | `feat/phase4-trip-service` | `feat/phase4-user-service` | 1 | feat(ejb): add TripService with membership management |
| 16 | `feat/phase4-itinerary-service` | `feat/phase4-trip-service` | 1 | feat(ejb): add ItineraryService carrying query 1 |
| 17 | `feat/phase4-expense-service` | `feat/phase4-itinerary-service` | 1 | feat(ejb): add ExpenseService with splits, settlements and query 2 |
| 18 | `feat/phase4-report-service` | `feat/phase4-expense-service` | 1 | feat(ejb): add ReportService with the two complex queries |
| 19 | `feat/phase4-session-bean-checks` | `feat/phase4-report-service` | 1 | feat(web): add session bean checks and consolidate the diagnostics page |
| 20 | `feat/phase4-docs` | `feat/phase4-session-bean-checks` | 1 | docs: add the Phase 4 session bean record |
| 21 | `feat/phase5-query-verification` | `feat/phase4-docs` | 1 | test(ejb): verify the five queries against raw SQL |
| 22 | `feat/phase5-docs` | `feat/phase5-query-verification` | 1 | docs: add the Phase 5 query verification record |
| 23 | `feat/phase6-primefaces-setup` | `feat/phase5-docs` | 1 | feat(web): add PrimeFaces and map the design tokens onto its theme |
| 24 | `feat/phase6-auth` | `feat/phase6-primefaces-setup` | 1 | feat(web): add sign-in, registration, session state and an auth filter |
| 25 | `feat/phase6-dashboard` | `feat/phase6-auth` | 1 | feat(web): build the dashboard with totals, trips and recent activity |
| 26 | `feat/phase6-trips` | `feat/phase6-dashboard` | 1 | feat(web): add the trips list and trip creation |
| 27 | `feat/phase6-trip-detail` | `feat/phase6-trips` | 1 | feat(web): add the trip detail page with its four tabs |
| 28 | `feat/phase6-itinerary` | `feat/phase6-trip-detail` | 1 | feat(web): add the itinerary editor with a LocalTime converter |
| 29 | `feat/phase6-expenses` | `feat/phase6-itinerary` | 1 | feat(web): add the expense form with a split editor |
| 30 | `feat/phase6-balances-settle` | `feat/phase6-expenses` | 1 | feat(web): add the balances page and the settle-up flow |
| 31 | `feat/phase6-reports` | `feat/phase6-balances-settle` | 1 | feat(web): add the reports page with a category chart |
| 32 | `feat/phase6-profile` | `feat/phase6-reports` |  **2** | docs: add the Phase 6 web tier record |
| 33 | `feat/phase7-jms-setup` | `feat/phase6-profile` | 1 | build: add the JMS resources TripMate needs on GlassFish |
| 34 | `feat/phase7-expense-mdb` | `feat/phase7-jms-setup` | 1 | feat(ejb): add the expense message and its message-driven bean |
| 35 | `feat/phase7-docs` | `feat/phase7-expense-mdb` | 1 | docs: add the Phase 7 messaging record |
| 36 | `feat/phase8-outstanding-position` | `feat/phase7-docs` | 1 | feat(web): show what is still outstanding, and settle from that |
| 37 | `feat/phase8-docs` | `feat/phase8-outstanding-position` | 1 | docs: add the Phase 8 complex UI record |
| 38 | `feat/phase9-authorization` | `feat/phase8-docs` | 1 | feat(ejb): enforce the rules that need to know who is asking |
| 39 | `feat/phase9-jsf-validation` | `feat/phase9-authorization` | 1 | feat(web): add JSF field validation with inline messages |
| 40 | `feat/phase9-docs` | `feat/phase9-jsf-validation` | 1 | docs: add the Phase 9 business rules record |
| 41 | `feat/phase10-test-matrix` | `feat/phase9-docs` | 1 | test: run the ROADMAP section 16.2 matrix from a script |
| 42 | `feat/phase10-docs` | `feat/phase10-test-matrix` | 1 | docs: add the Phase 10 testing record |
| 43 | `feat/phase11-runbook` | `feat/phase10-docs` | 1 | docs: add an operations runbook |
| 44 | `feat/phase11-serverless-report` | `feat/phase11-runbook` | 1 | docs: add the Part One serverless evaluation |
| 45 | `feat/phase11-design-report` | `feat/phase11-serverless-report` | 1 | docs: add the Part Two design report |
| 46 | `feat/phase12-clean-deploy` | `feat/phase11-design-report` | 1 | build: add a cold deploy script and fix the two faults it found |
| 47 | `feat/phase12-viva-prep` | `feat/phase12-clean-deploy` | 1 | docs: add the viva preparation |
| 48 | `feat/phase13-design-system` | `feat/phase12-viva-prep` | 1 | feat(web): extend the design system beyond the imported spec |
| 49 | `feat/phase13-app-shell` | `feat/phase13-design-system` | 1 | feat(web): make the application shell responsive and properly framed |
| 50 | `feat/phase13-form-controls` | `feat/phase13-app-shell` | 1 | feat(web): rebuild the date picker and settle the focus and field-height rules |
| 51 | `feat/phase13-feedback` | `feat/phase13-form-controls` | 1 | feat(web): add toasts and dialogs, and stop leaking raw failures |
| 52 | `feat/phase13-data-table` | `feat/phase13-feedback` | 1 | feat(web): make tables sortable and turn them into cards on a phone |
| 53 | `feat/phase13-workspace-pages` | `feat/phase13-data-table` | 1 | feat(web): give the dashboard a summary and tidy the trip settings form |
| 54 | `feat/phase13-auth-profile` | `feat/phase13-workspace-pages` | 1 | feat(web): rebuild sign-in and registration, and split the profile into tabs |
| 55 | `feat/phase14-reports-analytics` | `feat/phase13-auth-profile` | 1 | feat(reports): rebuild the report as charts, comparisons and written findings |
| 56 | `feat/phase13-status-chips` | `feat/phase14-reports-analytics` | 1 | feat(web): colour the state chips and lock the tab widths |
| 57 | `feat/phase15-security` | `feat/phase13-status-chips` | 1 | fix(security): close the authorization holes in the service tier |
| 58 | `feat/phase14-invites` | `feat/phase15-security` | 1 | feat(trips): invite by exact email, and make the admin rules followable |
| 59 | `feat/phase14-expense-breakdown` | `feat/phase14-invites` | 1 | feat(expenses): show who owes what on each expense, and who has spent what |
| 60 | `feat/phase13-trip-pages` | `feat/phase14-expense-breakdown` | 1 | feat(web): make the trip pages readable at a glance, and gate them properly |
| 61 | `feat/phase14-email` | `feat/phase13-trip-pages` | 1 | feat(mail): add transactional email, with a log fallback when unconfigured |
| 62 | `feat/phase14-password-reset` | `feat/phase14-email` |  **2** | feat(auth): add forgotten-password, from the request to the new password |
| 63 | `feat/phase14-trip-summary` | `feat/phase14-password-reset` | 1 | feat(trips): email everyone a closing summary of the trip |
| 64 | `feat/phase15-docs` | `feat/phase14-trip-summary` | 1 | docs: record the delivery, notifications and security phases |
| 65 | `feat/phase16-theme-toggle` | `feat/phase15-docs` | 1 | feat(web): add a dark theme, chosen by the server so the charts agree |
| 66 | `feat/phase16-mail-delivery` | `feat/phase16-theme-toggle` |  **2** | fix(web): make ink-filled surfaces legible on the dark theme |
| 67 | `feat/phase16-ui-fixes` | `feat/phase16-mail-delivery` |  **25** | Revert "feat(web): one table everywhere, a darker floor, and the neutral button" |
| 68 | `feat/phase17-remaining-work` | `feat/phase16-ui-fixes` | 1 | feat: add calendar sync, the travel map, and rebuilt public pages |
| 69 | `feat/phase18-email-verification` | `feat/phase17-remaining-work` | 1 | feat: confirm an email address before the account works |
| 70 | `feat/phase19-paginator-select` | `feat/phase18-email-verification` | 1 | fix(web): make the paginator's rows-per-page control match the other selects |

---

## Notes

### Branches carrying more than one commit

The convention is one commit per branch. Five branches break it:

| Branch | Commits |
| --- | ---: |
| `feat/phase16-ui-fixes` | 25 |
| `feat/phase0-design-tokens` | 2 |
| `feat/phase6-profile` | 2 |
| `feat/phase14-password-reset` | 2 |
| `feat/phase16-mail-delivery` | 2 |

`feat/phase16-ui-fixes` is the one worth knowing about. It carries twenty-five
commits, because a run of work was committed straight onto it rather than cut
into branches, and it ends with a revert of the commit before it. **That pull
request will show twenty-five commits and a very large diff.** If you want it
reviewed as one thing, it is worth splitting before pushing; if it is the last
of the UI work and you are happy with it as a single change, push it as it is.

### The phase numbers are not the push order

Branches were cut as the work was done, which is not the same as numerical
order. `feat/phase14-reports-analytics` sits at position 55 and
`feat/phase13-status-chips` at 56, and `feat/phase15-security` at 57 comes
before two of the phase 14 branches. Follow the table, not the numbers.

### What is not on a branch

Four paths are untracked on purpose and are not part of any of this:

| Path | Why |
| --- | --- |
| `PHASE13-NOTES.md`, `PHASE16-NOTES.md` | Working notes. Both say so in their first paragraph. |
| `.agents/`, `skills-lock.json` | Tooling artifacts, not project files. |

---

Generated 2026-10-14 from the local history: 70 branches, 70 commits ahead of
`main`.
