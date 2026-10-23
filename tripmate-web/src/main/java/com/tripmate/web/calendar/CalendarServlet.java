package com.tripmate.web.calendar;

import java.io.IOException;

import com.tripmate.dto.CalendarFeedDTO;
import com.tripmate.exception.ValidationException;
import com.tripmate.remote.CalendarServiceRemote;
import com.tripmate.web.backing.CurrentUser;
import com.tripmate.web.support.Messages;

import jakarta.ejb.EJB;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Serves one trip's plan as a downloadable calendar file.
 *
 * <h2>The authorisation trap this endpoint sits next to</h2>
 *
 * <p>{@code AuthenticationFilter} is mapped to {@code *.xhtml}, so it does not
 * see this URL at all. Any servlet outside that mapping is reachable by anyone
 * who can type the address, and it is the second half of this class's job not to
 * forget it: the caller is checked here, and the trip is checked in
 * {@link CalendarServiceRemote}, which is the only place that knows both who is
 * asking and which trip they are asking about.
 *
 * <p>The status codes follow from that. A caller who is not a member gets 403
 * with the same sentence whether the trip exists or not, because the business
 * tier deliberately does not distinguish the two and this endpoint must not
 * undo that decision by answering differently. A failure the application did not
 * plan for gets 500 and the generic sentence, with the detail in the log.
 *
 * <p>Its sibling, {@link CalendarFeedServlet}, is the same file at an address
 * that carries its own authorisation, because the caller there has no session to
 * check.
 */
@WebServlet(name = "calendarServlet", urlPatterns = CalendarServlet.PATH)
public class CalendarServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    /** The one address this serves. Named so the view can link to it. */
    public static final String PATH = "/calendar/itinerary.ics";

    /**
     * Injected by the remote interface, which is the only view of the bean
     * there is: a bean that exposes a remote business interface is not given a
     * no-interface local view.
     */
    @EJB
    private CalendarServiceRemote calendarService;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {

        // Looked up through CDI rather than injected, for the same reason
        // AuthenticationFilter does it: a servlet is not a CDI bean in portable
        // Jakarta EE, so an @Inject field here would be null in some containers
        // and fine in others.
        CurrentUser currentUser = CDI.current().select(CurrentUser.class).get();
        if (currentUser == null || !currentUser.isLoggedIn()) {
            // Sent to the sign-in page and no further. The reader reaches this
            // address from a page they are already signed in on, so arriving
            // here without a session means it has expired rather than that they
            // were on their way somewhere.
            response.sendRedirect(request.getContextPath() + "/login.xhtml");
            return;
        }

        Long tripId = parseTripId(request.getParameter("tripId"));
        if (tripId == null) {
            CalendarResponse.fail(response, HttpServletResponse.SC_BAD_REQUEST,
                    "That address does not name a trip.");
            return;
        }

        CalendarFeedDTO feed;
        try {
            feed = calendarService.exportItinerary(tripId, currentUser.getUserId());
        } catch (ValidationException refused) {
            // A rule the application made on purpose, with a sentence written
            // for a person. 403 rather than 404 for all of them, including the
            // trip that does not exist, because answering differently is how
            // this endpoint would tell a stranger which trips are real.
            CalendarResponse.fail(response, HttpServletResponse.SC_FORBIDDEN,
                    Messages.describe(refused));
            return;
        } catch (RuntimeException unexpected) {
            CalendarResponse.fail(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    Messages.describe(unexpected));
            return;
        }

        CalendarResponse.sendDownload(response, feed);
    }

    private static Long parseTripId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
