package com.tripmate.geo;

import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;

/**
 * Turns a place on the map into a name.
 *
 * <h2>Why this is the one thing here that calls somebody else</h2>
 *
 * <p>Everything else about the map is decided by the reader clicking: where a
 * trip was, where they went, where they are going. Nothing in this application
 * turns text into a coordinate and nothing needs to.
 *
 * <p>The other direction is worth a call. Having clicked Pokhara, being asked to
 * type "Pokhara" is busywork, and being asked for the country is busywork that
 * people get wrong. OpenStreetMap knows both, and the same answer carries the
 * extent of the place, which is what lets the map fill a city rather than pin a
 * dot in it.
 *
 * <h2>Their rules, which are not optional</h2>
 *
 * <p>The Nominatim service is run on donated hardware and its usage policy is
 * explicit: at most one request a second, an identifying User-Agent, and no
 * heavy use. Every call here sets the agent, and the caller runs on a click
 * rather than on a loop.
 *
 * <p>Every method returns null rather than throwing when anything goes wrong.
 * A name is a convenience, and refusing to place a trip because a geocoder was
 * unreachable would turn a nice-to-have into an outage. The reader types the
 * name instead, which is what they did before this existed.
 */
public final class PlaceLookup {

    private static final Logger LOG = Logger.getLogger(PlaceLookup.class.getName());

    private static final String ENDPOINT = "https://nominatim.openstreetmap.org/reverse";

    /** Identifies this application, as their policy requires. */
    private static final String USER_AGENT =
            "TripMate/1.0 (student trip-planning project; contact privacy@tripmate.example)";

    /**
     * How much boundary is worth storing.
     *
     * <p>Simplification keeps a district to a few kilobytes, so this is a
     * guard against a shape nobody expected rather than a limit anything
     * normal meets. A country-sized multipolygon would be megabytes, and a
     * page that renders it would be slow for a line on a map.
     */
    private static final int MAXIMUM_GEOMETRY_LENGTH = 200_000;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private PlaceLookup() {
        // Static helpers; not instantiable.
    }

    /**
     * What is at a point, or null.
     *
     * @param latitude  where
     * @param longitude where
     * @return the name, the country and the extent of what was found, or null
     *         when the service could not be reached or had nothing to say
     */
    public static PlaceName describe(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            return null;
        }

        String url = ENDPOINT
                + "?format=jsonv2"
                + "&lat=" + URLEncoder.encode(String.valueOf(latitude), StandardCharsets.UTF_8)
                + "&lon=" + URLEncoder.encode(String.valueOf(longitude), StandardCharsets.UTF_8)
                // City level rather than street level: a dot on a map is a place
                // somebody went, not an address.
                + "&zoom=10"
                + "&addressdetails=1"
                // The boundary itself, not just the middle of it, so the map
                // fills the place rather than putting a dot in it.
                + "&polygon_geojson=1"
                // Simplified by about a hundred metres. Unsimplified, a
                // district is tens of thousands of points and a hundred
                // kilobytes; simplified, it is a few hundred points, which is
                // more detail than a map at any zoom the reader will use can
                // show anyway.
                + "&polygon_threshold=0.001"
                // Without this the answer comes back in the local language,
                // which is right for the map and wrong for an interface that is
                // in English.
                + "&accept-language=en";

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response =
                    HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                LOG.log(Level.INFO, "Place lookup answered {0}", response.statusCode());
                return null;
            }
            return parse(response.body());
        } catch (java.io.IOException unreachable) {
            LOG.log(Level.INFO, "Place lookup could not be reached", unreachable);
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException unexpected) {
            LOG.log(Level.INFO, "Place lookup failed", unexpected);
            return null;
        }
    }

    /**
     * Reads the answer.
     *
     * <p>The place is named from the most specific thing that is a settlement:
     * a city, a town, a village, a municipality. Falling back to the
     * administrative name is why clicking a point in the countryside used to
     * suggest a district, which is less wrong than an empty box.
     */
    private static PlaceName parse(String body) {
        try (JsonReader reader = Json.createReader(new StringReader(body))) {
            JsonObject json = reader.readObject();
            JsonObject address = json.getJsonObject("address");

            String name = firstOf(
                    address == null ? null : string(address, "city"),
                    address == null ? null : string(address, "town"),
                    address == null ? null : string(address, "village"),
                    address == null ? null : string(address, "municipality"),
                    string(json, "name"));
            String country = address == null ? null : string(address, "country");

            if (name == null && country == null) {
                return null;
            }

            PlaceName place = new PlaceName(name, country);

            // The boundary, kept as the text it arrived as. Nothing here reads
            // it: the page hands it to Leaflet, which is the only thing that
            // knows what to do with it, and turning it into numbers and back
            // would be work in the middle of a request for no reason.
            jakarta.json.JsonValue geometry = json.get("geojson");
            if (geometry != null && geometry.getValueType() == jakarta.json.JsonValue.ValueType.OBJECT) {
                String text = geometry.toString();
                if (text.length() <= MAXIMUM_GEOMETRY_LENGTH) {
                    place.setGeometry(text);
                } else {
                    LOG.log(Level.INFO, "A boundary of {0} characters was too large to keep",
                            text.length());
                }
            }
            return place;
        } catch (RuntimeException notWhatWasExpected) {
            LOG.log(Level.INFO, "Place lookup returned something unreadable", notWhatWasExpected);
            return null;
        }
    }

    private static String firstOf(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private static String string(JsonObject json, String name) {
        jakarta.json.JsonValue value = json.get(name);
        return value instanceof JsonString text ? text.getString() : null;
    }
}
