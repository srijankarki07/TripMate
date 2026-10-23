package com.tripmate.web.calendar;

import java.io.IOException;

import com.tripmate.dto.CalendarFeedDTO;
import com.tripmate.remote.CalendarServiceRemote;
import com.tripmate.security.TokenHasher;

import jakarta.ejb.EJB;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Serves one trip's plan at an address a calendar application can subscribe to.
 *
 * <h2>The one endpoint here that nobody signs in to</h2>
 *
 * <p>Google Calendar, Apple Calendar and Outlook fetch a subscribed calendar
 * from their own servers, on their own schedule, with no browser and therefore
 * no session and no cookie. There is nothing to check a user against, so the
 * address itself is the authorisation: it carries a 256-bit token that names one
 * trip, and holding it is what entitles the caller to read that trip's plan.
 *
 * <p>This is worth being explicit about, because it is the only place in the
 * application where a URL alone is enough. Three things keep it contained:
 *
 * <ul>
 * <li>The token is unguessable. It is minted by {@link TokenHasher} from
 * {@code SecureRandom}, the same source the password reset links use.</li>
 * <li>It grants exactly one trip, and only its itinerary. It is not an account
 * and it does not open anything else.</li>
 * <li>It can be taken away. An administrator can issue a new one, and every
 * client still holding the old address stops being able to read.</li>
 * </ul>
 *
 * <p>What it deliberately does not do is tell a caller anything about which
 * tokens exist. An unknown token and a malformed one are the same answer:
 * {@code 404}, with no body worth reading. A trip with nothing planned is not
 * one of those; see {@link CalendarServiceRemote#feedFor}.
 */
@WebServlet(name = "calendarFeedServlet", urlPatterns = CalendarFeedServlet.PATH + "/*")
public class CalendarFeedServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    /** The prefix an address is built from, so the view and this agree. */
    public static final String PATH = "/calendar/feed";

    /** The suffix a calendar application expects to find on a feed address. */
    private static final String SUFFIX = ".ics";

    /** What a token is made of, and how long it can be. See the schema's column. */
    private static final int TOKEN_LIMIT = 64;

    @EJB
    private CalendarServiceRemote calendarService;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {

        String token = tokenFrom(request.getPathInfo());
        if (token == null) {
            // The shape of the address is wrong, which is a caller's mistake and
            // not a question about which trips exist, so it is told apart from
            // an unknown token. It leaks nothing: no token was presented.
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        CalendarFeedDTO feed;
        try {
            feed = calendarService.feedFor(token);
        } catch (RuntimeException unexpected) {
            // Nothing useful can be said to a calendar application, and saying
            // it in HTML in the middle of what it expects to be a calendar file
            // would be worse than saying nothing. The log has the detail.
            log("Calendar feed failed for a presented token", unexpected);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        if (feed == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        boolean unchanged =
                CalendarResponse.sendFeed(response, feed, request.getHeader("If-None-Match"));

        // One line per poll, which is the only way to answer the question a
        // subscriber actually has: has the calendar come back to look yet?
        // Google fetches on a schedule of its own and says nothing about when,
        // so without this the only way to tell whether an edit has reached a
        // reader is to open their calendar and see, and the answer to "not
        // yet" looks exactly like the answer to "broken".
        //
        // The token is deliberately not in the line. It is a credential, and a
        // credential written into a log is one handed to everybody who can read
        // the log.
        log(unchanged
                ? "Calendar feed polled: " + feed.getTripName() + ", unchanged, answered 304"
                : "Calendar feed polled: " + feed.getTripName() + ", served in full");
    }

    /**
     * The token an address names, or null when it does not name one.
     *
     * <p>The shape is checked before the database is asked anything, so a
     * request for {@code /calendar/feed/} or for a path with a slash in it or
     * for a megabyte of text is refused without a query. The character set is
     * the one {@link TokenHasher} mints in, URL-safe base64, so a token that
     * could not have been issued is not looked up.
     *
     * @param pathInfo everything after the servlet's own prefix, or null
     * @return the token, or null when the address is not one this serves
     */
    private static String tokenFrom(String pathInfo) {
        if (pathInfo == null || !pathInfo.startsWith("/") || !pathInfo.endsWith(SUFFIX)) {
            return null;
        }
        String token = pathInfo.substring(1, pathInfo.length() - SUFFIX.length());
        if (token.isEmpty() || token.length() > TOKEN_LIMIT) {
            return null;
        }
        for (int i = 0; i < token.length(); i++) {
            char character = token.charAt(i);
            boolean allowed = (character >= 'A' && character <= 'Z')
                    || (character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')
                    || character == '-' || character == '_';
            if (!allowed) {
                return null;
            }
        }
        return token;
    }
}
