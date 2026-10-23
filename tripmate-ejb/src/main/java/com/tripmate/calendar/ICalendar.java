package com.tripmate.calendar;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.tripmate.dto.ItineraryItemDTO;
import com.tripmate.entity.Trip;

/**
 * Renders a trip's plan as an iCalendar file (RFC 5545).
 *
 * <p>The output is what a calendar application imports: one {@code VEVENT} per
 * itinerary item, wrapped in a single {@code VCALENDAR}. Nothing here is
 * TripMate-specific, which is the point. A calendar file is the one format every
 * calendar application reads, so the application does not need an account with
 * any of them, a client secret, or a network round trip to let somebody see
 * their plan beside their other appointments.
 *
 * <h2>Three decisions worth knowing</h2>
 *
 * <p><strong>Times are written as UTC instants, converted through the trip's
 * zone.</strong> This was wrong first, and the way it was wrong is worth keeping.
 *
 * <p>An itinerary item is a wall clock: {@code item_date}, {@code start_time},
 * {@code end_time}, and no zone of its own. RFC 5545 has a representation for
 * exactly that, a {@code DTSTART} with no {@code TZID} and no trailing
 * {@code Z}, which means "at this clock time, wherever you are", and that is
 * what was written. It is also what Google Calendar's reader takes to mean
 * <em>UTC</em>: a trek out of Lukla planned for 07:00 appeared at 12:45 in a
 * calendar set to Kathmandu, which is the fifth of a day that +05:45 is worth,
 * and the plan had quietly moved to the middle of the afternoon.
 *
 * <p>Every time now goes out as a UTC instant, converted through
 * {@link com.tripmate.entity.Trip#effectiveZone()}. The wall clock the reader
 * typed is preserved exactly, because the conversion puts it back in the same
 * zone when it is read; what changes is that the file no longer depends on how
 * a client chooses to interpret an ambiguity.
 *
 * <p><strong>An item with no start time is an all-day event.</strong> A plan is
 * often "somewhere in Pokhara on Saturday" without an hour, and inventing 09:00
 * would put a commitment in the reader's calendar that nobody agreed to. An
 * all-day event is what that means, and it carries no time and so no zone.
 * RFC 5545 makes {@code DTEND} exclusive for a date-valued event, so a one-day
 * event ends the following day.
 *
 * <p><strong>An item with a start time and no end time lasts an hour.</strong>
 * A calendar entry is not allowed to have a duration of nothing, and the
 * alternative, leaving the end off, makes the reader's client choose the
 * duration instead. An hour is the application's guess and is applied
 * consistently rather than per item. It is also applied to an item whose end
 * equals its start, which the schema permits and which no calendar can draw.
 * The hour is added before the conversion, so it stays an hour across a
 * daylight-saving boundary rather than becoming one.
 *
 * <h2>The details that break a naive writer</h2>
 *
 * <ul>
 * <li><strong>CRLF, not LF.</strong> The format requires it, and generators
 * that emit {@code \n} are rejected by strict parsers.</li>
 * <li><strong>Folding at 75 octets, not 75 characters.</strong> A line is
 * continued with CRLF and a single space, counted in <em>octets of UTF-8</em>,
 * and the continuation must not split a character. Counting characters instead
 * of octets corrupts any non-ASCII text, and a Nepali trip name is non-ASCII in
 * every character.</li>
 * <li><strong>Escaping.</strong> A backslash, semicolon, comma or newline inside
 * a value has meaning to the parser, so a note containing a comma would silently
 * become two values. Everything a person can type goes through
 * {@link #escape(String)}.</li>
 * <li><strong>A stable UID, and a SEQUENCE that moves.</strong> Each event is
 * identified by its database id, which never changes, and carries the item's
 * edit count as its {@code SEQUENCE}, which always increases. A client that has
 * already read the feed relates the two copies of an event by the first and
 * decides which one is newer by the second, so an edit reaches a subscriber's
 * calendar rather than being ignored.</li>
 * </ul>
 *
 * <h2>What a UID and a SEQUENCE do not do</h2>
 *
 * <p>They make a <em>subscribed</em> calendar follow the plan. They do not make
 * a one-off import behave the same way: Google Calendar's importer reads
 * {@code SEQUENCE} too, and a file imported twice can still leave two copies of
 * everything. That is why the dialog warns about re-importing, and why the
 * subscribe address exists at all.
 *
 * <p>Nothing here re-stamps an event on read. Both {@code DTSTAMP} and
 * {@code LAST-MODIFIED} come from the item's own stored times, so the same data
 * renders to the same bytes every time it is asked for. That is what lets the
 * endpoint answer a poll with {@code 304 Not Modified} and stop paying to send
 * a calendar that has not changed.
 */
public final class ICalendar {

    /** The longest a physical line may be, in octets, excluding the line break. */
    private static final int LINE_LIMIT = 75;

    /** How long an item with a start time and no end time is taken to last. */
    private static final Duration DEFAULT_LENGTH = Duration.ofHours(1);

    private static final String LINE_BREAK = "\r\n";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** A UTC date and time. Every time in the file is written in this form. */
    private static final DateTimeFormatter UTC_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private ICalendar() {
        // A pure function of its arguments; not instantiable.
    }

    /**
     * Renders the whole file.
     *
     * <p>A pure function of its arguments, including the times. Nothing in the
     * output depends on when it was asked for, which is what allows a caller to
     * hash the result and use it as an entity tag.
     *
     * @param trip          the trip the plan belongs to, for the calendar's name
     * @param itinerary     the items, in the order they will be experienced
     * @param fallbackStamp used for an item that carries no update time, which
     *                      the database does not allow but a hand-built DTO can
     * @return the file's text, CRLF-terminated on every line
     */
    public static String render(Trip trip, List<ItineraryItemDTO> itinerary,
            Instant fallbackStamp) {
        StringBuilder out = new StringBuilder();

        line(out, "BEGIN:VCALENDAR");
        line(out, "VERSION:2.0");
        line(out, "PRODID:-//TripMate//Itinerary//EN");
        line(out, "CALSCALE:GREGORIAN");
        line(out, "METHOD:PUBLISH");

        // Not part of the standard, and read by every client that matters. It
        // names the calendar a reader sees if they import the file into one of
        // its own rather than into an existing calendar.
        if (isPresent(trip.getTripName())) {
            line(out, "X-WR-CALNAME:" + escape(trip.getTripName()));
        }

        // The zone every wall-clock time in this file is read in. Resolved
        // once, from the trip, so nothing below has to decide for itself.
        ZoneId zone = trip.effectiveZone();
        for (ItineraryItemDTO item : itinerary) {
            event(out, item, stamp(item, fallbackStamp), zone);
        }

        line(out, "END:VCALENDAR");
        return out.toString();
    }

    /** Writes one itinerary item as a {@code VEVENT}. */
    private static void event(StringBuilder out, ItineraryItemDTO item, Instant stamp, ZoneId zone) {
        LocalDate day = item.getItemDate();
        LocalTime start = item.getStartTime();
        LocalTime end = item.getEndTime();

        line(out, "BEGIN:VEVENT");
        line(out, "UID:" + uid(item));
        line(out, "DTSTAMP:" + UTC_STAMP.format(stamp));

        // How a client tells a newer copy of an event from an older one. It has
        // to be present and it has to move only upwards, so it is written from
        // the item's own count of edits rather than from anything about this
        // request.
        line(out, "SEQUENCE:" + item.getRevision());

        // The same time as DTSTAMP, for clients that prefer it. RFC 5545 makes
        // this the property that says when the event's data last changed, which
        // is exactly what the application stores.
        line(out, "LAST-MODIFIED:" + UTC_STAMP.format(stamp));

        if (start == null) {
            // An all-day event, which carries no time and therefore no zone.
            line(out, "DTSTART;VALUE=DATE:" + DATE.format(day));
            line(out, "DTEND;VALUE=DATE:" + DATE.format(day.plusDays(1)));
        } else {
            LocalDateTime begins = LocalDateTime.of(day, start);
            // The schema refuses an end before its start, so an end that is not
            // after the start is either absent or equal to it.
            LocalDateTime finishes = end == null || !end.isAfter(start)
                    ? begins.plus(DEFAULT_LENGTH)
                    : LocalDateTime.of(day, end);
            line(out, "DTSTART:" + UTC_STAMP.format(begins.atZone(zone).toInstant()));
            line(out, "DTEND:" + UTC_STAMP.format(finishes.atZone(zone).toInstant()));
        }

        // Required by the format, and the title is required by the schema, so
        // there is nothing to fall back to and nothing to check.
        line(out, "SUMMARY:" + escape(item.getTitle()));

        if (isPresent(item.getLocation())) {
            line(out, "LOCATION:" + escape(item.getLocation()));
        }
        if (isPresent(item.getDescription())) {
            line(out, "DESCRIPTION:" + escape(item.getDescription()));
        }

        line(out, "END:VEVENT");
    }

    /**
     * When one item last changed, as a UTC instant.
     *
     * <p>The stored time is a local one, so it is read in the server's own zone,
     * the same way {@code SettleBean} reads the time it records. What matters
     * for the feed is not which zone it is read in but that the same stored
     * value always produces the same instant, so that an unchanged plan renders
     * to unchanged bytes.
     */
    private static Instant stamp(ItineraryItemDTO item, Instant fallback) {
        LocalDateTime updated = item.getUpdatedAt();
        return updated == null ? fallback : updated.atZone(ZoneId.systemDefault()).toInstant();
    }

    /**
     * The identifier for one item's event.
     *
     * <p>Derived from the item's own id, which the database never reuses and
     * never changes, so the same item keeps the same identifier across exports.
     * That is the whole basis of re-importing a file to update a calendar rather
     * than to duplicate it, so a generated or random identifier would undo the
     * feature it is meant to support.
     */
    private static String uid(ItineraryItemDTO item) {
        return "tripmate-itinerary-" + item.getItemId() + "@tripmate";
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /** Writes one property line, folded and CRLF-terminated. */
    private static void line(StringBuilder out, String text) {
        out.append(fold(text)).append(LINE_BREAK);
    }

    /**
     * Breaks a line into physical lines of at most 75 octets.
     *
     * <p>Folding is by octets, and a character is never split, so a line of
     * Nepali text, where one character is three octets, folds roughly a third as
     * often as a line of English. Each continuation begins with one space, and
     * that space is inside the continuation's own 75 rather than free, which is
     * why the running total starts at one after a fold rather than at zero.
     *
     * @param text one complete logical line, without its line break
     * @return the same line, folded, with CRLF and a space wherever it continues
     */
    static String fold(String text) {
        if (octets(text) <= LINE_LIMIT) {
            return text;
        }

        StringBuilder folded = new StringBuilder(text.length() + text.length() / 60 * 3);
        int used = 0;
        int from = 0;
        int at = 0;
        while (at < text.length()) {
            int codePoint = text.codePointAt(at);
            int width = octets(codePoint);
            if (used + width > LINE_LIMIT) {
                folded.append(text, from, at).append(LINE_BREAK).append(' ');
                from = at;
                used = 1;
            }
            used += width;
            at += Character.charCount(codePoint);
        }
        return folded.append(text, from, text.length()).toString();
    }

    /**
     * Makes a value safe to place after a property name.
     *
     * <p>The four characters RFC 5545 gives meaning to are backslash, semicolon,
     * comma and the line break. A comma is the one that bites: notes are written
     * in prose, prose has commas, and an unescaped comma splits one value into
     * two that the reader's calendar then shows as separate lines.
     *
     * <p>A carriage return is dropped rather than escaped, because it only ever
     * arrives as half of a Windows line ending and the newline that follows it
     * becomes the escape.
     *
     * @param value any text a person has typed
     * @return the same text with the four significant characters escaped
     */
    static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case ';' -> escaped.append("\\;");
                case ',' -> escaped.append("\\,");
                case '\r' -> {
                    // Dropped; the \n that follows becomes the escape.
                }
                case '\n' -> escaped.append("\\n");
                default -> escaped.append(character);
            }
        }
        return escaped.toString();
    }

    /** @return the width in octets of one code point once encoded as UTF-8 */
    private static int octets(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        return codePoint < 0x10000 ? 3 : 4;
    }

    private static int octets(String text) {
        int total = 0;
        for (int at = 0; at < text.length(); ) {
            int codePoint = text.codePointAt(at);
            total += octets(codePoint);
            at += Character.charCount(codePoint);
        }
        return total;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
