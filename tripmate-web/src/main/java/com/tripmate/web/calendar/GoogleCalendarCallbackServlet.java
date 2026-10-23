package com.tripmate.web.calendar;

import java.io.IOException;

import com.tripmate.exception.ValidationException;
import com.tripmate.remote.GoogleCalendarRemote;
import com.tripmate.web.backing.CurrentUser;
import com.tripmate.web.support.Messages;

import jakarta.ejb.EJB;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Where Google sends a reader back to, with the answer.
 *
 * <h2>Everything this endpoint is given is hostile until checked</h2>
 *
 * <p>It is reached by a redirect the reader's browser performs, so every part of
 * the request is a value Google sent, a value an attacker sent, or a value an
 * attacker made Google send. It is also unauthenticated by URL, because Google
 * arrives with no session cookie of ours beyond whatever the browser already
 * has, so nothing here may assume the caller is who the parameters claim.
 *
 * <p>Three checks follow from that:
 *
 * <ul>
 * <li><b>The state must match the one this browser was given.</b> That is what
 * makes a code somebody else obtained useless here. It is compared with
 * {@code MessageDigest.isEqual} rather than {@code equals}, so the comparison
 * does not stop at the first differing character.</li>
 * <li><b>The reader must be signed in.</b> Taken from the session, never from a
 * parameter, so a callback arriving in the wrong browser connects nothing.</li>
 * <li><b>The message shown is never Google's.</b> Whatever went wrong has
 * already been logged; the reader gets a sentence this application wrote.</li>
 * </ul>
 *
 * <p>The state and the trip are removed from the session on the way through,
 * whether they were used or refused, so a callback cannot be replayed.
 */
@WebServlet(name = "googleCalendarCallbackServlet", urlPatterns = GoogleCalendarCallbackServlet.PATH)
public class GoogleCalendarCallbackServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    /** Must match the address registered against the Google client exactly. */
    public static final String PATH = GoogleCalendarConnectServlet.PATH + "/callback";

    @EJB
    private GoogleCalendarRemote googleCalendar;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {

        HttpSession session = request.getSession(false);
        String expectedState = take(session, GoogleCalendarConnectServlet.STATE_ATTRIBUTE);
        Long tripId = tripIdFrom(session);
        session = request.getSession(true);
        session.removeAttribute(GoogleCalendarConnectServlet.TRIP_ATTRIBUTE);

        CurrentUser currentUser = CDI.current().select(CurrentUser.class).get();
        if (currentUser == null || !currentUser.isLoggedIn()) {
            // A different browser, or a session that expired while the reader was
            // at Google. Nothing can be connected without knowing who to
            // connect it to, which is exactly the point.
            response.sendRedirect(request.getContextPath() + "/login.xhtml");
            return;
        }

        if (tripId == null) {
            // No trip to return to, so there is nothing to say and nowhere to
            // say it. The reader has still connected, or not; the page will show
            // which when they reach it.
            response.sendRedirect(request.getContextPath() + "/trips/list.xhtml");
            return;
        }

        if (!matches(expectedState, request.getParameter("state"))) {
            refuse(request, tripId, response,
                    "That connection did not start from this page, so it was ignored. "
                            + "Try connecting again.");
            return;
        }

        // Google reports a refusal as a parameter rather than a status, because
        // the reader's browser is the one being answered.
        String refused = request.getParameter("error");
        if (refused != null && !refused.isBlank()) {
            refuse(request, tripId, response,
                    "Google Calendar was not connected. Nothing was changed.");
            return;
        }

        try {
            googleCalendar.connect(currentUser.getUserId(),
                    request.getParameter("code"), GoogleCalendarConnectServlet.callbackUri(request));
            GoogleCalendarConnectServlet.remember(request, tripId,
                    "Google Calendar is connected. TripMate can write this trip into it.", false);
        } catch (ValidationException refusedConnection) {
            refuse(request, tripId, response, Messages.describe(refusedConnection));
            return;
        } catch (RuntimeException unexpected) {
            refuse(request, tripId, response, Messages.describe(unexpected));
            return;
        }

        response.sendRedirect(request.getContextPath()
                + GoogleCalendarConnectServlet.tripPage(tripId));
    }

    private static void refuse(HttpServletRequest request, Long tripId,
            HttpServletResponse response, String sentence) throws IOException {
        GoogleCalendarConnectServlet.remember(request, tripId, sentence, true);
        response.sendRedirect(request.getContextPath()
                + GoogleCalendarConnectServlet.tripPage(tripId));
    }

    /** Reads and removes a session value, tolerating there being no session. */
    private static String take(HttpSession session, String key) {
        if (session == null) {
            return null;
        }
        Object value = session.getAttribute(key);
        session.removeAttribute(key);
        return value == null ? null : value.toString();
    }

    private static Long tripIdFrom(HttpSession session) {
        if (session == null) {
            return null;
        }
        Object value = session.getAttribute(GoogleCalendarConnectServlet.TRIP_ATTRIBUTE);
        if (value instanceof Long id) {
            return id;
        }
        return value == null ? null : GoogleCalendarConnectServlet.parseTripId(value.toString());
    }

    /**
     * Constant-time comparison of the state.
     *
     * <p>An early-exit comparison leaks, through timing, how much of a guess was
     * right, which over many attempts is enough to recover the value. The state
     * is short-lived and the window is small, and the cost of not having to
     * reason about that is two lines.
     */
    private static boolean matches(String expected, String presented) {
        if (expected == null || presented == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                presented.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
