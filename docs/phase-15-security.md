# Phase 15: Security Record

**Status:** complete, one item open
**Date:** 2026-09-28
**Milestone (ROADMAP §14):** *Findings recorded, and everything found is either fixed or
written down with its reasoning.*

---

## 1. Why this document exists

The security work in this project was not a phase in the original plan. It came out of a
review of the service tier during the delivery phase, which turned up authorization gaps
that had been present since Phase 4 and had never been reachable from the interface, and
therefore never noticed.

They are recorded here as one list rather than scattered through the phase documents,
because a reader asking "what does this application do about authorization" wants the
answer in one place, and because two of the findings are the kind that look like an
oversight whether or not they were one.

## 2. What was found and fixed

### 2.1 Any signed-in user could change anyone's password

`UserService.changePassword(Long userId, String newPlainPassword)` took a bare user id and
asked neither who was calling nor for the password in force. Any authenticated user who knew
or guessed another user's numeric id could set their password.

The web tier only ever passed the signed-in user's own id, so this was not reachable through
the interface. It was reachable from the service, and the service is a remote interface.

**Fixed.** The method now takes an actor and the current password, requires the actor to be
the owner, and verifies the password in force.

### 2.2 The itinerary service had no authorization at all

`updateItem` and `deleteItem` did not accept an actor parameter. `addItem` checked that the
author existed and never that they were on the trip. Anyone signed in could add to, edit or
delete any trip's itinerary given an id.

This one *was* reachable: an itinerary item id appears in the URL of the edit action.

**Fixed.** All three now call `TripAccess.requireMember` against the item's own trip. The
DTO deliberately carries no trip id, so update and delete resolve it from the loaded entity.

### 2.3 The report service answered for anyone

`getMemberBalances` and `getTotalSpentForTrip` took a trip id and returned its figures
without checking membership. The per-user aggregates took a user id and returned that
person's spending, so any caller could read any account's totals by guessing a number.

**Fixed.** The trip-scoped methods require membership; the user-scoped ones require the
actor to be the subject.

### 2.4 The session id survived sign-in

`CurrentUser.login` assigned a field and nothing else. An attacker who could plant a known
session id on the victim before they signed in kept using it afterwards. There was a second
half to this: `AuthenticationFilter` wrote the post-sign-in destination into the
pre-authentication session, so the same id genuinely did carry across the boundary.

**Fixed.** `login` calls `HttpServletRequest.changeSessionId()`, which preserves the
session's attributes and so preserves the destination. Verified by capturing the cookie
before and after: the id rotates and the session survives.

### 2.5 Failures were printed verbatim into the page

Seven copies of a `rootMessage` helper walked to the root cause of an exception and returned
its message for display. The container reports a failure from a session bean wrapped in an
`EJBException`, so what reached the page was whatever the container or the JDBC driver had
written, which on a database failure can name a table, a column or a constraint.

**Fixed.** One helper passes the application's own `ValidationException` messages through,
because those are written for a person to read, and turns everything else into one plain
sentence while logging the detail server-side.

### 2.6 The invite form listed every account

The member picker was populated from `getAllUsers()`, so any trip administrator could read
the full list of every account on the platform.

**Fixed.** Replaced by an exact email lookup with no partial search.

### 2.7 A password reset token was a plaintext key in waiting

The reset feature did not exist, which is itself the finding: no reset means either shared
passwords or accounts that cannot be recovered, and people are pragmatic about which.

**Built to a specification rather than added.** See the notifications document: 256-bit
tokens from `SecureRandom`, stored only as SHA-256, compared without an early exit, thirty
minutes, single use, every earlier link spent when a new one is requested, and every
outstanding link spent when a password changes.

### 2.8 The password-reset form answered whether an address exists

Not a finding so much as a trap avoided while building it. A reset form that says "no such
user" is a way to test whether any given address uses this site.

**Verified, not assumed.** The reply was compared for a seeded address and for one that has
never existed, and it is identical.

## 3. What is not fixed

### 3.1 CSRF

**Open, and known.** Every state-changing action is a plain form POST and there is no token
anywhere. JSF's server-side view state makes this harder to exploit than a bare form, since
an attacker cannot simply construct a valid POST without knowing the view state, and that is
not a defence.

It is last on purpose. It touches every form in the application, and `scripts/test-matrix.sh`
drives those same forms with curl and will need to scrape and resend the token. Doing it
carelessly would break the only end-to-end test the project has, which is a worse outcome
than doing it deliberately later.

### 3.2 The reset rate limit is per account

Somebody with a list of addresses can ask once for each. Per-address limiting needs state
that survives a restart, which is a table or a shared counter and a bigger change than the
feature warranted on its own.

### 3.3 `getAllUsers()` is still exposed

Nothing in the web tier calls it. A smoke test does, which is why it is still on the remote
interface. The leak was the interface surfacing it, and that is closed; the method is a
loose end rather than an open door.

### 3.4 No authorization on `TripService` reads

`getTrip`, `getTripsForUser`, `getTripMembers` and `isMember` take ids and return data
without checking who is asking. The web tier always calls them with the signed-in user's id
and the pages check membership before rendering, so nothing is exposed through the
interface today. It is the same class of gap as 2.3 and is recorded rather than quietly
left.

## 4. What is defended, and how

| Concern | Approach |
| --- | --- |
| Password storage | PBKDF2-HMAC-SHA256, 210,000 iterations, 16-byte random salt, constant-time compare |
| Reset tokens | 256-bit `SecureRandom`, stored as SHA-256, single use, 30 minutes |
| Address enumeration | One reply for a known and an unknown address, on sign-in, invite and reset |
| Session fixation | Session id rotated on sign-in |
| Authorization | Explicit actor parameter on every operation that needs to know who is asking |
| Error disclosure | Application messages pass through; everything else becomes one sentence |
| SQL injection | JPQL with bound parameters throughout; no string concatenation into a query |
| Response escaping | JSF escapes output by default; the mail templates escape every interpolation |

## 5. The authorization pattern, and why it looks the way it does

Every operation that depends on who is asking takes the acting user's id as a parameter.
Nothing reads an identity from a thread-local, a session or a security context.

The reasoning is in `TripAccess` and is worth repeating, because the alternative is the
obvious design and it is worse: an implicit identity can be forgotten. A caller that forgets
to set it produces an authorization check that silently passes, and nothing fails. An
argument cannot be forgotten, because the code does not compile.

The cost is that every call site has to pass the id, and that a service method's signature
is longer than it looks like it needs to be. That is the trade.

## 6. Reproducing the checks

```bash
./scripts/test-matrix.sh          # rows 13 and 14 assert refusals
./scripts/deploy.sh
# then open /diagnostics.xhtml and run the suites
```
