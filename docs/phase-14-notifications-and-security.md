# Phase 14: Notifications, Invitations and Security

**Status:** complete
**Date:** 2026-09-28
**Milestone (ROADMAP §14):** *The application can reach the people who use it, and can refuse
the people who should not.*

---

## 1. What this phase was

Three things the brief asks for and the application did not do: it never emailed anyone, it
had no way to invite a person except by picking them out of a list of every account on the
platform, and password reset did not exist. Alongside those, the delivery phase made visible
a set of authorization gaps that had been in the service tier since Phase 4.

## 2. Deliverables

| Area | What was added |
| --- | --- |
| Email | `MailService`, responsive templates, a logging fallback, a provisioning script |
| Invitations | Exact-email invite, rotate-free join by invitation only, promote, demote, leave |
| Password reset | Hashed single-use tokens, a uniform reply, per-account rate limiting |
| Trip summary | A closing email written per recipient |
| Security | Authorization on the itinerary, report and user services; session rotation |

## 3. Design decisions

### 3.1 Sending email must never fail the thing that caused it

Registering an account or being added to a trip has already succeeded by the time an email
is attempted. A mail server that is down, or a password that has expired, must not turn a
successful registration into an error page, because the person then either retries and
creates a duplicate or gives up on an account that exists.

So `MailService.send` returns nothing and throws nothing. Failures are logged at WARNING
with enough detail to diagnose, and the caller carries on. The send is `@Asynchronous`, so
the container defers it until the caller's transaction commits and an email is never sent
for a write that then rolls back.

### 3.2 Whether mail leaves the server is decided by the container, not the environment

The mail resource always exists. Whether it delivers is carried by a `tripmate.deliver`
property set from whether an SMTP host was configured. With no host, `MailService` renders
each message in full and writes it to the log.

A Java EE application has no portable access to the environment the server was started in,
and the container's own configuration is the right place for the decision anyway: the same
artifact then runs against a logging transport on a laptop and a real one in a deployment
with no code difference. A fresh checkout deploys, runs and is demonstrable with no
credentials, and nothing silently disappears.

### 3.3 A reset token is a bearer credential, so it is stored hashed

Whoever holds a reset token can set the password. Storing it in the clear would mean a
backup, a log line or a read replica hands over the ability to take over any account with
an outstanding request.

The token is 256 bits from `SecureRandom`, stored as its SHA-256, compared with
`MessageDigest.isEqual` so the comparison does not stop at the first differing byte. SHA-256
rather than the PBKDF2 used for passwords, and deliberately: a password is chosen by a
person and is guessable, so it needs a slow hash; a 256-bit random token is not, and slowing
it down would only make every reset more expensive to serve. The two cases look similar and
are not.

### 3.4 The invite no longer lists the user directory

The old picker was populated from `getAllUsers()`, so any trip administrator could read the
full list of every account on the platform. It is replaced by an exact email lookup with no
partial search, because a partial search is the same directory again one letter at a time.

**The trade-off, recorded honestly:** a refusal says whether the address has an account, so
an administrator can tell somebody to sign up first rather than leaving them guessing. That
is a narrow disclosure, one address at a time, to somebody who already administers a trip,
rather than a list of everybody. It is still a disclosure, and an invitation that could be
created without knowing whether the account exists would remove it.

### 3.5 The password change lives in the reset service

`UserService.changePassword` requires both the actor and the password in force. A reset is
precisely the case where the second is not known, so the write lives in
`PasswordResetService`, where the token is the whole of the authorization. Keeping it in one
place keeps the unguarded surface small enough to audit.

## 4. Bugs found by running it

### 4.1 The guard was inserted inside the null check

Adding `TripAccess.requireMember` to `ItineraryService.deleteItem` with a text substitution
put the call between the `if (entity == null) { ... }` block and its closing brace, so the
authorization check only ran when the item did not exist. Found by reading the result rather
than trusting the edit.

### 4.2 A bean exposing a remote interface has no no-interface local view

`@EJB private ReportService reportService;` inside `TripService` compiled and failed at
deployment, because a bean that exposes a remote business interface is not given a
no-interface local view. Injecting by `ReportServiceRemote` is the correct form.

### 4.3 The plain-text part carried HTML

The trip summary built its "you are owed" sentence once and used it in both the HTML and the
plain-text part, which put literal `<strong>` tags into the text-only copy. The
added-to-trip template already carried a comment warning about exactly this, having made the
same mistake. There are now two methods, `positionHtml` and `positionText`, and neither is
derived from the other.

### 4.4 The session id did not change on sign-in

`CurrentUser.login` assigned a field and nothing else, so an attacker who could plant a
session id on the victim before they signed in kept using it afterwards. It now rotates,
which was verified by capturing the cookie before and after.

## 5. Verification

```text
ROADMAP §16.2 test matrix
  17 passed, 0 failed
  rows 4 and 5 rewritten for the new invite form; row 5b added

Diagnostics page
  288 checks, 0 failures

Password reset, end to end
  request            one reply for a seeded address and for an unknown one
  token              43 characters, 256 bits, stored as a hash
  redeem             greets the holder, sets the password, signs in with it
  second use         refused
  after a reseed     the seeded password works again, the reset one does not
```

The invite and reset rows assert on the membership count before and after rather than on the
page text, so a refusal that quietly created a second row would still be caught.

## 6. Reproducing

```bash
./scripts/deploy.sh
```

With no `TM_MAIL_HOST` set, every message is rendered into the server log:

```bash
tail -f ../tools/glassfish7/glassfish/domains/domain1/logs/server.log | grep -A 20 "MAIL (no transport"
```

To send for real, export `TM_MAIL_HOST`, `TM_MAIL_PORT`, `TM_MAIL_USER`, `TM_MAIL_PASSWORD`,
`TM_MAIL_FROM` and `TM_APP_BASE_URL` before deploying.

## 7. Notes for later phases

- **CSRF is not implemented.** Every state-changing action is a plain form POST and there is
  no token. JSF's server-side view state makes this harder to exploit than a bare form and
  is not a defence. It is deliberately last because it touches every form and breaks the
  test harness, which drives those forms with curl.
- **The reset rate limit is per account, not per address.** Somebody with a list of
  addresses can still ask once for each. Per-address limiting needs state that survives a
  restart.
- **There is no `password_changed_at` column**, so a password cannot be expired by age.
  Spending the outstanding links explicitly achieves the same thing for the case that
  matters.
- **`getAllUsers()` is still on the remote interface** because a smoke test calls it.
  Nothing in the web tier calls it any more.
- **Real inbox placement is not verifiable here.** The templates can be rendered and read;
  whether a given provider files them as spam depends on DNS and reputation.

## 8. Next: Phase 15, the documentation pass

This document, the delivery record, and the ROADMAP update.
