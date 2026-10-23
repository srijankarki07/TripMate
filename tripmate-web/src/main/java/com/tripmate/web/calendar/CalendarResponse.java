package com.tripmate.web.calendar;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.tripmate.dto.CalendarFeedDTO;

import jakarta.servlet.http.HttpServletResponse;

/**
 * How the two calendar endpoints answer.
 *
 * <p>Both serve the same file and differ only in what the answer means, so the
 * headers, the file name and the refusal page live here rather than twice. The
 * difference is one word: {@link #sendDownload} says {@code attachment}, so a
 * browser saves the file, and {@link #sendFeed} says {@code inline} and carries
 * an entity tag, because a calendar application is reading it rather than a
 * person saving it.
 *
 * <h2>Why these are servlets at all</h2>
 *
 * <p>Everything else in this application is a Faces view. These two are not:
 * their whole body is a file, they are reached by a link, and one of them is
 * fetched by a machine that has never seen a page of this application. Faces
 * produces a download through an Ajax action and a cookie that generated
 * JavaScript has to carry, which turns a single GET into a request that cannot
 * be bookmarked, cannot be repeated by refreshing, and cannot be fetched by
 * anything that is not a browser running the page.
 */
final class CalendarResponse {

    /** The extension every calendar application looks for. */
    private static final String EXTENSION = ".ics";

    /** Longest name taken from a trip name, before the extension. */
    private static final int NAME_LIMIT = 60;

    /** Where a reader who has been refused should go next. */
    private static final String VIEW_PATH = "/trips/list.xhtml";

    private static final String CALENDAR_TYPE = "text/calendar; charset=UTF-8";

    private CalendarResponse() {
        // Static helpers; not instantiable.
    }

    // ------------------------------------------------------------------
    // The file
    // ------------------------------------------------------------------

    /**
     * Answers with the file, for saving.
     *
     * <p>Nothing between here and the browser may keep a copy. The body is one
     * signed-in reader's trip, and it is meant to be read at the moment it is
     * asked for, so the response carries no validator and no lifetime at all.
     */
    static void sendDownload(HttpServletResponse response, CalendarFeedDTO feed)
            throws IOException {
        byte[] body = feed.getContent().getBytes(StandardCharsets.UTF_8);

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(CALENDAR_TYPE);
        response.setHeader("Content-Disposition", contentDisposition(feed.getTripName(), true));
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setContentLength(body.length);

        response.getOutputStream().write(body);
    }

    /**
     * Answers with the file, for a calendar application that polls.
     *
     * <p>Distinct from a download in three ways, and each is deliberate.
     * {@code inline}, because nothing should be saved to somebody's downloads
     * folder. An {@code ETag}, because the same unchanged plan renders to the
     * same bytes and there is no reason to send it again. And {@code private,
     * no-cache} rather than {@code no-store}: the client may keep its copy, but
     * it has to ask whether that copy is still current before using it, which is
     * what makes the entity tag worth having.
     *
     * <p>When the tag the caller already holds matches, the answer is a bare
     * {@code 304} with no body. That is the whole point of the tag.
     *
     * @param ifNoneMatch the caller's {@code If-None-Match}, or null
     * @return true when the request was answered as unchanged
     */
    static boolean sendFeed(HttpServletResponse response, CalendarFeedDTO feed, String ifNoneMatch)
            throws IOException {
        byte[] body = feed.getContent().getBytes(StandardCharsets.UTF_8);
        String tag = etag(body);

        if (tag.equals(ifNoneMatch)) {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            response.setHeader("ETag", tag);
            response.setHeader("Cache-Control", "private, no-cache");
            return true;
        }

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(CALENDAR_TYPE);
        response.setHeader("Content-Disposition", contentDisposition(feed.getTripName(), false));
        response.setHeader("ETag", tag);
        response.setHeader("Cache-Control", "private, no-cache");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setContentLength(body.length);

        response.getOutputStream().write(body);
        return false;
    }

    /**
     * A tag for one rendering of the file.
     *
     * <p>Computed from the bytes that are about to be sent rather than from
     * anything about the data behind them. Nothing in the file depends on when
     * it was asked for, so an unchanged plan produces an unchanged tag, and the
     * question "has this changed" is answered by the only thing that can answer
     * it exactly.
     *
     * <p>SHA-256 rather than a checksum. A collision here is not a security
     * failure but it is a real one: two different plans sharing a tag means a
     * calendar is told its copy is current when it is not, and the reader sees a
     * stale plan with nothing to explain it.
     */
    static String etag(byte[] body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
            return "\"" + HexFormat.of().formatHex(digest, 0, 16) + "\"";
        } catch (NoSuchAlgorithmException impossible) {
            // Required of every Java platform, so this is unreachable. Answered
            // by sending the file rather than failing, because no validator is
            // better than no response.
            return null;
        }
    }

    // ------------------------------------------------------------------
    // The file name
    // ------------------------------------------------------------------

    /**
     * Names the file, in both the forms a header allows.
     *
     * <p>The trip name is the reader's own words and can hold anything, so it is
     * not written into a header as it stands. A quote would end the quoted
     * string early and turn the rest of the name into further header syntax.
     * {@link #fileName} keeps only letters, digits and single hyphens, so the
     * quoted form is always safe.
     *
     * <p>A name in another script has no ASCII form worth the name, so when the
     * two differ the file also carries {@code filename*}, the RFC 5987 form that
     * encodes the real name in UTF-8. A client that understands it uses the
     * reader's own spelling; one that does not falls back to the quoted form,
     * which is at worst a plainer name than the trip has.
     *
     * @param attachment true to save the file, false to read it in place
     */
    static String contentDisposition(String tripName, boolean attachment) {
        String name = fileName(tripName);
        String ascii = asciiOnly(name);
        String disposition = attachment ? "attachment" : "inline";

        if (ascii.equals(name)) {
            return disposition + "; filename=\"" + name + EXTENSION + "\"";
        }
        String encoded = URLEncoder.encode(name + EXTENSION, StandardCharsets.UTF_8)
                .replace("+", "%20");
        return disposition + "; filename=\"" + ascii + EXTENSION + "\""
                + "; filename*=UTF-8''" + encoded;
    }

    /**
     * Reduces a trip name to something safe to put in a header.
     *
     * <p>Letters and digits are kept, in whatever script they are written in; a
     * run of anything else becomes one hyphen and a leading or trailing one is
     * dropped, so {@code "Pokhara / Weekend: day 1"} becomes
     * {@code "Pokhara-Weekend-day-1"}. The length is capped because the name is
     * arbitrary and a header has no use for a novel.
     *
     * @return a non-empty name with no extension, never containing a quote, a
     *         backslash, a control character or a path separator
     */
    static String fileName(String tripName) {
        StringBuilder name = new StringBuilder();
        boolean lastWasSeparator = true;
        if (tripName != null) {
            for (int i = 0; i < tripName.length() && name.length() < NAME_LIMIT; i++) {
                char character = tripName.charAt(i);
                if (Character.isLetterOrDigit(character)) {
                    name.append(character);
                    lastWasSeparator = false;
                } else if (!lastWasSeparator) {
                    name.append('-');
                    lastWasSeparator = true;
                }
            }
        }
        while (name.length() > 0 && name.charAt(name.length() - 1) == '-') {
            name.setLength(name.length() - 1);
        }
        return name.length() == 0 ? "trip-itinerary" : name.toString();
    }

    /**
     * The same name with every non-ASCII character removed.
     *
     * <p>Removing a character can leave a hyphen with nothing before it, so the
     * result is trimmed again: a name written entirely in another script would
     * otherwise fall back to {@code -Pokhara} rather than to {@code Pokhara}.
     *
     * @return an ASCII name, or the generic one when nothing survives
     */
    private static String asciiOnly(String name) {
        StringBuilder ascii = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char character = name.charAt(i);
            if (character < 0x80) {
                ascii.append(character);
            }
        }
        while (ascii.length() > 0 && ascii.charAt(0) == '-') {
            ascii.deleteCharAt(0);
        }
        while (ascii.length() > 0 && ascii.charAt(ascii.length() - 1) == '-') {
            ascii.setLength(ascii.length() - 1);
        }
        return ascii.length() == 0 ? "trip-itinerary" : ascii.toString();
    }

    // ------------------------------------------------------------------
    // The refusals
    // ------------------------------------------------------------------

    /**
     * Answers with a sentence rather than a file.
     *
     * <p>A page of its own, small and self-contained, rather than a redirect to
     * one of the application's views. A reader looking at a download that did
     * not happen should not be sent to a page with navigation on it, which
     * would look like the feature had worked and taken them somewhere. The
     * styles are inline and minimal because this page is deliberately outside
     * the stylesheet: it is served by a servlet that Faces does not route, so it
     * has no access to the application's CSS, and a broken link to it would be
     * worse than plain type.
     *
     * <p>A feed's refusals are not this. That caller is a machine, and it is
     * told with a status and nothing else.
     *
     * @param status the HTTP status, which matches how the caller should read it
     * @param sentence what to tell the reader, safe to render as text
     */
    static void fail(HttpServletResponse response, int status, String sentence)
            throws IOException {
        response.setStatus(status);
        response.setContentType("text/html; charset=UTF-8");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "no-store");

        PrintWriter out = response.getWriter();
        out.println("<!DOCTYPE html>");
        out.println("<html lang=\"en\"><head><meta charset=\"UTF-8\"/>");
        out.println("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>");
        out.println("<title>Nothing to download</title>");
        out.println("<style>");
        out.println(":root{color-scheme:light dark}");
        out.println("body{margin:0;min-height:100vh;display:grid;place-items:center;");
        out.println("background:#ffffff;color:#3f3f3f;");
        out.println("font:400 16px/1.6 system-ui,-apple-system,'Segoe UI',Roboto,sans-serif}");
        out.println("@media (prefers-color-scheme:dark){body{background:#0b0b0e;color:#cfcfd6}}");
        out.println("main{max-width:32rem;padding:2rem}");
        out.println("h1{margin:0 0 .5rem;font-size:1.25rem;font-weight:600;color:#222222}");
        out.println("@media (prefers-color-scheme:dark){h1{color:#f4f4f6}}");
        out.println("a{color:#ff385c}");
        out.println("</style></head><body><main>");
        out.println("<h1>Nothing to download</h1>");
        out.print("<p>");
        // Escaped because this sentence is assembled from a message that could
        // one day carry something a person typed. Cheap, and it means the page
        // stays a page.
        out.print(html(sentence));
        out.println("</p>");
        out.print("<p><a href=\"");
        out.print(html(VIEW_PATH));
        out.println("\">Back to your trips</a></p>");
        out.println("</main></body></html>");
    }

    /** Escapes the five characters that would otherwise be markup. */
    static String html(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
