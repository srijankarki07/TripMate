package com.tripmate.calendar;

import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.tripmate.dto.ItineraryItemDTO;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonWriter;

/**
 * The three calls that write one itinerary item into a Google Calendar.
 *
 * <p>Separate from the service that decides <em>what</em> to write, so that the
 * shape of Google's API lives in one place and the rules about who may write
 * live in another. Nothing here knows about users, trips or permissions: it is
 * given a token, a calendar and an event, and it either does it or says why not.
 *
 * <h2>An item is a timed event or an all-day one, and nothing in between</h2>
 *
 * <p>Same rule as the calendar file, for the same reasons. An item with a start
 * time is a timed event in the trip's zone, which Google resolves into a real
 * moment; the zone matters here for exactly the reason it mattered in the feed,
 * and it is passed as a name rather than converted to UTC because the API takes
 * a local time and a zone and does the resolving itself. An item with no start
 * time is a whole-day event, whose end is the next day because Google, like
 * RFC 5545, treats an end date as exclusive.
 *
 * <p>The default hour for a start with no end is added here rather than in the
 * file writer, and both do it the same way. They are two renderings of one
 * plan, and a plan whose evening item lasts an hour in the file and two on the
 * calendar would be a disagreement the reader could see.
 */
public final class GoogleEvents {

    private static final Logger LOG = Logger.getLogger(GoogleEvents.class.getName());

    private static final String BASE = "https://www.googleapis.com/calendar/v3/calendars/";

    /** No attendees are ever set, so there is nobody for Google to notify. */
    private static final String PARAMETERS = "?sendUpdates=none";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** See {@link ICalendar}: the same guess, so the two renderings agree. */
    private static final Duration DEFAULT_LENGTH = Duration.ofHours(1);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private GoogleEvents() {
        // Static helpers; not instantiable.
    }

    /**
     * What Google said when it refused.
     *
     * <p>Carries the status because two of them mean something specific to the
     * caller. A {@code 404} on an update means the event is gone and the right
     * answer is to create it again; a {@code 410} on a delete means it was
     * already gone and there is nothing to do. Everything else is a failure to
     * report.
     */
    public static final class Refused extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final int status;

        Refused(int status, String message) {
            super(message);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }

        /** @return true when the event this was about no longer exists */
        public boolean isGone() {
            return status == 404 || status == 410;
        }
    }

    /**
     * Creates an event.
     *
     * @return the id Google gave it, which is the only handle there will be on it
     * @throws Refused if Google would not, including when the token is dead
     */
    public static String create(String accessToken, String calendarId, ItineraryItemDTO item,
            ZoneId zone) {
        String body = body(item, zone);
        JsonObject created = send("POST", eventsUrl(calendarId, null), accessToken, body);
        String id = string(created, "id");
        if (id == null || id.isBlank()) {
            // No id means no way to ever update or remove this event. Failing
            // here leaves an orphan event in the reader's calendar, which is bad
            // and is still better than a row claiming to point at one.
            throw new Refused(200, "Google created an event without returning its id");
        }
        return id;
    }

    /**
     * Replaces an event that was written before.
     *
     * @throws Refused with {@link Refused#isGone()} when the event is no longer
     *                 there, which the caller answers by creating it again
     */
    public static void update(String accessToken, String calendarId, String eventId,
            ItineraryItemDTO item, ZoneId zone) {
        send("PUT", eventsUrl(calendarId, eventId), accessToken, body(item, zone));
    }

    /**
     * Removes an event.
     *
     * <p>A 404 or a 410 is not an error here. Both mean the event the reader
     * wanted gone is gone, which is the outcome asked for; the usual cause is
     * somebody having deleted it in Google by hand.
     */
    public static void delete(String accessToken, String calendarId, String eventId) {
        try {
            send("DELETE", eventsUrl(calendarId, eventId), accessToken, null);
        } catch (Refused alreadyGone) {
            if (!alreadyGone.isGone()) {
                throw alreadyGone;
            }
        }
    }

    // ------------------------------------------------------------------
    // The event
    // ------------------------------------------------------------------

    /**
     * One itinerary item as Google's event resource.
     *
     * <p>The item's id goes into a private extended property as well as into the
     * row that records the event. The row is what the application uses; the
     * property is what a person looking at the event in Google can see, and it
     * is the only way to tell which item an event came from once the row is the
     * thing that is missing.
     *
     * @return JSON, ready to send
     */
    static String body(ItineraryItemDTO item, ZoneId zone) {
        LocalDate day = item.getItemDate();
        LocalTime start = item.getStartTime();

        JsonObjectBuilder event = Json.createObjectBuilder();
        event.add("summary", item.getTitle() == null ? "" : item.getTitle());
        if (isPresent(item.getLocation())) {
            event.add("location", item.getLocation());
        }
        if (isPresent(item.getDescription())) {
            event.add("description", item.getDescription());
        }

        if (start == null) {
            event.add("start", Json.createObjectBuilder().add("date", DATE.format(day)));
            event.add("end", Json.createObjectBuilder()
                    .add("date", DATE.format(day.plusDays(1))));
        } else {
            LocalDateTime begins = LocalDateTime.of(day, start);
            // The default hour is added to a local time, before the zone is
            // applied, so an item starting an hour before a daylight-saving
            // change lasts an hour rather than an hour and some minutes. Doing
            // it to the resolved moment would be the other way round.
            LocalDateTime finishes = item.getEndTime() == null
                    || !item.getEndTime().isAfter(start)
                            ? begins.plus(DEFAULT_LENGTH)
                            : LocalDateTime.of(day, item.getEndTime());
            String name = zone.getId();
            event.add("start", Json.createObjectBuilder()
                    .add("dateTime", DATE_TIME.format(begins))
                    .add("timeZone", name));
            event.add("end", Json.createObjectBuilder()
                    .add("dateTime", DATE_TIME.format(finishes))
                    .add("timeZone", name));
        }

        event.add("extendedProperties", Json.createObjectBuilder()
                .add("private", Json.createObjectBuilder()
                        .add("tripmateItemId", String.valueOf(item.getItemId()))));

        // The reader is not being invited to their own plan, so no reminders are
        // invented and nobody is emailed. Google's defaults would do both.
        event.add("reminders", Json.createObjectBuilder().add("useDefault", false));

        StringWriter out = new StringWriter();
        try (JsonWriter writer = Json.createWriter(out)) {
            writer.writeObject(event.build());
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // Talking to Google
    // ------------------------------------------------------------------

    private static String eventsUrl(String calendarId, String eventId) {
        StringBuilder url = new StringBuilder(BASE)
                .append(URLEncoder.encode(calendarId == null ? "primary" : calendarId,
                        StandardCharsets.UTF_8))
                .append("/events");
        if (eventId != null) {
            url.append('/').append(URLEncoder.encode(eventId, StandardCharsets.UTF_8));
        }
        return url.append(PARAMETERS).toString();
    }

    private static JsonObject send(String method, String url, String accessToken, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json");
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json; charset=UTF-8")
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }

        HttpResponse<String> response;
        try {
            response = HTTP.send(request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.io.IOException failed) {
            throw new Refused(0, "could not reach Google: " + failed.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Refused(0, "the request to Google was interrupted");
        }

        if (response.statusCode() / 100 != 2) {
            // The body describes the request, and the request carries the
            // reader's access token in a header rather than in the body, so
            // this is safe to log and useful to have.
            LOG.log(Level.WARNING, "Google refused a calendar write: HTTP {0} {1}",
                    new Object[] {response.statusCode(), errorSummary(response.body())});
            // Google's own sentence is carried through rather than replaced by
            // a status code. It is the difference between "something went wrong"
            // and "the Calendar API is not enabled in your project", and only
            // one of those tells the reader what to do next.
            throw new Refused(response.statusCode(), errorSummary(response.body()));
        }

        return response.body() == null || response.body().isBlank()
                ? Json.createObjectBuilder().build()
                : parse(response.body());
    }

    private static String errorSummary(String body) {
        try {
            JsonObject error = parse(body).getJsonObject("error");
            if (error == null) {
                return "(no detail)";
            }
            String message = string(error, "message");
            return message == null ? "(no detail)" : message;
        } catch (RuntimeException notJson) {
            return "(response was not JSON)";
        }
    }

    private static JsonObject parse(String body) {
        try (JsonReader reader = Json.createReader(new StringReader(body))) {
            return reader.readObject();
        } catch (RuntimeException notJson) {
            throw new Refused(200, "Google's answer could not be read");
        }
    }

    private static String string(JsonObject json, String name) {
        if (json == null) {
            return null;
        }
        jakarta.json.JsonValue value = json.get(name);
        return value instanceof JsonString text ? text.getString() : null;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
