# Phase 18: Confirming an Address Before the Account Works

**Status:** complete
**Date:** 2026-10-03
**Milestone:** *An account made here cannot be used until the address it was made with answers.*

---

## 1. What this phase was

Registration used to be the whole of it. A name, an address and a password were accepted, the
account was created, and the person was signed straight in. Nothing ever asked whether the
address was theirs, or whether it existed at all.

That is fine until the address matters. It is the login identifier, so the account is only as
reachable as the string typed into the box. It is where every notification goes: being added to a
trip, the closing summary, the reset link that is the only way back into a forgotten account. An
account registered against an address nobody reads is a dead account with a live password, and it
also holds that address against the person who really owns it, because the unique constraint does
not care who is typing.

So this phase puts a step between creating an account and using one: a six-digit code goes to the
address, and until it comes back the account exists but does not open.

## 2. Deliverables

| Area | What was added |
| --- | --- |
| Storage | `users.email_verified_at`, and the `email_verifications` table |
| Entities | `EmailVerification`, and the `emailVerifiedAt` column on `User` |
| Business tier | `EmailVerificationService`: issuing, resending, checking, and the welcome message that now waits for all three |
| Mail | `Templates.verificationCode`, with the code set large and tracked out |
| Web tier | `VerifyEmailBean`, `PendingVerification`, `verify-email.xhtml` |
| Changed | `RegisterBean` no longer signs anyone in; `LoginBean` gained a third destination; `UserDTO` carries `emailVerified`; `ExternalAddress.clientAddress` |
| Styles | `.tm-input-code`, and the `:root` the secondary button rule was missing |
| Database | `database/load.sh` produces the same schema; seeded users are marked confirmed |

## 3. The flow

```
register.xhtml ──▶ UserService.register ──▶ account created, unconfirmed
                                  │
                                  └─▶ EmailVerificationService.issueIfNoneOutstanding
                                        └─▶ code emailed, hash stored

verify-email.xhtml ──▶ verify(code?) ──┬─ right ──▶ address confirmed ──▶ signed in ──▶ dashboard
                                       ├─ wrong ──▶ "N tries left"
                                       ├─ expired ──▶ "send a new one"
                                       ├─ out of tries ──▶ "that code is dead, send a new one"
                                       └─ nothing waiting ──▶ "send a new one"
                                       │
                                       └─▶ resend ──▶ a new code, and the old one dies

login.xhtml ──▶ right password, unconfirmed address ──▶ verify-email.xhtml
```

## 4. The decisions, and what they are for

### 4.1 A code, not a link

The password reset sends a link. This sends six digits, and the difference is not an
inconsistency.

A reset link is opened once, from whatever device is nearest, and can carry 256 bits of
unguessable token. A confirmation code has to be read off one screen and typed into another, so it
has to be short enough to hold in the head for the few seconds that takes. Short means guessable:
six digits is a million possibilities, which is nothing to a script.

That is not an argument against codes, it is an argument for bounding the guessing somewhere other
than the length. Which is what the next two decisions are.

### 4.2 Stored under the slow hash, not the fast one

`password_resets.token_hash` is a SHA-256 digest. `email_verifications.code_hash` is PBKDF2-SHA256
at 210,000 iterations, the same as a password. The two look alike and the reasoning runs the
opposite way.

A reset token is 256 random bits, so there is nothing to guess and a fast hash is enough to make a
stolen copy of the table useless. A six-digit code against a fast hash is brute-forceable from a
copy of that table in well under an hour. The slow hash costs about a tenth of a second per
candidate, which the application pays once per verification attempt and an attacker pays a million
times.

### 4.3 Five tries, then the code is dead

The attempt limit is the half of this that actually makes six digits safe. A million possibilities
with an unlimited number of guesses is a million possibilities; with five, it is five.

A wrong guess is counted, the count is stored on the row, and the fifth one spends the code. The
person is told how many tries are left rather than left to discover the limit by hitting it. A
value that is not six digits at all is refused without being counted, because a value that cannot
be correct is not a guess, and charging for it only punishes somebody who fumbled a paste.

### 4.4 What happens when the code expires

This was the question worth getting right, and the shape of the answer is: **an expired code costs
a press, never an account.**

Three things make that true, and each of them is load-bearing.

1. **Expiry is reported as expiry.** The service distinguishes an expired code from a wrong one,
   from an exhausted one, and from a code that was never sent, and gives each its own sentence.
   The usual shortcut is one message — "that code is not valid" — which leaves the person with no
   idea whether to retype it or ask for another.

2. **The cooldown does not apply when nothing is live.** A replacement code is refused for sixty
   seconds after the last one, but only while that code is still usable. The wait exists to stop a
   live code being replaced in a loop; it has no business making somebody sit out a code that has
   already stopped working. So an expired code is replaced immediately.

3. **Signing in leads back to it.** The account is not the thing that expired, so the password still
   works. Signing in with it lands on the verification page, and a fresh code is sent if none is
   live. Closing the tab, clearing cookies, or coming back tomorrow all end in the same place
   rather than in an account that can neither be used nor finished.

The alternative — refusing the sign-in with "confirm your email first" — is the common
implementation and it is a dead end. It turns an expired code into a locked account, because the
page that could send another one is behind the sign-in that is being refused.

### 4.5 Signing in has three outcomes now

`UserService.login` answers one question: do these credentials belong together, and to an account?
It deliberately does not also answer whether that account may be used, and an unconfirmed account
therefore still authenticates.

That is not a loophole, it is the mechanism from 4.4: the only route back to the confirmation page
after a lost session is the sign-in form, so the sign-in form has to work. The decision moves out
to the caller, where it belongs, and the answer travels in the DTO as `emailVerified`:

| Caller | Unconfirmed account | Why |
| --- | --- | --- |
| `LoginBean` | sent to the verification page | signing in is how you get back to the code |
| `ProfileBean` | unaffected | already signed in, re-checking a password |

`CurrentUser.login` is called on exactly one path, the one where the address has answered.

### 4.6 Requesting a code is not a way to post mail at somebody

One code is live at a time: sending a replacement spends the earlier one, so a message found later
in an inbox is dead. On top of that, sixty seconds between replacements while a code is live, and
five codes an hour.

Neither of those applies to the account holder's own recovery. They apply to the loop.

### 4.7 The welcome message moved

It used to go out at registration, congratulating somebody on an account they could not yet use and
offering a button to a page that would refuse them. It now goes out when the address answers, which
is the first moment any of it is true.

### 4.8 One primary action per view

The confirmation page has two actions, and the first draft of it showed them as
two identical Rausch buttons. The cause was not the page: it carried
`ui-button-secondary`, and the class was losing a specificity fight in the
stylesheet. The base rule is `:root body .ui-button.ui-button` at (0,3,1), and
the variant rule was `body .ui-button.ui-button-secondary` at (0,2,1), so the
variant lost whatever the order in the file and drew the primary fill.

That means it was wrong everywhere rather than only here, and it came from the
revert of the neutral-button commit: the reverted version carried `:root` on
both rules, and reverting restored it to one of the pair and not the other.

Fixed by giving the variant rules the same `:root` and doubled class. The base
rule keeps the Rausch fill, because the fill was never the mistake. Any view
with two actions must show one obvious primary; the second takes the ink
outline DESIGN.md specifies for `button-secondary`.

## 5. What is deliberately not done

### 5.1 Changing an address does not re-open verification

`UserService.updateUser` still lets a signed-in person change their email, and the new address is
not confirmed. The honest fix needs the old address to keep working until the new one answers, or
somebody who mistypes is locked out of the account they were trying to correct. That is a
change-of-address flow, and half of one is worse than none. Recorded here rather than
half-implemented.

### 5.2 Abandoned accounts are not cleaned up

Somebody who registers and never enters the code leaves a row behind: an account that cannot be
signed into, holding an address against the person who really owns it. Nothing purges these. The
schema carries `idx_email_verifications_expiry` so a cleanup job by age is a query away, but a
scheduled job is not something this application has, and inventing one here would be the first.

### 5.3 The refusal paths in the code's own tests

`SessionBeanSmokeTest` covers registration leaving an address unconfirmed, the credentials still
authenticating it, a code being left waiting, and a malformed code being refused without cost. It
cannot cover the resend cooldown or running out of attempts, because both raise, and a raise inside
that test poisons the single transaction everything else in it shares — the same reason its class
comment gives for the other rejection cases. Those two are exercised by hand.

## 6. Reproducing the checks

```bash
./scripts/deploy.sh
./database/load.sh          # DESTRUCTIVE: drops and reseeds. Also applies the new schema.
```

Then, with no SMTP host configured, the confirmation email is rendered into the server log instead
of being sent — which is what makes the flow demonstrable on a laptop:

```bash
tail -f tools/glassfish7/glassfish/domains/domain1/logs/server.log
```

Register an account in the browser, read the six digits out of the `MAIL` block in the log, and
enter them on the confirmation page. To see the expiry path without waiting fifteen minutes:

```sql
UPDATE email_verifications SET expires_at = NOW() - INTERVAL '1 minute'
WHERE consumed_at IS NULL;
```

The page then says the code has expired, and the resend button works immediately rather than after
the cooldown, which is the whole of 4.4.
