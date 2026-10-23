# Phase 13: Delivery

**Status:** complete
**Date:** 2026-09-28
**Milestone (ROADMAP §14):** *The application presents as a product, not as a CRUD form.*

---

## 1. What this phase was

Phases 0 to 12 built a working enterprise application. Every requirement in the brief
was met and the test matrix passed, but the thing a person actually looked at had barely
been touched since the first PrimeFaces page rendered. The theme's own blue showed
through on buttons, tabs and checkboxes; the date picker was 424 by 514 pixels; every
container sat flush against the next one; destructive actions used `window.confirm`; a
failure printed the container's own words into a red box; there was no responsive
behaviour at all; and the reports page was one flat bar chart.

This phase is the delivery layer: the work of making what already functioned present well,
and of closing the gaps that only became visible once somebody used it properly.

It also turned up four defects that were not cosmetic, described in section 4.

## 2. Deliverables

| Area | What changed |
| --- | --- |
| Design tokens | `DESIGN.md` Part Two: semantic states, a three-tier elevation scale, gradients, motion, a validated chart palette |
| Shell | Sticky responsive navigation, an off-canvas panel below 744px, a footer that sits at the bottom, per-page metadata, generated icons |
| Form controls | A rebuilt date picker, a select that does not resize, one focus policy |
| Feedback | Toasts, one global confirmation dialog, empty and error states, sanitised failures |
| Tables | Sortable headers, a stacked-card layout below 744px, pagination, state chips |
| Pages | Dashboard, trips, trip detail, expenses, balances, itinerary, sign-in, registration, profile, reports |
| Reports | Category ring, spend over time, paid against share, per-head trip comparison, written findings |
| Expense breakdown | Who owes what on each expense, and what each person has spent |
| Security | Authorization gaps closed, session fixation fixed, error messages sanitised |
| Invitations | Exact-email invite, promote, demote, leave |
| Email | Asynchronous sending, responsive templates, a logging fallback |
| Password reset | Hashed single-use tokens, a uniform reply, rate limiting |
| Trip summary | A closing email, written per recipient |

## 3. Design decisions

### 3.1 The design system was extended, not replaced

`DESIGN.md` Part One is Airbnb's design system, imported rather than authored. It is
written for a photography-led marketplace, and three of its rules do not survive contact
with a data application. It documents no success colour, so a notice saying an expense was
saved rendered identically to one saying nothing happened. It defines one shadow tier,
which makes a dropdown indistinguishable from a resting card. And its responsive rules
describe cards that stack, not tables that have to become something else.

Part Two extends it: four semantic states, three elevation tiers, two gradients, a motion
scale, and a chart palette. Rausch remains the only brand accent; the states are states and
never appear on a button, a navigation item or a link.

### 3.2 The chart palette was computed

A chart needs more than one colour, which a single-accent system cannot supply. Rather than
pick six that looked harmonious, candidate orderings were run through the checks that can
be measured: lightness band, chroma floor, adjacent-pair separation under simulated
protanopia and deuteranopia, normal-vision separation, and contrast against the surface.
The order that passed all five with no warnings is the one in use, and the reasoning is
recorded in Part Two so a later change does not undo it by accident.

Because Chart.js draws to a canvas, the colours cannot come from CSS, so the palette exists
twice: once as custom properties for the legend, once as a Java class for the chart. The
diagnostics page reads the stylesheet back and asserts they still agree.

### 3.3 One focus policy

Two reported defects had the same cause. Clicking a tab left a border behind, and clicking
a table cell drew one too. PrimeFaces gives both a `tabindex` so they are reachable by
keyboard, and a browser then treats a mouse click as focus and draws the platform ring,
which follows the desktop accent rather than the page's.

The rule is now one rule: a ring is drawn for `:focus-visible` only. Keyboard focus keeps an
obvious indicator, which is a requirement rather than a preference; a mouse click leaves
nothing behind.

### 3.4 One reply, whatever happened

Sign-in already refused an unknown address and a wrong password with the same sentence.
The invite form and the forgotten-password form follow the same rule, for the same reason:
a form that answers "no such user" is a way to test whether any given address uses this
site. The password-reset page's reply was verified to be identical for a seeded address and
for one that has never existed.

### 3.5 Toasts need two buckets

A toast is put into request scope *and* flash scope. Flash alone is not enough: JSF carries
a flash value only to the next request, and a failed sign-in redisplays the page inside the
same request. Request scope alone is not enough either, because a successful action usually
redirects and discards the request. The view also reads the pending list live rather than
caching it, because the view's bean can be constructed before the action that raises the
message. Both of those were bugs before they were design.

## 4. Bugs found by running it

### 4.1 Sorting did nothing, for three different reasons

Reported as "the sorting on expense and member tables is not functional and when done shows
nothing". Each cause produced the same symptom.

1. **Three of the four tab panels on the trip page had no `<form>` ancestor.** Sorting is
   an AJAX round trip, and JSF cannot process one without a form. Clicking a header did
   nothing at all.
2. **Once they had forms, the table rendered empty.** PrimeFaces sorts the collection the
   value expression returns, in place. The beans defaulted to `List.of()`, which is
   immutable, so the sort threw `UnsupportedOperationException` inside the render. The table
   came back blank and the log said nothing useful.
3. **The first fix made it worse.** Returning `new ArrayList<>(field)` from the getter looks
   safer and silently breaks sorting again: the sort lands on a copy that is discarded, and
   the render then reads a fresh unsorted one. The getter has to hand over the same mutable
   instance, and each one now carries a comment saying so.

### 4.2 The second-level cache served entities the database no longer had

The most confusing defect in the project, and it was found by accident while testing
password reset.

A reset changed a password. A reseed restored the seeded hash, byte for byte identical to
`seed.sql`. The application then refused the seeded password and accepted the reset one.
Renaming a user directly in the database changed nothing on the dashboard.

EclipseLink enables a shared cache by default, and `database/load.sh` and
`scripts/clean-deploy.sh` both drop and reseed every table **while the server is still
running**. The cache survived the reload.

It also explains a diagnostics failure that had been reported once and never reproduced:
`FAIL [users] read after clear() returns the row`. `em.clear()` empties the persistence
context, not the shared cache, so a row read back after a clear can come from the cache and
look missing. It was intermittent because it depended on what had been read earlier.

`persistence.xml` now sets `<shared-cache-mode>NONE</shared-cache-mode>`, the JPA-standard
switch rather than a provider-specific one.

### 4.3 PrimeFaces reflow switches at a different width from the application

`p:dataTable reflow="true"` applies its stacking rules inside `@media (max-width: 640px)`.
The application's breakpoint is 744px. Between those two widths the header row was hidden
but the cells had not been restructured, so the table rendered as neither. The rules are now
restated at the application's own breakpoint, with `td[role="gridcell"]` in the selector
because the library's own rule uses an attribute and a `:not()` and would otherwise win on
specificity.

### 4.4 A `min-width` on a table widens the whole page on a phone

The obvious fix for a crushed table. It does make the wrapper scroll, but the table's
minimum width then propagates up as the document's minimum content width, and a browser on
a phone widens the layout viewport to fit it rather than scrolling one region. At a 390px
viewport the page measured 680px and everything on it was scaled down. A comment in the
stylesheet records this so the fix is not attempted again.

### 4.5 Returning a copy from a getter is not always the safe choice

See 4.1.3. Recorded separately because it is the kind of change that reviews well and
behaves badly.

## 5. Verification

```text
ROADMAP §16.2 test matrix
  17 passed, 0 failed

Diagnostics page, all six suites plus the palette checks
  288 checks, 0 failures

Horizontal overflow, measured at every breakpoint
  390 / 768 / 1024 / 1440   scrollWidth == innerWidth   no offenders
```

The responsive claims were measured rather than eyeballed, using the DevTools protocol to
read `scrollWidth` against `innerWidth` and to list any element extending past the viewport.
The focus claims were measured the same way: tabbing through a page and reporting whether
each stop draws a ring, then clicking the same elements and reporting that none does.

## 6. Reproducing

```bash
./scripts/deploy.sh
xdg-open http://localhost:8080/tripmate/
```

Sign in as `alice@tripmate.test` with `tripmate123`.

## 7. Notes for later phases

- **The chart palette order is not cosmetic.** Reordering the slots, or cycling them for a
  seventh category, breaks the colourblind separation. A seventh category folds into
  "Other" in the reserved neutral.
- **Do not return copies from the table list getters.** See 4.1.3.
- **Do not put a `min-width` on a table.** See 4.4.
- **A reseed no longer needs a restart**, because of the shared-cache change. Before that it
  did, and the symptom was silent.
- **Mobile sorting is not available.** Reflow adds a native `<select>` for choosing a sort
  column, and its dropdown is drawn by the browser and cannot be styled, so it is hidden
  rather than left looking like it came from a different product. Sorting is a desktop
  affordance for now.

## 8. Next: Phase 14, notifications and security

Email, invitations, password reset, the trip summary, and the authorization work that the
delivery phase made visible.
