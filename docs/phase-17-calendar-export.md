# Phase 17: Getting the Plan into a Calendar

**Status:** complete
**Date:** 2026-10-01
**Milestone:** *A plan made here appears in the calendar the group already uses, and stays current.*

---

## 1. What this phase was

A trip is planned against everything else in the group's lives. TripMate held the plan and
nothing else did, so working out whether the trek back from Kala Patthar lands on the same day
as something already booked meant reading one screen and remembering it while looking at
another.

This phase lets a trip's itinerary out of the application as a calendar file, in the one format
every calendar application reads, and gives the trip an address those applications can subscribe
to so their copy keeps up with the plan on its own.

## 2. Deliverables

| Area | What was added |
| --- | --- |
| Format | `ICalendar`, an RFC 5545 writer with folding, escaping, stable identifiers and a `SEQUENCE` that moves |
| Business tier | `CalendarService` and `CalendarServiceRemote`: the rules, the refusals, and the subscribe address |
| Transport | `CalendarServlet` for the download, `CalendarFeedServlet` for the subscription, `CalendarResponse` for what they have in common |
| Storage | `trip_calendar_feeds`, and `revision` and `updated_at` on `itinerary_items` |
| Web tier | `CalendarFeedDTO`, the bean methods, a toolbar button, a dialog, and a copy button |
| Styles | `.tm-dialog-steps` and the calendar dialog's own rules |

## 3. Design decisions

### 3.1 A calendar file, not an integration

Google Calendar, Apple Calendar and Outlook all offer an API, and all three would have needed an
application registered with them, a client secret kept somewhere, a callback address that
resolves from the public internet, and a consent screen for each reader. That is a large amount
of machinery to place a plan beside a dentist's appointment, and it would have married the
application to one vendor to do it.

A calendar file needs none of it. It is the interchange format the whole category reads, it is
produced by a pure function of data already in the database, and it works the same for the three
clients and for the ones nobody here has heard of. It also means the feature is not Google-only:
the same address works in Apple Calendar and Outlook, which matters for a product rather than for
a demonstration.

### 3.2 Two ways out, and why the subscriber is the default

**A subscription** is what most people mean by "add it to my calendar". The address goes into the
calendar application once and the application fetches it on its own schedule, so an edit made
here reaches a phone without anybody doing anything. This is the option the dialog offers first.

**A download** is a file the reader imports once. It never updates, and section 3.8 explains why
importing a second copy of it can leave two copies of everything.

The download still exists, and is still offered in the same dialog, because it is the only one of
the two that needs nothing from anybody. A subscription needs the application to be reachable
from the internet, which a copy running on a laptop is not. The fallback that always works is
worth one line of the dialog.

### 3.3 Servlets, because these are files and not views

Everything else in the web tier is a Faces view. These are GETs whose entire body is a file, and
Faces is the wrong shape for that: a Facelets download runs through an Ajax action and a cookie
the generated JavaScript has to carry, so the address cannot be bookmarked, cannot be fetched
again by refreshing, and cannot be fetched at all by anything that is not a browser running the
page. The second of those matters twice over here, because the caller of the feed is not a
browser at all.

The price is stated in `CalendarServlet`'s and `CalendarFeedServlet`'s own documentation and is
worth repeating, because it is the trap in this whole phase: **`AuthenticationFilter` is mapped to
`*.xhtml`, so it does not see either address.** Anything outside that mapping is reachable by
anyone who types it. Each servlet therefore does its own check, and each check is different,
because the two callers are.

### 3.4 The feed's address is its authorisation

Google Calendar, Apple Calendar and Outlook fetch a subscribed calendar from their own servers,
on their own schedule, with no browser and therefore no session and no cookie. There is nothing
to check a user against. So the address itself is the credential: it carries 256 bits of random
that name one trip, and holding it is what entitles the caller to read that trip's plan.

This is the only place in the application where a URL alone is enough, so it is worth being
explicit about what contains it:

- The token is minted by the same `TokenHasher` the password reset links use.
- It grants one trip, and only its itinerary. It is not an account.
- It can be taken away. An administrator can issue a new one, and every client holding the old
  address stops being able to read.

**The token is stored in the clear, and that is a decision rather than an oversight.** A reset
token is spent the moment it is used and never needs to be shown again, so hashing it costs
nothing. This one has to be shown to its owner every time they open the dialog, because people
lose addresses and the only other way to give one back is to rotate it, which unsubscribes them
from their own calendar. What hashing would buy is protection against a leak of this table, and
that is worth very little: the itinerary the token guards is in the same database, in
`itinerary_items`, two tables away, and anyone who can read one can read the other. The control
that does matter here is revocation, and rotation is it.

The identical rule applies to the thing the token is written into: `TripCalendarFeed.toString`
does not include it, because that string reaches log files.

### 3.5 A live trip can leave; a finished one cannot

The plan, the subscription and the Google connection are all offered for a trip that is being
planned **or one that is under way**. Those two have the thing that matters in common: somebody
is still going to act on the plan. A completed trip's itinerary is a record of what happened and
a cancelled one's is a plan nobody is following, so neither is offered. `TripStatus.isLive()`
names it, and the page and the service both ask it.

**This was planning-only at first, and that was backwards.** The reasoning was that once a trip
starts the plan has stopped being a proposal, so a fresh export offers nothing except the chance
to overwrite what the reader has changed by hand. The reasoning ignored when a plan is actually
wanted: on the day, in a pocket, on the way to the airport. Refusing an ongoing trip while
offering the one planned for next month is the wrong way round, and it was reported as a
missing feature rather than defended.

**The feed goes further still and serves any status.** It is not a fresh export but a standing
request somebody made on purpose, and a calendar application polling it has no way to tell the
reader that the answer has changed to "no". Refusing would leave an error in their calendar and
empty the entries they had just filled. So the feed answers whatever its token names.

The rule is enforced in `CalendarService`, not in the page. The page asks the same question so
that the control is not offered for work that would be refused, and the service asks it again
because hiding a control is a convenience for the reader and is not a guard against a request
that was written by hand. `docs/phase-15-security.md` makes the same argument about the
authorization checks that already existed; this is one more place it applies.

### 3.6 Times are UTC instants, converted through the trip's zone

This was written the other way first, and how it was wrong is the most useful thing in this
document.

An itinerary item is a wall clock: `item_date`, `start_time`, `end_time`, and no zone of its
own. RFC 5545 has an exact representation for that, a `DTSTART` with no `TZID` and no trailing
`Z`, meaning "at this clock time, wherever you are", and that is what was written. The reasoning
was that inventing a zone would be worse than omitting one.

It was wrong because of what a reader does with the ambiguity. Google Calendar's importer takes
a floating time to mean **UTC**. A trek out of Lukla planned for 07:00 and ending at 12:00
appeared in a Kathmandu calendar at 12:45 to 17:45, which is exactly the +05:45 the zone is
worth. Nobody had touched the plan; it had moved to the middle of the afternoon.

So a trip now carries a zone (`trips.timezone`, an IANA id, chosen in the trip settings dialog),
and every time goes out as a UTC instant converted through it. The wall clock the reader typed
is preserved exactly, because the conversion puts it back in the same zone on the way out; what
changes is that the file no longer depends on a client choosing to resolve an ambiguity the way
Google does.

Two details fall out of doing it in this order:

- **The default hour is added before the conversion**, so an item with a start and no end stays
  an hour long across a daylight-saving boundary instead of becoming one hour and some minutes.
- **An all-day event still carries a plain date.** It has no time, so it has no zone, and
  `VALUE=DATE` is already unambiguous.

A null zone reads as the server's own, which is right for a trip planned where the server is.
New trips are given the server's zone explicitly rather than left null, so the fallback is
something a reader can see and correct rather than something being applied quietly.

### 3.7 An item with no time is a whole-day event

Plans are often "somewhere in Pokhara on Saturday" and the time is genuinely not decided.
Defaulting to 09:00 would put a commitment in the reader's calendar that nobody agreed to, and
would look like a decision the group had made. An all-day event is what the plan actually says.

Where an item does have a start time and no end time, the writer gives it an hour. A calendar
entry is not allowed a duration of nothing and the alternative is to leave the end off and let
the reader's client choose instead, so the application chooses, once, and says so. The same hour
applies to an item whose end equals its start, which the schema permits and which no calendar can
draw.

### 3.8 What makes a subscription follow the plan

Three things, and the download has only the first of them:

1. **A stable `UID`.** Each event is identified by its itinerary item's id, which the database
   never reuses and never changes. Two copies of an event are related by this.
2. **A `SEQUENCE` that moves.** This is what says which of the two is newer. It is written from
   `itinerary_items.revision`, a column added for it, which `ItineraryService.updateItem` counts
   up on every edit. Without it a client sees an event it already has and ignores the new one,
   and the edit never leaves this application.
3. **A target that consumes the feed repeatedly.** A calendar application polling an address does
   this. A file imported once does not, which is why the download can promise nothing here, and
   why the dialog says instead that importing a second copy may leave two copies of the plan.

Counting the revision in the database rather than deriving it is deliberate: nothing else in the
row records that an edit happened. `created_at` is set once and stays put, so without the column
an edited item would be indistinguishable from one the reader had already seen.

A deleted item needs nothing. An event that is simply absent from the feed is removed from a
subscribed calendar, which is the correct representation of "this is no longer planned".

### 3.9 The same data renders to the same bytes

`DTSTAMP` and `LAST-MODIFIED` are written from the item's own stored times rather than from the
moment of the request, so an unchanged plan produces a byte-identical file every time it is
asked for. That is what allows the endpoint to compute an `ETag` by hashing what it is about to
send and answer a poll whose tag matches with a bare `304`.

It matters more than it looks. A calendar application polls on a schedule of its own, and a feed
that re-stamps itself on every read can never answer `304`, so every poll costs a full body
whether or not anything changed. The stable rendering is the difference between a feed that is
cheap to poll and one that is not.

The tag is a SHA-256 of the body, truncated, rather than a checksum, because a collision here is
not a security failure but is a real one: two different plans sharing a tag means a calendar is
told its copy is current when it is not.

### 3.10 An address on a laptop cannot work, and the dialog says so

Google fetches a subscribed calendar from its own servers, so `http://localhost:8080/...` is not
an address it can reach. The error it reports says "could not fetch URL" and nothing about why,
which is exactly the kind of failure that makes a working feature look broken.

So the dialog asks whether the address it is showing is one a calendar application could fetch,
and explains when it is not. The check is deliberately conservative: it recognises the names and
private ranges that are plainly this machine or a private network, and it cannot catch a public
hostname that resolves to a private address from the outside, so the note is written to cover
that case too.

## 4. Bugs found by running it

### 4.1 A missing trip answered differently from a trip you are not on

The first version of `exportItinerary` read the trip and then checked membership. A request for a
trip id that does not exist therefore failed with `no such trip`, while a request for a real trip
the caller is not on failed with `you are not a member`. Two different sentences, and the
difference between them is an oracle: walk the ids and the responses say which ones are real.

`TripAccess.requireMember` is written not to do that, and `TripService.emailTripSummary` already
puts the membership check first for exactly this reason. The fix is to match it, and the
verification now checks that a non-member's answer for a real trip and for a nonexistent one are
byte-identical.

### 4.2 Folding counts octets, and the tests were written in ASCII

The first cut of `ICalendar.fold` counted characters. Every case it was tried against was
English, where the two are the same, and it looked correct. The test data added for the
non-ASCII case is a Devanagari title, where one character is three octets, and the difference
decides whether a folded line is 75 octets or 75 characters, which is to say whether a strict
parser accepts the file.

### 4.3 The new buttons did nothing, for the reason written above them

The itinerary tab carries a comment explaining that a control which opens a dialog must carry
`process="@this"`, because the dialog's fields are required and live in the same form: without
it, pressing the control validates the empty fields, the validation fails, and the action never
runs.

The two buttons added to the calendar dialog were written without it, and did nothing. Clicking
one produced no error, no toast and no change, because the failure was a validation message
rendered into the item dialog, which was closed at the time. This is the second time this exact
fault has been made in this view, which is why the comment is there; it is worth reading before
adding a control to that form.

### 4.4 The build's own canary caught the schema change

`scripts/test-matrix.sh` asserts that a cold load produces exactly nine tables. It was right to
fail when this phase added a tenth, and the count is now ten. The assertion is deliberate: it is
the one place that notices a table appearing or disappearing without anybody deciding to.

### 4.5 Every time was five and three-quarter hours out

Reported after the feature had been subscribed to successfully: the app showed 07:00 to 12:00
for a trek on 2026-11-06, and the calendar showed 12:45 to 17:45.

The difference is exactly +05:45, which is Nepal, and that is the whole diagnosis: the times
were written as floating values and Google's reader takes a floating time to mean UTC. Section
3.6 has the rest, including why the reasoning that produced floating values was wrong.

The lesson is narrower than "test with a real client", which this had been: the feature worked,
the file was valid, and an event appeared. It was simply at the wrong time of day, and only
somebody who knew when the trek was could see it. **Validity is not correctness.**

### 4.6 A duplicated select and a stale expression

While adding the zone picker, an edit left two copies of the control behind, one of them
disabled, and the surviving one referenced `#{z.label}` on a list of plain strings. Facelets
threw `PropertyNotFoundException` on every render of the trip page, and because the failing
component was inside a dialog the page still returned 200 with most of itself intact; what was
missing was subtle enough to miss in a browser and obvious in the log.

It is recorded because the first fix did not take: the edit that removed the duplicate kept the
bad expression, and the page stayed broken. Asserting the new state is not the same as reading
it.

## 5. Verification

Driven against the deployed application, with the responses read rather than the code.

**The download.** Signed in as a member of two trips.

| Request | Result |
| --- | --- |
| Trip 2, `PLANNING`, member | `200`, `text/calendar; charset=UTF-8`, a file |
| Trip 1, `ONGOING`, member | `200`, a file |
| Trip 3, `COMPLETED` | `403`, "only a trip that is still to come or under way..." |
| trip 3 set to `CANCELLED` | `403`, the same sentence |
| Trip 2, signed in as a non-member | `403`, and the same sentence as for a trip that does not exist |
| Trip id 999998, which does not exist | `403`, the identical sentence |
| `tripId=abc`, no `tripId` | `400` |
| No session at all | `302` to the sign-in page |

**The feed.** With no session and no cookie, which is how a calendar application arrives.

| Request | Result |
| --- | --- |
| A valid token | `200`, `text/calendar`, `inline`, an `ETag`, `private, no-cache` |
| The same request with that tag | `304`, no body |
| Two full reads | byte-identical |
| An unknown token | `404` |
| A malformed token, and no token at all | `404` |
| The old token after a rotation | `404` |

**The subscription follows an edit.** An item was edited through the page's own editor, as the
trip's member:

- the row's `revision` went from 0 to 1 and its `updated_at` moved
- the feed, fetched anonymously, then carried `SEQUENCE:1` and the new title for that item, while
  the four untouched items still carried `SEQUENCE:0`
- the `ETag` changed from `4d03de3b...` to `e65a2a9c...`, so a subscriber's next poll receives
  the new file rather than a `304`

The row was restored to its seeded values afterwards.

**The file.** Decoded and checked rather than eyeballed:

- every line break is CRLF, with no bare `\n` in the file
- the longest physical line is 75 octets, and no line exceeds it
- every continuation begins with exactly one space, never two
- the file decodes as UTF-8, so no character was split by folding
- unfolding reproduces the original values, including a title written in Devanagari

Content was checked against rows put in the database for the purpose:

| Case | Written as |
| --- | --- |
| Item with no times | `DTSTART;VALUE=DATE:20270102`, `DTEND;VALUE=DATE:20270103` |
| Started, never ended | `DTSTART:20270103T100000`, `DTEND:20270103T110000` |
| End equal to start | `20270104T130000` to `20270104T140000` |
| Started at 23:30, no end | `20270105T233000` to `20270106T003000`, rolling the day |
| `Lunch; then a walk` | `SUMMARY:Lunch\; then a walk` |
| `Line one\nLine two, with a \\ and a ;` | `Line one\nLine two\, with a \\ backslash and a \; semicolon` |
| Non-ASCII trip name | `filename="Pokhara.ics"; filename*=UTF-8''%E0%A4%AA...` |

Those rows were removed afterwards.

**The zone conversion.** The reported case, checked against the row it comes from:

| | |
| --- | --- |
| `itinerary_items` for item 8 | `2026-11-06 07:00:00` to `12:00:00` |
| what the feed writes | `DTSTART:20261106T011500Z`, `DTEND:20261106T061500Z` |
| what a Kathmandu calendar renders that as | `2026-11-06 07:00` to `12:00` |

An item with no start time still writes `DTSTART;VALUE=DATE:20261107` and
`DTEND;VALUE=DATE:20261108`, which is a one-day event with no zone to get wrong.

**The page.** Read in a real browser at 1440px, 420px, and in both themes:

- the control is offered on a planning trip and on an ongoing one, and on neither a completed
  trip nor a live trip with an empty itinerary
- the dialog opens, the address is shown in a read-only field, and the copy button reports
  `Copied` and puts the whole address on the clipboard
- "Replace this address" appears for the trip's administrator and not for a plain member
- replacing it asks for confirmation, issues a new address, and the old address then answers
  `404`
- the address on a laptop is flagged as one Google cannot fetch
- at 420px the dialog fits, both steps wrap, and the footer controls stay on one row

**The suites.** The diagnostics page runs 291 checks, all passing, including two added here:

- `ItineraryService`: a new item starts at revision 0
- `ItineraryService`: an edit counts up once
- `Authorization`: a plain member cannot reset a trip's calendar address

`scripts/test-matrix.sh` passes 17 of 17, including the cold load that builds the schema, the
sample data and the integrity checks from an empty database.

## 6. Reproducing

```bash
./scripts/deploy.sh
```

Then, signed in, open a trip that is being planned or one that is under way:

```
http://localhost:8080/tripmate/trips/detail.xhtml?tripId=2
```

The button is in the Itinerary tab's toolbar, and the dialog offers both ways out.

To watch the feed update without a calendar application, take the address from the dialog and
poll it. It needs no cookie, which is the point:

```bash
curl -i "http://localhost:8080/tripmate/calendar/feed/<token>.ics"
```

For the subscription to work in a real calendar, the application has to be reachable from the
internet. A tunnel is enough for a demonstration, and it needs one setting, because a tunnel
terminates TLS and forwards plain HTTP to a port on this machine: the container then sees a
request that is not the one the reader made, and an address built from it would name `localhost`
on port 8080, which is not an address Google can fetch.

```bash
TM_TRUST_PROXY=true ./scripts/glassfish-setup.sh    # no redeploy; read per request
cloudflared tunnel --url http://localhost:8080
```

`ExternalAddress` then reads `X-Forwarded-Proto` and `X-Forwarded-Host`, so opening the
application through the tunnel produces an address on the tunnel's own hostname and scheme. It
is off by default, because a request carries those headers whether or not anything in front of
the application put them there.

**Both halves were checked without a tunnel**, by sending the headers a proxy would add on the
request that opens the dialog, which is where the address is rendered:

| `X-Forwarded-Proto` / `X-Forwarded-Host` | Address with the flag off | with the flag on |
| --- | --- | --- |
| `https` / `trips.example.com` | `http://localhost:8080/...` | `https://trips.example.com/...` |
| `https` / `trips.example.com:8443` | as above | `https://trips.example.com:8443/...` |
| `http` / `plain.example.com` | as above | `http://plain.example.com/...` |
| `https` / `edge.example.com, internal.local:8080` | as above | `https://edge.example.com/...`, the first entry only |
| `https` / `[2001:db8::1]:8443` | as above | `https://[2001:db8::1]:8443/...` |

With the flag off the headers are ignored and the page still warns that the address is on this
machine; with it on the address is the forwarded one and the warning goes, because it is now an
address a calendar application could reach.

## 7. Notes for later phases

- **Connecting to Google Calendar directly is the next step, and it is the one the feed cannot
  replace.** A feed is one-way, polled on somebody else's schedule, and needs a public address.
  An OAuth connection would push an edit the moment it is made, into a calendar the reader
  chooses, with no address to look after. It needs a Google Cloud project and an OAuth client;
  while the consent screen is in Testing, Google expires refresh tokens after seven days, so it
  stops working weekly until the app is published to Production. The shape it needs on this side
  is a refresh token stored per user, a Google event id per item to update and delete against, and
  handling for a token that has been revoked.
- **A time zone on the trip** is done; see section 3.6. What is not done is a trip that starts in
  one zone and ends in another, which a long itinerary across a border would need.
- **A revoked token leaves no trace.** A subscriber whose administrator replaces the address
  simply stops receiving updates, and their calendar shows the last copy it fetched, for as long
  as the client keeps it. Nothing tells them. An email to the trip's members when the address
  changes would close that, and `MailService` is already there.
- **The feed is not rate limited.** A token is unguessable, so there is nothing to brute force,
  but anything holding a valid address can ask for the file as often as it likes. That is bounded
  by the work involved, which is one indexed lookup and a rendering, and it is not bounded by
  anything else.
- **The whole trip, not only the itinerary.** Expenses and repayments have dates and could be
  events too. Nothing needs them, and a calendar that fills with a row per coffee is worse than
  one that shows the plan.
- **The address is built from the request, and behind a proxy that is opt-in.** Covered in
  section 6, where the flag is to be set.

## 8. A separate fix: the buttons that close a dialog

**Reported:** "on all modal the cancel button does not close the modal".

**Cause.** A `p:commandButton` with no `process` attribute processes the whole form, so the
request carries every input in it. Every dialog that collects something has required fields, and
on the state that matters most, the form a reader has just opened and not yet filled in, those
fields are empty. So the request failed its own validation, the action never ran, and the dialog
stayed exactly where it was.

The failure is worse than it sounds because of where the message goes. Pressing Cancel on the
itinerary editor produced a required-field error rendered into the item dialog, which was the
one that was open, so it looked like a dead button. Pressing it on the calendar dialog produced
the same error inside the *item* dialog, which was closed, and so produced nothing visible at
all.

**This is the same rule the itinerary tab already documents for the buttons that open a dialog.**
The comment at the top of that tab explains that a control which opens a dialog must carry
`process="@this"`, because the dialog's fields are required and live in the same form. It had
simply never been applied to the buttons that close them, and this phase walked into it twice:
the two buttons added to the calendar dialog had the fault as well, which is what
section 4.3 records.

**The fix** is `process="@this"` on every control whose whole job is to dismiss: it narrows the
request to the button, so nothing is converted or validated, and the action reads no input
anyway. Nine of them, across three pages:

| Page | Buttons |
| --- | --- |
| `trips/detail.xhtml` | Trip settings, Keep the trip, the item editor, the calendar dialog, Add someone, Email a summary, the summary log entry |
| `trips/list.xhtml` | New trip |
| `trips/settle.xhtml` | Record a repayment |

The buttons that *save* deliberately keep the default, because validating what is being saved is
the point of them.

**Two more of the same defect, found by auditing for it.** The expenses form's "Split equally"
and "Clear shares" also processed the whole form, so the required description, amount and date
refused them. `ExpenseBean.splitEqually` opens with a guard for exactly that case, saying "Enter
the amount before splitting it", and that sentence was unreachable: the view rejected the request
before the bean ever saw it. Both now carry `process="@this"`, and `splitEqually` also renders the
toast region, without which its own message would have been raised into a part of the page the
response did not touch.

**This was pre-existing.** The buttons that close these dialogs are unchanged in `HEAD`; the
fault arrived with the dialogs themselves. It was found by the person using the application and
not by anything in section 5, which is worth noting: every check in this project drives a page to
a result, and none of them pressed Cancel.

## 9. Next: the Google Calendar API

Section 3.1 argues that a file is the right first answer, and it still is: it needs no account
with anybody and works in every client. The connection described in section 7 is what turns "add
this to your calendar" into "keep my calendar in step with this trip" without asking the reader
to paste an address into another product at all.
