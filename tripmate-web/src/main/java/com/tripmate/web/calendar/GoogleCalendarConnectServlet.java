package com.tripmate.web.calendar;

import java.io.IOException;

import com.tripmate.remote.GoogleCalendarRemote;
import com.tripmate.security.TokenHasher;
import com.tripmate.web.backing.CurrentUser;
import com.tripmate.web.backing.SiteBean;
import com.tripmate.web.support.ExternalAddress;

import jakarta.ejb.EJB;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Sends a reader to Google to ask for permission.
 *
 * <h2>What is kept in the session, and why both</h2>
 *
 * <p>A random {@code state}, which comes back on the callback and is checked
 * against the one stored here. Without it, a request to the callback with a code
 * somebody else obtained would connect the wrong calendar to this account, which
 * is the whole of what the parameter prevents.
 *
 * <p>And the trip they were looking at, so the callback can put them back. It
 * travels in the session rather than in the state, because the state's only job
 * is to be unguessable and mixing a destination into it invites somebody to
 * start trusting it for something.
 *
 * <p>Neither is a credential, and both are discarded the moment they are used.
 */
@WebServlet(name = "googleCalendarConnectServlet", urlPatterns = GoogleCalendarConnectServlet.PATH)
public class GoogleCalendarConnectServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    /** Where a reader starts. The callback's address is this plus {@code /callback}. */
    public static final String PATH = "/calendar/google";

    /** Session key for the value Google will echo back. */
    static final String STATE_ATTRIBUTE = "tm.google.oauthState";

    /** Session key for the trip to return to afterwards. */
    static final String TRIP_ATTRIBUTE = "tm.google.tripId";

    /**
     * Set by the callback, read once by the trip page, which turns it into a toast.
     *
     * <p>Public, unlike the two above, because the page that reads it is in
     * another package. That is the honest description of what this is: the
     * handover between a servlet that cannot raise a message and a bean that
     * can.
     */
    public static final String NOTICE_ATTRIBUTE = "tm.google.notice";

    /** Set by the callback alongside the notice, true when it was a failure. */
    public static final String NOTICE_FAILED_ATTRIBUTE = "tm.google.noticeFailed";

    @EJB
    private GoogleCalendarRemote googleCalendar;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {

        CurrentUser currentUser = CDI.current().select(CurrentUser.class).get();
        if (currentUser == null || !currentUser.isLoggedIn()) {
            response.sendRedirect(request.getContextPath() + "/login.xhtml");
            return;
        }

        Long tripId = parseTripId(request.getParameter("tripId"));
        if (tripId == null) {
            CalendarResponse.fail(response, HttpServletResponse.SC_BAD_REQUEST,
                    "That address does not name a trip to come back to.");
            return;
        }

        String redirectUri = callbackUri(request);
        if (!googleCalendar.isAvailable()) {
            // Sent back with a sentence rather than shown an error page: the
            // reader was going somewhere and has been stopped, and the page they
            // came from is where the explanation belongs.
            remember(request, tripId,
                    "This deployment has no Google client configured, so a calendar cannot be "
                            + "connected. The subscribe address still works.",
                    true);
            response.sendRedirect(request.getContextPath() + tripPage(tripId));
            return;
        }

        String state = TokenHasher.newToken();
        HttpSession session = request.getSession(true);
        session.setAttribute(STATE_ATTRIBUTE, state);
        session.setAttribute(TRIP_ATTRIBUTE, tripId);

        response.sendRedirect(googleCalendar.authorizeUrl(redirectUri, state));
    }

    /**
     * Where Google should send the reader back to.
     *
     * <p>Built from the request, so it follows the host the reader is actually
     * using: the same deployment reachable at localhost and through a tunnel
     * produces two different addresses, and Google requires the one it is given
     * to match what was registered exactly. That is why this is derived rather
     * than configured, and why an operator running behind a tunnel needs
     * {@code TM_TRUST_PROXY} on for it to be right.
     *
     * @param request the request being answered
     * @return an absolute address for the callback
     */
    static String callbackUri(HttpServletRequest request) {
        SiteBean site = CDI.current().select(SiteBean.class).get();
        return ExternalAddress.base(request, site != null && site.isTrustProxy())
                + request.getContextPath() + PATH + "/callback";
    }

    /** The trip page to return to, as a context-relative path. */
    static String tripPage(Long tripId) {
        return "/trips/detail.xhtml?tripId=" + tripId;
    }

    /**
     * Leaves a sentence for the trip page to raise.
     *
     * <p>In the session rather than the request, because the reader is about to
     * be redirected and a request attribute does not survive that. The page
     * takes it and clears it, so it is shown once.
     */
    static void remember(HttpServletRequest request, Long tripId, String notice, boolean failed) {
        HttpSession session = request.getSession(true);
        session.setAttribute(NOTICE_ATTRIBUTE, notice);
        session.setAttribute(NOTICE_FAILED_ATTRIBUTE, failed);
        if (tripId != null) {
            session.setAttribute(TRIP_ATTRIBUTE, tripId);
        }
    }

    static Long parseTripId(String raw) {
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
