package com.tripmate.mail;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import com.tripmate.dto.CategoryTotalDTO;
import com.tripmate.dto.ItineraryItemDTO;
import com.tripmate.dto.MemberBalanceDTO;
import com.tripmate.entity.TripStatus;
import com.tripmate.entity.TripSummaryKind;

/**
 * Builds the application's transactional email.
 *
 * <h2>What "works in every client" actually requires</h2>
 *
 * Email clients are not browsers, and the ones that matter most are the oldest.
 * Outlook on Windows renders with Microsoft Word's engine, which has no support
 * for {@code max-width}, {@code flex}, {@code border-radius} on some elements,
 * or modern CSS selectors. Gmail strips {@code <style>} blocks from some
 * contexts and rewrites others. Apple Mail and iOS Mail do support media
 * queries, and will also auto-invert colours in dark mode whatever is declared.
 *
 * <p>
 * So this file is written to the rules that survive all of that:
 *
 * <ul>
 * <li><strong>Tables for layout.</strong> Not a stylistic preference: a
 * {@code div}-based layout collapses in Outlook. Every spacing decision is
 * a cell padding or a fixed-height spacer row.</li>
 * <li><strong>Inline styles on everything that matters.</strong> A
 * {@code <style>} block carries the media queries and the client resets,
 * but nothing load-bearing depends on it, because some clients drop
 * it.</li>
 * <li><strong>A conditional wrapper for Outlook.</strong> The 600px container
 * is a {@code max-width} that Outlook ignores, so Outlook gets a
 * fixed-width table of the same size inside a {@code [if mso]} block
 * instead.</li>
 * <li><strong>A bulletproof button.</strong> A styled anchor becomes a
 * VML rounded rectangle in Outlook, so it is still a button rather than a
 * bare link.</li>
 * <li><strong>A preheader.</strong> The first line of body text that a client
 * shows next to the subject in the inbox list, set explicitly, with hidden
 * filler after it so the client cannot pull the greeting in instead.</li>
 * <li><strong>A plain-text part.</strong> Written by hand for each message
 * rather than stripped from the HTML, because a stripped version reads
 * like a ransom note and scores worse with spam filters.</li>
 * </ul>
 *
 * <h2>Escaping</h2>
 *
 * Every interpolated value goes through {@link #escape}. A trip name, a
 * destination and a person's name are all user-entered, and one containing an
 * angle bracket would otherwise break the markup of the message it appears in.
 * There is no templating engine here, deliberately, in the same spirit as
 * {@code PasswordHasher} being hand-rolled: the escaping is one method, and the
 * alternative is a dependency that has to be kept out of the deployment
 * descriptor and explained.
 */
public final class Templates {

    // ------------------------------------------------------------------
    // Brand
    // ------------------------------------------------------------------
    // The tokens from DESIGN.md, inlined here because an email cannot reach the
    // stylesheet. Kept in one place so a rebrand is one edit.
    private static final String RAUSCH = "#ff385c";
    private static final String INK = "#222222";
    private static final String BODY = "#484848";
    private static final String MUTED = "#6a6a6a";
    private static final String HAIRLINE = "#dddddd";
    private static final String CANVAS = "#ffffff";
    private static final String SURFACE_SOFT = "#f7f7f7";

    /** The width every client is told to render at. 600 is the safe maximum. */
    private static final int WIDTH = 600;

    private static final String FONT_STACK = "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_SHORT = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private Templates() {
    }

    // ------------------------------------------------------------------
    // Confirm an address
    // ------------------------------------------------------------------

    /**
     * The message carrying the code that confirms a new account's address.
     *
     * <h2>A code rather than a link</h2>
     *
     * The password reset sends a link and this sends six digits, which is not
     * an inconsistency. A reset link is opened once, on whatever device is to
     * hand, and can carry 256 bits of unguessable token. A code has to be read
     * off one screen and typed into another, so it has to be short enough to
     * hold in the head for the few seconds that takes, and short means
     * guessable. The defence against guessing is not the length of the code but
     * the attempt limit behind it, which the message states.
     *
     * <p>The code is set as large, spaced, monospaced digits rather than set
     * into a sentence: it is read once, character by character, and a code that
     * looks like prose is transcribed wrongly.
     *
     * @param toEmail      the address the code was sent to
     * @param fullName     the name on the account
     * @param code         the six digits, already formatted
     * @param validMinutes how long the code lasts, said in the message so the
     *                     reader knows whether it is worth typing
     * @param maxAttempts  how many wrong tries the code accepts before it is
     *                     spent, also said in the message
     * @return the rendered message
     */
    public static Email verificationCode(String toEmail, String fullName, String code,
            int validMinutes, int maxAttempts) {
        String greeting = firstNameOr(fullName, "there");
        String heading = "Confirm your email address, " + escape(greeting);

        String body = paragraph("Enter this code on the TripMate page you came from to "
                + "finish setting up your account.")
                + codeBlock(escape(code))
                + paragraph("The code works for " + validMinutes + " minutes, and for "
                        + maxAttempts + " tries. If it runs out, ask for another one from the "
                        + "same page: nothing you have already filled in is lost, and your "
                        + "account is not affected either way.")
                + note("If you did not create a TripMate account, nothing has happened and "
                        + "nothing needs doing. Ignore this message and the code will expire "
                        + "on its own, and no account will be usable without it.");

        String html = layout(
                "Confirm your email address",
                "Your code is " + code + ". It lasts " + validMinutes + " minutes.",
                heading,
                body,
                "You are receiving this because this address was used to register at TripMate.");

        String text = greeting + ",\n\n"
                + "Enter this code on the TripMate page you came from to finish setting up\n"
                + "your account:\n\n"
                + "    " + code + "\n\n"
                + "The code works for " + validMinutes + " minutes, and for " + maxAttempts
                + " tries.\n"
                + "If it runs out, ask for another one from the same page: nothing you have\n"
                + "already filled in is lost, and your account is not affected either way.\n\n"
                + "If you did not create a TripMate account, nothing has happened and nothing\n"
                + "needs doing. Ignore this message and the code will expire on its own, and\n"
                + "no account will be usable without it.\n\n"
                + "--\n"
                + "You are receiving this because this address was used to register at\n"
                + "TripMate.\n";

        return new Email(toEmail, "Your TripMate confirmation code", html, text);
    }

    // ------------------------------------------------------------------
    // Welcome
    // ------------------------------------------------------------------

    /**
     * The message sent once an address has been confirmed.
     *
     * <p>Held back until then rather than sent at registration. The previous
     * arrangement congratulated somebody on an account they could not yet use,
     * and told them to sign in to a page that would refuse them.
     *
     * @param toEmail   the address to send to
     * @param fullName  the name they gave, used in the greeting
     * @param signInUrl the absolute URL of the sign-in page
     * @return the rendered message
     */
    public static Email welcome(String toEmail, String fullName, String signInUrl) {
        String greeting = firstNameOr(fullName, "there");

        String heading = "Welcome to TripMate, " + escape(greeting);

        String intro = "You now have somewhere to plan a trip with other people: one "
                + "shared itinerary, and one place where everybody's spending adds up.";

        String body = paragraph(intro)
                + paragraph("When you start a trip you become its administrator, so you can "
                        + "invite the others by email and change the trip's details. Everyone "
                        + "you add can plan the itinerary and log what they spend.")
                + paragraph("Nothing here is shared with anyone you have not added to a trip. "
                        + "There is no directory to browse.");

        String button = button("Sign in", signInUrl);

        String html = layout(
                "Welcome to TripMate",
                "Your account is ready. Sign in to start planning.",
                heading,
                body + button,
                "You are receiving this because this address was used to register at TripMate.");

        String text = greeting + ",\n\n"
                + "Welcome to TripMate.\n\n"
                + "You now have somewhere to plan a trip with other people: one shared\n"
                + "itinerary, and one place where everybody's spending adds up.\n\n"
                + "When you start a trip you become its administrator, so you can invite the\n"
                + "others by email and change the trip's details. Everyone you add can plan the\n"
                + "itinerary and log what they spend.\n\n"
                + "Nothing here is shared with anyone you have not added to a trip. There is\n"
                + "no directory to browse.\n\n"
                + "Sign in: " + signInUrl + "\n\n"
                + "--\n"
                + "You are receiving this because this address was used to register at\n"
                + "TripMate.\n";

        return new Email(toEmail, "Welcome to TripMate", html, text);
    }

    // ------------------------------------------------------------------
    // Added to a trip
    // ------------------------------------------------------------------

    /**
     * The message sent when somebody is added to a trip.
     *
     * @param toEmail       the address to send to
     * @param recipientName the person who was added
     * @param actorName     the administrator who added them
     * @param tripName      the trip's name
     * @param destination   where the trip goes, which may be null or blank
     * @param startDate     the first day, which may be null
     * @param endDate       the last day, which may be null
     * @param tripUrl       the absolute URL of the trip's page
     * @return the rendered message
     */
    public static Email addedToTrip(String toEmail, String recipientName, String actorName,
            String tripName, String destination,
            LocalDate startDate, LocalDate endDate, String tripUrl) {
        String greeting = firstNameOr(recipientName, "there");
        // Kept in both forms. The HTML part needs the escaped one, the plain-text
        // part needs the raw one, and deriving either from the other is how an
        // ampersand ends up reading as "&amp;" in a text-only client.
        String actorPlain = isBlank(actorName) ? "Someone" : actorName;
        String actor = escape(actorPlain);
        String tripPlain = isBlank(tripName) ? "a trip" : tripName;
        String safeTrip = escape(tripPlain);
        String when = describeDates(startDate, endDate);

        String heading = actor + " added you to " + safeTrip;

        StringBuilder facts = new StringBuilder();
        facts.append(factRow("Trip", safeTrip));
        if (!isBlank(destination)) {
            facts.append(factRow("Where", escape(destination)));
        }
        if (!when.isEmpty()) {
            facts.append(factRow("When", escape(when)));
        }

        String body = paragraph("You are on the trip now, so it appears in your list and you "
                + "can see its itinerary, its expenses and who owes what.")
                + factsTable(facts.toString())
                + paragraph("You can log what you pay for, add to the itinerary, and see exactly "
                        + "who owes what for each expense.");

        String button = button("Open the trip", tripUrl);

        String html = layout(
                "You were added to " + tripPlain,
                actor + " added you to " + safeTrip + " on TripMate.",
                heading,
                body + button,
                "You are receiving this because " + actor + " added you to a trip on TripMate.");

        StringBuilder text = new StringBuilder();
        text.append(greeting).append(",\n\n")
                .append(actorPlain).append(" added you to ")
                .append(tripPlain).append(" on TripMate.\n\n");
        if (!isBlank(destination)) {
            text.append("Where: ").append(destination).append('\n');
        }
        if (!when.isEmpty()) {
            text.append("When:  ").append(when).append('\n');
        }
        text.append('\n')
                .append("You are on the trip now, so it appears in your list and you can see its\n")
                .append("itinerary, its expenses and who owes what.\n\n")
                .append("Open the trip: ").append(tripUrl).append("\n\n")
                .append("--\n")
                .append("You are receiving this because ").append(actorPlain)
                .append(" added you to a trip on\nTripMate.\n");

        return new Email(toEmail, "You were added to " + tripPlain, html, text.toString());
    }

    // ------------------------------------------------------------------
    // The shell
    // ------------------------------------------------------------------

    /**
     * Wraps a message in the parts every client needs.
     *
     * @param title     the document title, which most clients ignore but some
     *                  use as a fallback subject
     * @param preheader the line shown beside the subject in the inbox list
     * @param heading   the message's headline, already escaped
     * @param content   the body, already assembled and escaped
     * @param footer    the reason-this-was-sent line, already escaped
     * @return a complete HTML document
     */
    private static String layout(String title, String preheader, String heading,
            String content, String footer) {
        String shell = """
                <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN" \
                "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">
                <html xmlns="http://www.w3.org/1999/xhtml" lang="en">
                <head>
                <meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>
                <meta name="viewport" content="width=device-width, initial-scale=1"/>
                <meta name="x-apple-disable-message-reformatting"/>
                <meta name="format-detection" content="telephone=no,address=no,email=no,date=no"/>
                <!-- Declares light only. Apple Mail and iOS Mail invert colours in dark
                     mode regardless, so claiming both and then fighting it produces a
                     worse result than declaring the one the design is built for. -->
                <meta name="color-scheme" content="light"/>
                <meta name="supported-color-schemes" content="light"/>
                <title>{{TITLE}}</title>
                <!--[if mso]>
                <noscript>
                  <xml>
                    <o:OfficeDocumentSettings>
                      <o:PixelsPerInch>96</o:PixelsPerInch>
                    </o:OfficeDocumentSettings>
                  </xml>
                </noscript>
                <![endif]-->
                <style type="text/css">
                  /* Resets. Some clients drop this block entirely, so nothing here is
                     load-bearing: it only stops clients adding their own spacing. */
                  html, body { margin: 0 !important; padding: 0 !important; height: 100% !important; width: 100% !important; }
                  * { -ms-text-size-adjust: 100%; -webkit-text-size-adjust: 100%; }
                  table, td { mso-table-lspace: 0pt !important; mso-table-rspace: 0pt !important; border-collapse: collapse !important; }
                  img { -ms-interpolation-mode: bicubic; border: 0; height: auto; line-height: 100%; outline: none; text-decoration: none; }
                  a { text-decoration: none; }
                  /* Outlook adds a default spacing to paragraphs; this removes it. */
                  p { margin: 0; }

                  /* The one place a media query is essential rather than cosmetic: the
                     fixed 600px width has to become fluid on a phone, or the message is
                     wider than the screen and the reader has to pan sideways to read it. */
                  @media screen and (max-width: 600px) {
                    .tm-shell { width: 100% !important; }
                    .tm-gutter { padding-left: 20px !important; padding-right: 20px !important; }
                    .tm-heading { font-size: 22px !important; line-height: 1.25 !important; }
                    .tm-body { font-size: 16px !important; }
                    /* A fixed-width button overflows a narrow screen, so it becomes a
                       full-width block instead. */
                    .tm-button { width: 100% !important; }
                    .tm-button a { display: block !important; width: auto !important; }
                    .tm-fact-label { width: 84px !important; }
                  }
                </style>
                </head>
                <body style="margin:0; padding:0; background-color:{{SURFACE_SOFT}};">
                <!-- The preheader. Hidden, but it is what the inbox list shows beside the
                     subject, so it carries the one-line summary. The invisible run after it
                     is filler: without it a client keeps reading into the message body and
                     the preview becomes the greeting. -->
                <div style="display:none; font-size:1px; color:{{SURFACE_SOFT}}; line-height:1px; max-height:0; max-width:0; opacity:0; overflow:hidden;">\
                {{PREHEADER}}&#8199;&#65279;&#8199;&#65279;&#8199;&#65279;&#8199;&#65279;&#8199;&#65279;&#8199;&#65279;&#8199;&#65279;</div>

                <table role="presentation" cellpadding="0" cellspacing="0" border="0" width="100%" \
                style="background-color:{{SURFACE_SOFT}};">
                  <tr>
                    <td align="center" style="padding:32px 12px;">

                      <!--[if mso]>
                      <table role="presentation" cellpadding="0" cellspacing="0" border="0" width="{{WIDTH}}"><tr><td>
                      <![endif]-->
                      <table role="presentation" cellpadding="0" cellspacing="0" border="0" \
                width="{{WIDTH}}" class="tm-shell" style="width:{{WIDTH}}px; max-width:{{WIDTH}}px; \
                background-color:{{CANVAS}}; border:1px solid {{HAIRLINE}}; border-radius:12px; \
                overflow:hidden;">
                        <tr>
                          <td class="tm-gutter" style="padding:32px 40px 8px 40px;">
                            <p style="margin:0; font-family:{{FONT}}; font-size:13px; font-weight:700; \
                letter-spacing:0.06em; text-transform:uppercase; color:{{RAUSCH}};">TripMate</p>
                          </td>
                        </tr>
                        <tr>
                          <td class="tm-gutter" style="padding:0 40px 0 40px;">
                            <h1 class="tm-heading" style="margin:0; font-family:{{FONT}}; font-size:26px; \
                line-height:1.25; font-weight:700; color:{{INK}};">{{HEADING}}</h1>
                          </td>
                        </tr>
                        <tr>
                          <td class="tm-gutter tm-body" style="padding:16px 40px 0 40px; \
                font-family:{{FONT}}; font-size:16px; line-height:1.5; color:{{BODY}};">
                {{CONTENT}}
                          </td>
                        </tr>
                        <tr>
                          <td class="tm-gutter" style="padding:28px 40px 32px 40px; font-family:{{FONT}}; \
                font-size:13px; line-height:1.5; color:{{MUTED}}; border-top:1px solid {{HAIRLINE}};">
                            {{FOOTER}}<br/>
                            TripMate &middot; group trip planning and expense tracking
                          </td>
                        </tr>
                      </table>
                      <!--[if mso]>
                      </td></tr></table>
                      <![endif]-->

                    </td>
                  </tr>
                </table>
                </body>
                </html>
                """;

        return fill(shell, "TITLE", title)
                .replace("{{PREHEADER}}", preheader)
                .replace("{{WIDTH}}", String.valueOf(WIDTH))
                .replace("{{FONT}}", FONT_STACK)
                .replace("{{RAUSCH}}", RAUSCH)
                .replace("{{INK}}", INK)
                .replace("{{HEADING}}", heading)
                .replace("{{BODY}}", BODY)
                .replace("{{CONTENT}}", content)
                .replace("{{MUTED}}", MUTED)
                .replace("{{FOOTER}}", footer)
                .replace("{{SURFACE_SOFT}}", SURFACE_SOFT)
                .replace("{{CANVAS}}", CANVAS)
                .replace("{{HAIRLINE}}", HAIRLINE);
    }

    // ------------------------------------------------------------------
    // Pieces
    // ------------------------------------------------------------------

    /** One sentence of body copy. */
    private static String paragraph(String text) {
        return "<p style=\"margin:0 0 16px 0;\">" + text + "</p>";
    }

    /**
     * A small definition table for the trip's facts.
     *
     * <p>
     * A table rather than lines of text so the labels and the values line up,
     * which is what makes a set of facts scannable rather than a paragraph to be
     * read.
     *
     * @param rows pre-built rows from {@link #factRow}
     */
    private static String factsTable(String rows) {
        return fill("""
                <table role="presentation" cellpadding="0" cellspacing="0" border="0" width="100%" \
                style="margin:0 0 16px 0; background-color:{{SURFACE_SOFT}}; border:1px solid {{HAIRLINE}}; \
                border-radius:8px;">
                  <tr>
                    <td style="padding:16px 20px;">
                      <table role="presentation" cellpadding="0" cellspacing="0" border="0" width="100%">
                {{ROWS}}      </table>
                    </td>
                  </tr>
                </table>
                """, "ROWS", rows)
                .replace("{{SURFACE_SOFT}}", SURFACE_SOFT)
                .replace("{{HAIRLINE}}", HAIRLINE);
    }

    /** One label and value in the facts table. */
    private static String factRow(String label, String value) {
        return "        <tr>\n"
                + "          <td class=\"tm-fact-label\" width=\"96\" valign=\"top\" "
                + "style=\"padding:4px 12px 4px 0; font-family:" + FONT_STACK + "; font-size:15px; "
                + "line-height:1.5; color:" + MUTED + ";\">" + label + "</td>\n"
                + "          <td valign=\"top\" style=\"padding:4px 0; font-family:" + FONT_STACK + "; "
                + "font-size:15px; line-height:1.5; font-weight:600; color:" + INK + ";\">"
                + value + "</td>\n"
                + "        </tr>\n";
    }

    /**
     * A button that is still a button in Outlook.
     *
     * <p>
     * Outlook renders with Word's engine, which does not support padding on an
     * inline element, so an anchor styled as a button collapses to underlined
     * text. The fix is a VML rounded rectangle for Outlook and a styled anchor
     * for everyone else, which is what the conditional comments below do. The VML
     * repeats the fill and the label because it draws its own shape and cannot
     * inherit either.
     *
     * @param label the button's text
     * @param url   where it goes
     */
    private static String button(String label, String url) {
        String markup = """
                <table role="presentation" cellpadding="0" cellspacing="0" border="0" style="margin:0 0 24px 0;">
                  <tr>
                    <td align="center" style="border-radius:8px; background-color:{{RAUSCH}};">
                      <!--[if mso]>
                      <v:roundrect xmlns:v="urn:schemas-microsoft-com:vml" \
                xmlns:w="urn:schemas-microsoft-com:office:word" href="{{URL}}" \
                style="height:48px; v-text-anchor:middle; width:220px;" arcsize="17%" \
                strokecolor="{{RAUSCH}}" fillcolor="{{RAUSCH}}">
                        <w:anchorlock/>
                        <center style="color:#ffffff; font-family:{{FONT}}; font-size:16px; font-weight:600;">{{LABEL}}</center>
                      </v:roundrect>
                      <![endif]-->
                      <!--[if !mso]><!-- -->
                      <a class="tm-button" href="{{URL}}" style="display:inline-block; padding:14px 28px; \
                font-family:{{FONT}}; font-size:16px; font-weight:600; line-height:1.25; color:#ffffff; \
                background-color:{{RAUSCH}}; border-radius:8px; text-decoration:none;">{{LABEL}}</a>
                      <!--<![endif]-->
                    </td>
                  </tr>
                </table>
                """;
        return fill(markup, "LABEL", label)
                .replace("{{URL}}", escape(url))
                .replace("{{RAUSCH}}", RAUSCH)
                .replace("{{FONT}}", FONT_STACK);
    }

    /**
     * Replaces one token in a template, then leaves the rest alone.
     *
     * <p>
     * {@code String.replace} rather than a regular expression, so a value
     * containing a dollar sign or a backslash is inserted literally. A user's
     * name is not a pattern.
     */
    private static String fill(String template, String token, String value) {
        return template.replace("{{" + token + "}}", value == null ? "" : value);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Escapes a value for HTML text content and for a double-quoted attribute.
     *
     * <p>
     * Escapes the apostrophe as well as the double quote, because values here
     * are interpolated into attributes that may be single-quoted in a client's
     * own rewrite. Escaping both means the output is safe in either.
     *
     * @param value the raw text, which may be null
     * @return the escaped text, or an empty string for null
     */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * The first word of a name, for a greeting.
     *
     * <p>
     * "Dear Alice Sharma" reads like a form letter. The first word is what a
     * person is actually called in most of the world, and a name that yields
     * nothing falls back to a neutral word rather than producing "Dear ,".
     *
     * <p>
     * Returns the name <em>unescaped</em>. Escaping belongs at the point the
     * value is put into HTML, and the plain-text part needs the raw form: a name
     * containing an ampersand would otherwise read as "&amp;" in a text-only
     * client, which is the kind of detail that makes an automated message look
     * broken.
     *
     * @param fullName the name, which may be null or blank
     * @param fallback what to use when there is no usable name
     * @return the first word, or the fallback
     */
    private static String firstNameOr(String fullName, String fallback) {
        if (isBlank(fullName)) {
            return fallback;
        }
        String trimmed = fullName.trim();
        int space = trimmed.indexOf(' ');
        return space > 0 ? trimmed.substring(0, space) : trimmed;
    }

    /**
     * A readable date range.
     *
     * <p>
     * Collapses to what is known, so a trip with no dates does not produce
     * "null to null". A range inside one month is shortened, because
     * "3 to 9 August 2026" is how a person says it.
     *
     * @return the range, or an empty string when neither date is known
     */
    private static String describeDates(LocalDate start, LocalDate end) {
        if (start == null && end == null) {
            return "";
        }
        if (start != null && end == null) {
            return "From " + start.format(DAY);
        }
        if (start == null) {
            return "Until " + end.format(DAY);
        }
        if (start.equals(end)) {
            return start.format(DAY);
        }
        if (start.getYear() == end.getYear() && start.getMonth() == end.getMonth()) {
            return start.getDayOfMonth() + " to " + end.format(DAY);
        }
        return start.format(DAY_SHORT) + " to " + end.format(DAY);
    }

    /** @return true when the value is null, empty or only whitespace */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // ------------------------------------------------------------------
    // Password reset
    // ------------------------------------------------------------------

    /**
     * The message carrying a reset link.
     *
     * <h2>What this message deliberately does not do</h2>
     *
     * It does not say the address has an account, because it is only sent when
     * it does, and its arrival is therefore the answer. A version that
     * apologised for the wait or explained what happened would be a longer way
     * of saying the same thing.
     *
     * <p>
     * It does not offer a button only. The link is written out in full in
     * the plain-text part and repeated under the button in the HTML, because a
     * reset link that only exists behind a button is unreachable in a client
     * that strips them, and this is the one message where being unable to
     * proceed has no workaround.
     *
     * @param toEmail      the address to send to
     * @param fullName     the name on the account
     * @param resetUrl     the absolute URL carrying the token
     * @param validMinutes how long the link lasts, stated in the message so the
     *                     reader knows whether it is worth trying
     * @return the rendered message
     */
    public static Email passwordReset(String toEmail, String fullName, String resetUrl,
            int validMinutes) {
        String greeting = firstNameOr(fullName, "there");
        String heading = "Reset your TripMate password";
        String escapedUrl = escape(resetUrl);

        String body = paragraph("Somebody asked to reset the password for this address. "
                + "If that was you, the button below takes you to a page where you "
                + "can choose a new one.")
                + button("Choose a new password", resetUrl)
                + paragraph("Or paste this link into your browser:")
                + linkBlock(escapedUrl)
                + paragraph("The link works once, and stops working after "
                        + validMinutes + " minutes. Requesting another one cancels this one.")
                + note("If this was not you, nothing has happened and nothing needs doing. "
                        + "Your password is unchanged and the link will expire on its own.");

        String html = layout(
                "Reset your TripMate password",
                "A link to choose a new password. It works once and expires in "
                        + validMinutes + " minutes.",
                heading,
                body,
                "You are receiving this because a password reset was requested for this "
                        + "address at TripMate.");

        String text = greeting + ",\n\n"
                + "Somebody asked to reset the password for this address. If that was you,\n"
                + "open this link to choose a new one:\n\n"
                + resetUrl + "\n\n"
                + "The link works once, and stops working after " + validMinutes + " minutes.\n"
                + "Requesting another one cancels this one.\n\n"
                + "If this was not you, nothing has happened and nothing needs doing. Your\n"
                + "password is unchanged and the link will expire on its own.\n\n"
                + "--\n"
                + "You are receiving this because a password reset was requested for this\n"
                + "address at TripMate.\n";

        return new Email(toEmail, "Reset your TripMate password", html, text);
    }

    /**
     * A short code, set large and spaced so it can be read off and typed in.
     *
     * <p>
     * The letter spacing and the monospaced stack are the whole point: digits
     * copied from a paragraph are misread, and {@code 0} against {@code O} and
     * {@code 1} against {@code l} are the usual victims. The block is styled
     * rather than in a {@code <code>} element because Outlook's renderer
     * ignores the element's default styling and would set the code in the same
     * body font as everything else.
     *
     * @param escapedCode the digits, already escaped
     */
    private static String codeBlock(String escapedCode) {
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" "
                + "width=\"100%\" style=\"margin:0 0 16px;\"><tr>"
                + "<td align=\"center\" style=\"padding:20px 14px;background-color:"
                + SURFACE_SOFT + ";border:1px solid " + HAIRLINE + ";border-radius:8px;"
                + "font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,'Courier New',monospace;"
                + "font-size:34px;line-height:1.2;font-weight:700;letter-spacing:8px;"
                + "color:" + INK + ";\">"
                // The trailing letter-spacing of the last digit would push the
                // block off centre, so it is taken back on the right.
                + "<span style=\"margin-right:-8px;\">" + escapedCode + "</span>"
                + "</td></tr></table>";
    }

    /**
     * The reset link, written out so it can be copied.
     *
     * <p>
     * A long URL in a 600px column has to break, and it has to break as a
     * block rather than mid-word, or the email client widens the whole message
     * and the layout falls apart on a phone. {@code word-break} is what the
     * clients that honour it need; the rest wrap it as one long token, which is
     * ugly and readable, and uglier and readable beats tidy and truncated.
     */
    private static String linkBlock(String escapedUrl) {
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" "
                + "width=\"100%\" style=\"margin:0 0 16px;\"><tr>"
                + "<td style=\"padding:12px 14px;background-color:" + SURFACE_SOFT
                + ";border:1px solid " + HAIRLINE + ";border-radius:8px;"
                + "font-family:" + FONT_STACK + ";font-size:13px;line-height:1.5;"
                + "color:" + INK + ";word-break:break-all;\">"
                + escapedUrl
                + "</td></tr></table>";
    }

    /**
     * A quieter paragraph, set apart from the body of the message.
     *
     * <p>
     * Used for the reassurance that nothing happened, which is important
     * enough to state and not important enough to compete with the instruction
     * above it.
     */
    private static String note(String text) {
        return "<p style=\"margin:20px 0 0;padding-top:16px;border-top:1px solid " + HAIRLINE
                + ";font-family:" + FONT_STACK + ";font-size:13px;line-height:1.6;color:"
                + MUTED + ";\">" + text + "</p>";
    }

    // ------------------------------------------------------------------
    // Trip summary
    // ------------------------------------------------------------------

    /**
     * The message sent to everyone when a trip is wrapped up.
     *
     * <h2>Written to one person, not to the group</h2>
     *
     * The same figures go to everybody, but the message does not read the same
     * to everybody, because the question each reader has is about themselves:
     * did I come out ahead or behind, and by how much. So the totals are shared
     * and the position is not, and the reader's own line comes before the table
     * of everyone else.
     *
     * <p>
     * That is also why {@code recipientId} is a parameter. A summary that
     * only listed the group's positions would leave every reader to find their
     * own row and work out their own answer, which is the work the email exists
     * to save them.
     *
     * <h2>Receivable and payable, said plainly</h2>
     *
     * "You are owed" and "you owe" rather than a signed number, because a
     * minus sign in front of a currency figure is read correctly by about half
     * of people and misread by the rest.
     *
     * @param toEmail      the address to send to
     * @param fullName     the recipient's name
     * @param recipientId  which row of the balances is theirs
     * @param tripName     the trip's name
     * @param startDate    the first day, which may be null
     * @param endDate      the last day, which may be null
     * @param total        what the trip cost altogether
     * @param expenseCount how many expenses made it up
     * @param categories   the category breakdown, largest first
     * @param balances     one row per member
     * @param tripUrl      the absolute URL of the trip's page
     * @return the rendered message
     */
    /**
     * The plan on its own: what is happening, and when.
     *
     * <p>Split out of the old combined summary so a reader can send the
     * itinerary without the money, or the money without the itinerary. They are
     * two different questions and a trip in planning usually has an answer to
     * only the first.
     */
    public static Email itinerarySummary(String toEmail, String fullName,
            String tripName, LocalDate startDate, LocalDate endDate,
            TripStatus status,
            List<ItineraryItemDTO> itinerary,
            String tripUrl) {
        return summary(TripSummaryKind.ITINERARY, toEmail, fullName, null,
                tripName, startDate, endDate, status,
                BigDecimal.ZERO, 0, itinerary, List.of(), List.of(), null, tripUrl);
    }

    /**
     * The money on its own: what the trip cost, and where everybody landed.
     *
     * @param recipientId       which row of the balances is the reader's, so
     *                          their own position can be said in a sentence
     *                          before the table
     * @param readerOutstanding what the reader still has to settle once the
     *                          repayments recorded on the trip are counted,
     *                          positive when they are still owed. Null when it
     *                          could not be worked out, in which case the
     *                          message falls back to the gross difference.
     */
    public static Email balancesSummary(String toEmail, String fullName, Long recipientId,
            String tripName, LocalDate startDate, LocalDate endDate,
            TripStatus status,
            BigDecimal total, int expenseCount,
            List<CategoryTotalDTO> categories,
            List<MemberBalanceDTO> balances,
            BigDecimal readerOutstanding,
            String tripUrl) {
        return summary(TripSummaryKind.BALANCES, toEmail, fullName, recipientId,
                tripName, startDate, endDate, status,
                total, expenseCount, List.of(), categories, balances,
                readerOutstanding, tripUrl);
    }

    private static Email summary(TripSummaryKind kind,
            String toEmail, String fullName, Long recipientId,
            String tripName, LocalDate startDate, LocalDate endDate,
            TripStatus status,
            BigDecimal total, int expenseCount,
            List<ItineraryItemDTO> itinerary,
            List<CategoryTotalDTO> categories,
            List<MemberBalanceDTO> balances,
            BigDecimal readerOutstanding,
            String tripUrl) {
        boolean withPlan = kind == TripSummaryKind.ITINERARY;
        boolean withMoney = kind == TripSummaryKind.BALANCES;

        String greeting = firstNameOr(fullName, "there");
        String plainTrip = isBlank(tripName) ? "your trip" : tripName;
        String escapedTrip = escape(plainTrip);

        // A trip that has not finished gets a different message, not the same
        // message with a different date on it. The summary used to open with
        // "The trip is done" whatever the trip's state, so a trip still in
        // planning arrived announcing its own completion, which is both wrong
        // and the sort of wrong that makes a reader distrust every figure under
        // it.
        //
        // Cancelled counts as finished: nothing further is going to be planned
        // or spent, so what is left to say about it is what it cost.
        boolean finished = status == TripStatus.COMPLETED || status == TripStatus.CANCELLED;

        String subjectTail = withPlan
                ? "what is planned"
                : (finished ? "the numbers" : "where it stands");
        String heading = escape(plainTrip) + ": " + subjectTail;
        String when = dateRange(startDate, endDate);

        String intro;
        if (withPlan) {
            intro = "Here is what is planned for " + plainTrip + ".";
        } else {
            intro = finished
                    ? "The trip is done. Here is what it cost and where everybody landed."
                    : "Here is what has been spent so far, and where everybody landed.";
        }

        MemberBalanceDTO mine = balances.stream()
                .filter(b -> recipientId != null && recipientId.equals(b.getUserId()))
                .findFirst()
                .orElse(null);

        StringBuilder body = new StringBuilder();
        body.append(paragraph(intro));

        // The plan, which the closing summary never carried because it assumed
        // there was nothing left to plan.
        if (withPlan && !itinerary.isEmpty()) {
            body.append(subheading("What is planned"));
            body.append(itineraryTable(itinerary));
        }

        if (withMoney) {
            body.append(figures(new String[][] {
                    { "Total spent", "NPR " + money(total) },
                    { "Expenses", String.valueOf(expenseCount) },
                    { "People", String.valueOf(balances.size()) },
                    { "Each", "NPR " + perPerson(total, balances.size()) },
            }));

            if (mine != null) {
                body.append(position(mine, readerOutstanding));
            }

            if (!categories.isEmpty()) {
                body.append(subheading("Where it went"));
                body.append(categoryTable(categories, total));
            }

            if (!balances.isEmpty()) {
                body.append(subheading("Where everybody landed"));
                body.append(balanceTable(balances, recipientId));
            }
        }

        body.append(button("Open the trip", tripUrl));

        String html = layout(
                plainTrip + ": " + subjectTail,
                withPlan
                        ? "What is planned for " + plainTrip + "."
                        : (finished
                                ? "What " + plainTrip + " cost, and where everybody landed."
                                : "What " + plainTrip + " has cost so far, and where everybody landed."),
                heading,
                (when.isEmpty() ? "" : paragraph(when)) + body,
                "You are receiving this because you were on this trip at TripMate.");

        StringBuilder text = new StringBuilder()
                .append(greeting).append(",\n\n")
                .append(intro).append("\n\n")
                .append(when.isEmpty() ? "" : when + "\n\n")
                .append(withPlan ? plainItinerary(itinerary) : "");

        if (withMoney) {
            text.append("Total spent: NPR ").append(money(total)).append("\n")
                    .append("Expenses:    ").append(expenseCount).append("\n")
                    .append("People:      ").append(balances.size()).append("\n")
                    .append("Each:        NPR ").append(perPerson(total, balances.size())).append("\n\n")
                    .append(mine == null ? "" : positionText(mine, readerOutstanding) + "\n\n")
                    .append(plainCategories(categories, total))
                    .append(plainBalances(balances));
        }

        text.append("Open the trip: ").append(tripUrl).append("\n\n")
                .append("--\n")
                .append("You are receiving this because you were on this trip at TripMate.\n");

        return new Email(toEmail, plainTrip + ": " + subjectTail, html, text.toString());
    }

    // --- pieces of the summary ------------------------------------------

    /** A small block of label and value pairs, two to a row. */
    private static String figures(String[][] pairs) {
        StringBuilder out = new StringBuilder();
        out.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" ")
                .append("border=\"0\" width=\"100%\" style=\"margin:0 0 20px;\">");
        for (int i = 0; i < pairs.length; i += 2) {
            out.append("<tr>");
            out.append(figureCell(pairs[i]));
            out.append(i + 1 < pairs.length ? figureCell(pairs[i + 1]) : emptyCell());
            out.append("</tr>");
        }
        out.append("</table>");
        return out.toString();
    }

    private static String figureCell(String[] pair) {
        return "<td width=\"50%\" style=\"padding:10px 12px 10px 0;vertical-align:top;\">"
                + "<div style=\"font-family:" + FONT_STACK + ";font-size:11px;"
                + "letter-spacing:0.4px;text-transform:uppercase;color:" + MUTED
                + ";font-weight:700;\">" + escape(pair[0]) + "</div>"
                + "<div style=\"font-family:" + FONT_STACK + ";font-size:20px;"
                + "font-weight:700;color:" + INK + ";padding-top:2px;\">"
                + escape(pair[1]) + "</div></td>";
    }

    private static String emptyCell() {
        return "<td width=\"50%\" style=\"padding:10px 0;\"></td>";
    }

    private static String subheading(String text) {
        return "<p style=\"margin:24px 0 8px;font-family:" + FONT_STACK
                + ";font-size:12px;font-weight:700;letter-spacing:0.4px;"
                + "text-transform:uppercase;color:" + MUTED + ";\">"
                + escape(text) + "</p>";
    }

    /**
     * The reader's own line.
     *
     * <p>
     * Coloured on the left edge as well as worded, so it is findable in a
     * message that also contains a table of everybody else.
     */
    private static String position(MemberBalanceDTO mine, BigDecimal outstanding) {
        String tone = mine.isCreditor() ? "#0f7b52" : (mine.isDebtor() ? "#c13515" : MUTED);
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" "
                + "width=\"100%\" style=\"margin:0 0 8px;\"><tr>"
                + "<td style=\"padding:14px 16px;background-color:" + SURFACE_SOFT
                + ";border-left:3px solid " + tone + ";border-radius:6px;"
                + "font-family:" + FONT_STACK + ";font-size:15px;line-height:1.5;color:"
                + INK + ";\">" + positionHtml(mine, outstanding) + "</td></tr></table>";
    }

    /**
     * The reader's position, for the HTML part, with the money emphasised.
     *
     * <p>
     * Written separately from {@link #positionText} rather than derived from
     * it. The two differ by markup, and the first version of this method built
     * both from one string, which put literal {@code <strong>} tags into the
     * plain-text part of the message.
     */
    private static String positionHtml(MemberBalanceDTO mine, BigDecimal outstanding) {
        return sentence(mine, outstanding, "<strong>", "</strong>");
    }

    /** The same sentence for the plain-text part, with no markup at all. */
    private static String positionText(MemberBalanceDTO mine, BigDecimal outstanding) {
        return sentence(mine, outstanding, "", "");
    }

    /**
     * The reader's own line.
     *
     * <p>Reads the outstanding figure when it is known, which is what is left
     * once the repayments recorded on the trip are counted. The gross
     * difference is the fallback: on a trip nobody has repaid anything the two
     * are the same number, and where they differ the outstanding one is the
     * only one the reader can act on.
     *
     * <p>"You get back" and "you pay" rather than a signed number, and rather
     * than the words the tables use: a minus sign in front of a currency figure
     * is read correctly by about half of people and misread by the rest.
     *
     * @param open  markup to put before the figure, empty for plain text
     * @param close markup to put after it, empty for plain text
     */
    private static String sentence(MemberBalanceDTO mine, BigDecimal outstanding,
            String open, String close) {
        BigDecimal left = outstanding != null ? outstanding : mine.getNetBalance();
        String figure = money(left.abs());

        String verdict;
        if (left.signum() > 0) {
            verdict = "You get back " + open + "NPR " + figure + close + ".";
        } else if (left.signum() < 0) {
            verdict = "You pay " + open + "NPR " + figure + close + ".";
        } else {
            verdict = "You are even.";
        }

        return "You paid NPR " + money(mine.getTotalPaid())
                + " and your share came to NPR " + money(mine.getTotalOwed())
                + ". " + verdict;
    }

    /**
     * The plan, as a table of day, time and what.
     *
     * <p>Day and time are two columns rather than one, because the times are
     * the thing a reader scans for: "what happens on the 8th" and "what starts
     * at nine" are different questions, and a single column answers neither
     * quickly.
     */
    private static String itineraryTable(List<ItineraryItemDTO> items) {
        StringBuilder out = new StringBuilder();
        out.append(openTable());
        out.append("<tr>")
                .append(headCell("Day")).append(headCell("Time"))
                .append(headCell("What")).append(headCell("Where"))
                .append("</tr>");
        for (ItineraryItemDTO item : items) {
            String time = item.getStartTime() == null
                    ? ""
                    : item.getStartTime().format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH));
            if (item.getEndTime() != null) {
                time = time.isEmpty() ? "" : time + " - ";
                time = time + item.getEndTime()
                        .format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH));
            }
            out.append("<tr>")
                    .append(cell(item.getItemDate() == null ? "" : item.getItemDate().toString(), false))
                    .append(cell(escape(time), false))
                    .append(cell(escape(item.getTitle()), false))
                    .append(cell(escape(item.getLocation()), false))
                    .append("</tr>");
        }
        out.append("</table>");
        return out.toString();
    }

    /** The same plan, for a client that refuses HTML. */
    private static String plainItinerary(List<ItineraryItemDTO> items) {
        if (items.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("What is planned:\n");
        for (ItineraryItemDTO item : items) {
            out.append("  ")
                    .append(item.getItemDate() == null ? "" : item.getItemDate())
                    .append("  ")
                    .append(item.getStartTime() == null ? "" : item.getStartTime())
                    .append("  ")
                    .append(item.getTitle() == null ? "" : item.getTitle());
            if (item.getLocation() != null && !item.getLocation().isBlank()) {
                out.append(" (").append(item.getLocation()).append(")");
            }
            out.append("\n");
        }
        return out.append("\n").toString();
    }

    private static String categoryTable(List<CategoryTotalDTO> categories, BigDecimal total) {
        StringBuilder out = new StringBuilder();
        out.append(openTable());
        for (CategoryTotalDTO category : categories) {
            out.append("<tr>")
                    .append(cell(escape(category.getLabel()), false))
                    .append(cell("NPR " + money(category.getTotal()), false))
                    .append(cell(percent(category.getTotal(), total), true))
                    .append("</tr>");
        }
        out.append("</table>");
        return out.toString();
    }

    private static String balanceTable(List<MemberBalanceDTO> balances, Long recipientId) {
        StringBuilder out = new StringBuilder();
        out.append(openTable());
        out.append("<tr>")
                .append(headCell("Person")).append(headCell("Paid"))
                .append(headCell("Share")).append(headCell("Difference"))
                .append("</tr>");
        for (MemberBalanceDTO balance : balances) {
            // The reader's own row is marked, so the table can be scanned for it
            // rather than read.
            boolean isReader = recipientId != null && recipientId.equals(balance.getUserId());
            String name = escape(balance.getFullName()) + (isReader ? " (you)" : "");
            out.append("<tr>")
                    .append(cell(name, false))
                    .append(cell("NPR " + money(balance.getTotalPaid()), false))
                    .append(cell("NPR " + money(balance.getTotalOwed()), false))
                    .append(cell("NPR " + money(balance.getNetBalance()), true))
                    .append("</tr>");
        }
        out.append("</table>");
        return out.toString();
    }

    private static String openTable() {
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" "
                + "border=\"0\" width=\"100%\" style=\"margin:0 0 8px;border-collapse:collapse;\">";
    }

    private static String headCell(String text) {
        return "<th align=\"left\" style=\"padding:6px 8px 6px 0;border-bottom:1px solid "
                + HAIRLINE + ";font-family:" + FONT_STACK + ";font-size:11px;font-weight:700;"
                + "letter-spacing:0.4px;text-transform:uppercase;color:" + MUTED + ";\">"
                + escape(text) + "</th>";
    }

    private static String cell(String content, boolean last) {
        return "<td align=\"left\" style=\"padding:8px 8px 8px 0;border-bottom:1px solid "
                + HAIRLINE + ";font-family:" + FONT_STACK + ";font-size:14px;color:"
                + (last ? INK : BODY) + ";font-weight:" + (last ? "600" : "400") + ";\">"
                + content + "</td>";
    }

    private static String plainCategories(List<CategoryTotalDTO> categories, BigDecimal total) {
        if (categories.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("Where it went\n");
        for (CategoryTotalDTO category : categories) {
            out.append("  ").append(category.getLabel()).append(": NPR ")
                    .append(money(category.getTotal()))
                    .append(" (").append(percent(category.getTotal(), total)).append(")\n");
        }
        return out.append("\n").toString();
    }

    private static String plainBalances(List<MemberBalanceDTO> balances) {
        if (balances.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("Where everybody landed\n");
        for (MemberBalanceDTO balance : balances) {
            out.append("  ").append(balance.getFullName())
                    .append(": paid NPR ").append(money(balance.getTotalPaid()))
                    .append(", share NPR ").append(money(balance.getTotalOwed()))
                    .append(", difference NPR ").append(money(balance.getNetBalance()))
                    .append("\n");
        }
        return out.append("\n").toString();
    }

    /** @return "1 to 5 September 2026", or an empty string when undated */
    private static String dateRange(LocalDate start, LocalDate end) {
        if (start == null && end == null) {
            return "";
        }
        if (start != null && end != null) {
            return start.format(DAY_SHORT) + " to " + end.format(DAY);
        }
        return (start == null ? end : start).format(DAY);
    }

    /** @return the total shared between people, to two places */
    private static String perPerson(BigDecimal total, int people) {
        if (total == null || people <= 0) {
            return "0.00";
        }
        return total.divide(BigDecimal.valueOf(people), 2, java.math.RoundingMode.HALF_UP)
                .toPlainString();
    }

    /** @return two decimal places, never null */
    private static String money(BigDecimal value) {
        return value == null ? "0.00"
                : value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /** @return a whole-number percentage of a total, or "0%" when it is zero */
    private static String percent(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0 || part == null) {
            return "0%";
        }
        return part.multiply(BigDecimal.valueOf(100))
                .divide(whole, 0, java.math.RoundingMode.HALF_UP).toPlainString() + "%";
    }
}
