# Verifying TripMate with Google

**Status:** prepared, not submitted
**Date:** 2026-10-02

---

## 1. Read this first

**The application cannot be verified while it runs on `localhost`.** Google's reviewers fetch the
home page, the privacy policy and the terms over the public internet, with no session, and the
redirect address registered against a web OAuth client has to be HTTPS. A laptop is none of
those things. Everything below is preparation; the submission itself needs a real hostname.

Two things must be true before anything is sent:

1. **The application is deployed somewhere public, over HTTPS.** Any host will do, but see
   section 6 on `TM_TRUST_PROXY`, because the address the app shows and the one Google is given
   both depend on it.
2. **The placeholder contact address is replaced.** Both documents say
   `privacy@tripmate.example`, which is a placeholder and not a working address. Google checks
   that the contact in a privacy policy works.

```bash
grep -rn "tripmate.example" tripmate-web/src/main/webapp/privacy.xhtml \
                            tripmate-web/src/main/webapp/terms.xhtml
```

## 2. The choice to make before starting

There are two routes, and they are worth about a month of waiting apart.

| | `calendar.events` | `calendar.app.created` |
| --- | --- | --- |
| Sensitivity | **sensitive** | non-sensitive |
| Google verification | required, 2 to 8 weeks | **not required** |
| What it can touch | events in calendars the reader already has | a secondary calendar the application itself creates |
| Where the plan appears | the reader's own calendar | a separate "TripMate" calendar they can show or hide |
| Can it read or change events the reader made by hand? | no, but it shares a calendar with them | no, and it cannot even see them |

**TripMate currently requests `calendar.events`**, because writing into the calendar somebody
already looks at is the point of the feature. The cost of that choice is verification.

`calendar.app.created` would remove the verification step entirely. The trade is real: the plan
would land in its own calendar rather than beside the reader's other appointments, and the scope
is thinly documented, with developer reports of `PERMISSION_DENIED` and no way to reach calendars
the application did not create. It would also mean creating a calendar on first connect rather
than writing into `primary`.

Switching is contained: the scope constant in `GoogleCalendarService`, the `calendar_id` the
sync writes to, and creating that calendar once on first connect. Nothing else about the design
changes, because the sync already treats `calendar_id` as a value rather than assuming
`primary`. That said, it has not been done, and doing it would need its own round of testing.

Everything below assumes `calendar.events` and full verification.

## 3. What is already in place

| Requirement | Where |
| --- | --- |
| Home page, public, describing the app | `/index.xhtml` |
| Privacy policy, public, on the same domain | `/privacy.xhtml` |
| Terms of service, public | `/terms.xhtml` |
| Limited Use disclosure | section 4 of the privacy policy |
| What is done with Google data, and what is not | section 4 of the privacy policy |
| How to revoke access, in the app and at Google | section 4 of the privacy policy |
| Retention and deletion | sections 4 and 8 of the privacy policy |
| Disclosure at the point of consent | the connect panel on the trip page, linking the policy |
| One sensitive scope, the narrowest that works | `calendar.events`, and no `calendar` or `calendar.readonly` |
| The token encrypted at rest | AES-GCM, key outside the database |

All three pages are reachable with no session, which is how a reviewer arrives. That is
`AuthenticationFilter.PUBLIC_PATHS`, and it is asserted below.

### 3.1 The first submission was rejected, and why

Two reasons came back together:

> Your homepage does not explain the purpose of your app.
> Your homepage is behind a login page.

Both were about the same page, and both were fair. The home page was a two-column shell: a card
carrying a sign-in button, a create-account button and **a published demonstration password**,
beside a decorative panel of slogans. Two things made it worse than it sounds.

The first is that the panel held the entire description of the product, and it collapsed away
below 900px. On a narrow viewport the home page was a sign-in card and nothing else: a login page
with no explanation, which is precisely what the review said. Google's fetcher and any reviewer
using a narrow window were reading a different page from a desktop visitor.

The second is that the demonstration password was on it. That is a development affordance, and on
a page whose audience is the public it reads as a login page with the credentials helpfully
supplied.

It was rebuilt as a single column of prose that happens to have a way in:

- The purpose is the content, not a caption: what a group trip is like without the application,
  what TripMate does about it, and who it is for.
- The sign-in controls are one band of the page rather than the page itself.
- **Nothing is hidden at any width.** Verified at 390px: all six feature cards visible, every
  section visible, and no block of text hidden by CSS anywhere on the page.
- The demonstration password moved to the sign-in page, where somebody who wants the sample data
  is already looking.
- A section on the Google Calendar integration, saying what it asks for, what it does not do, and
  how to turn it off. This is the page a reviewer reads to decide whether the scope is justified,
  so the answer is on it rather than only in the justification box.

**A note for the next attempt.** The rejection said "behind a login page" while the home page was
in fact answering `200` to anonymous requests with no redirect; the old page simply read as one.
If the next attempt is rejected the same way, check what URL was submitted and what it serves with
no cookie, because an interstitial in front of the application (a tunnel's warning page, for
instance) looks exactly like a gate that the application itself does not have.


## 4. The consent screen, field by field

**APIs & Services → OAuth consent screen.**

| Field | Value |
| --- | --- |
| User type | External |
| App name | `TripMate` |
| User support email | a working address; the one in the documents |
| App logo | a 120×120 PNG, if branding verification is wanted |
| Application home page | `https://<host>/tripmate/index.xhtml` |
| Application privacy policy link | `https://<host>/tripmate/privacy.xhtml` |
| Application terms of service link | `https://<host>/tripmate/terms.xhtml` |
| Authorized domains | `<host>` only, assuming the app is served from its root domain |
| Developer contact information | a working address |

**Data access → Add or remove scopes.** Add exactly one:

```
https://www.googleapis.com/auth/calendar.events
```

`openid` and `email` are added automatically for sign-in purposes and are not sensitive.

**Authorized redirect URIs** on the OAuth client (Credentials → the client):

```
https://<host>/tripmate/calendar/google/callback
```

The existing `http://localhost:8080/tripmate/calendar/google/callback` can stay on the client
for local development, but the verification is done against the HTTPS one. The address is built
from the request, so the application follows whichever host it was reached on without a code
change.

## 5. Scope justification

Paste this into the justification box for `calendar.events`:

> TripMate is a group trip planner. Several people plan one trip together: they build a shared
> day-by-day itinerary with times and places, log what each of them paid, and see who owes whom.
>
> The requested scope is used for one thing: writing that itinerary into the calendar of the
> person who granted it, so the plan appears beside the appointments it has to fit around. The
> user connects their calendar deliberately, from a dialog that explains what will be written and
> links to the privacy policy. Each itinerary item becomes one calendar event, carrying the item's
> title, date, times and place. When an item is edited the event is updated in place, and when an
> item is deleted the event is removed.
>
> A narrower scope is not sufficient. The application has to write events into a calendar the user
> already has, and to update and delete events it wrote earlier. `calendar.app.created` would
> confine it to a calendar the application creates itself, which cannot put the plan where the
> user's other commitments are, and is why that option was rejected. `calendar.readonly` would
> allow none of the writing. `calendar` is broader than needed: it would grant control of the
> user's calendar settings and sharing, which this application never touches.
>
> The application does not read the user's existing events, does not share calendar data with
> anyone, does not use it for advertising, and does not transfer it. The permission is requested
> only when a user chooses to connect a calendar, and it can be withdrawn in the application or at
> myaccount.google.com/permissions.

## 6. Deploying so that the address is right

The calendar address and the OAuth redirect are both built from the request. Behind a tunnel or a
proxy the container sees plain HTTP on a local port rather than the reader's HTTPS hostname, so
the deployment has to say it is behind one:

```bash
TM_TRUST_PROXY=true ./scripts/glassfish-setup.sh    # read per request, no redeploy
```

Without it the redirect address sent to Google is `http://localhost:8080/...`, which will not
match the registered URI and the connection will be refused with `redirect_uri_mismatch`.

**Domain verification** is separate from all of this and is done in
[Google Search Console](https://search.google.com/search-console) with a Google account that is
an Owner or Editor on the Cloud project. Verify the host, then add it under **Authorized
domains** on the consent screen. Nothing is submitted successfully until it shows as verified.

## 7. The demo video

Upload to YouTube as **Unlisted** and put the link in the consent screen. It has to be one
continuous take with no cuts, in English, and reviewers reject videos that do not show what they
ask for. Scene by scene:

1. **Start on the home page.** The browser address bar must show the host and the page. Say what
   TripMate is in one sentence.
2. **Show the OAuth client ID in the address bar.** This is the step people miss. Navigate
   directly to Google's authorization URL, which puts `client_id=...` in the address bar, and let
   it load the consent screen. The client ID must be legible.
3. **Show the consent screen.** It must display the app name "TripMate". Tick **every** permission
   box, including Calendar, and point out that the application asks for calendar events and for
   nothing else.
4. **Show the application before connecting.** Open a trip, show the itinerary with its days and
   times, and open the dialog that offers the connection.
5. **Connect, and show it took.** The dialog should now name the Google account.
6. **Press "Sync now"**, then switch to Google Calendar in another tab and show the events that
   appeared, with the right dates and times.
7. **Edit an itinerary item in TripMate**, then show the same event changed in Google Calendar
   rather than a second event appearing.
8. **Delete an itinerary item**, then show the event gone from Google Calendar.
9. **Disconnect**, and show the connection removed.

Steps 7 and 8 are the ones that demonstrate the scope is genuinely used rather than requested.

## 8. Publishing, and the seven-day trap

**A verification cannot be requested while the app is in Testing.** The consent screen must be
published to Production first, and for a sensitive scope that means the verification is what
makes it usable at scale.

Until it is verified, two limits apply and one of them will bite:

- Only accounts added as **test users** can authorise the app, up to 100.
- **Refresh tokens expire after 7 days** while the app is in Testing, so a connection stops
  working weekly. This is Google's policy for unverified apps and it is why the connection
  appeared to work and then quietly stopped.

The 7-day expiry disappears once the app is published to Production and verified.

## 9. Checklist

- [ ] Replace `privacy@tripmate.example` in both documents with a working address
- [ ] Deploy on a public hostname over HTTPS
- [ ] Set `TM_TRUST_PROXY=true` on that deployment
- [ ] Verify the domain in Search Console
- [ ] Add the HTTPS redirect URI to the OAuth client
- [ ] Confirm `/tripmate/privacy.xhtml` and `/tripmate/terms.xhtml` load with no session
- [ ] Fill in the consent screen fields from section 4
- [ ] Paste the justification from section 5
- [ ] Record and upload the video from section 7, unlisted
- [ ] Publish the app to Production
- [ ] Submit, then expect 2 to 3 days for branding and 2 to 8 weeks for the rest

## 10. Why these pages exist at all

Worth stating, because it is easy to treat them as paperwork: the privacy policy is the document
somebody reads to decide whether to let this application write into their calendar. It is written
to be true of what the code does, and the claims in it that are easy to overstate are the ones
stated most narrowly. Anything that changes the code's behaviour with Google data should change
that page in the same commit.
