package com.tripmate.web.filter;

import java.io.IOException;
import java.util.Set;

import com.tripmate.web.backing.CurrentUser;

import jakarta.enterprise.inject.spi.CDI;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Redirects anyone who is not signed in to the sign-in page.
 *
 * <p>Without this, a page is protected only because nothing links to it. Every
 * URL in the application would be reachable by typing it, and the business tier
 * cannot help: a stateless session bean has no idea who is calling it, which is
 * exactly why the check has to happen here.
 *
 * <p>It also sends in the other direction. A reader who is signed in and asks
 * for the sign-in page, the registration page or the landing page is sent to
 * their dashboard, because those pages exist for people who are not, and the
 * only thing they offer is what the reader has already done.
 *
 * <h2>Two details worth knowing</h2>
 *
 * Faces serves stylesheets, scripts and images through URLs that also end in
 * {@code .xhtml}, so a filter mapped only to {@code *.xhtml} would intercept the
 * application's own CSS and redirect it to the sign-in page. The resource
 * prefix has to be let through explicitly.
 *
 * <p>The bean is looked up through {@code CDI.current()} rather than injected.
 * A servlet filter is not a CDI bean in portable Jakarta EE, so an
 * {@code @Inject} field here would be null in some containers and fine in
 * others.
 */
@WebFilter(urlPatterns = "*.xhtml")
public class AuthenticationFilter implements Filter {

    /**
     * Reachable without signing in.
     *
     * <p>The bare context root and its empty-path form are here because the
     * container serves the welcome file for them. Without that, a request to
     * the root was redirected to sign-in while the same page requested by name
     * loaded normally, so the landing page was unreachable at the address
     * people actually type.
     */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/",
            "",
            "/index.xhtml",
            "/login.xhtml",
            "/register.xhtml",
            // Somebody who has just registered is not signed in yet, on
            // purpose: the account is created there and opened here. It is also
            // where signing in sends an account whose address has not answered,
            // so the page has to be reachable with no session at all.
            "/verify-email.xhtml",
            // Reachable without signing in by definition: somebody who cannot
            // sign in is the only person who needs them.
            "/forgot-password.xhtml",
            "/reset-password.xhtml",
            // And these two by requirement rather than by usefulness. A person
            // deciding whether to make an account needs to be able to read what
            // they are agreeing to first, and Google's reviewers fetch them with
            // no session at all. A policy behind a sign-in is not a published
            // policy.
            "/privacy.xhtml",
            "/terms.xhtml");

    /**
     * The pages that only make sense to somebody who is not signed in.
     *
     * <p>Signing in, creating an account, and confirming one. Being shown these
     * while already signed in is a dead end rather than a courtesy: the form
     * asks for credentials the reader has already given, and the only thing the
     * page offers is the thing they have done.
     *
     * <p>The verification page belongs here for a stronger reason than the
     * other two. A signed-in account has by definition had its address
     * confirmed, so there is nothing for that page to do, and letting it render
     * would put a code box in front of somebody who is already inside.
     *
     * <p>Deliberately not the password pages. Somebody signed in may still
     * follow a reset link from their inbox, and the profile's own "change
     * password" is a different thing from a reset that arrives by email.
     */
    private static final Set<String> GUEST_ONLY_PATHS = Set.of(
            "/login.xhtml",
            "/register.xhtml",
            "/verify-email.xhtml");

    /**
     * Faces serves every stylesheet, script and image under this prefix, and
     * those URLs end in .xhtml too.
     */
    private static final String RESOURCE_PREFIX = "/jakarta.faces.resource/";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String path = httpRequest.getRequestURI()
                .substring(httpRequest.getContextPath().length());

        // Somebody who is already signed in and lands on the root goes straight
        // to their dashboard.
        //
        // The landing page exists to explain the product to a visitor who has
        // not decided yet. To somebody who is signed in it is a page whose only
        // content is a button offering to take them where they were going, so
        // every return visit cost a click for nothing. Reported as exactly
        // that: "it shows me go to dashboard page instead, take me directly
        // there".
        //
        // Checked before the public-path test rather than after, because the
        // root is a public path and would otherwise be served as-is.
        if ((isHome(path) || GUEST_ONLY_PATHS.contains(path)) && isSignedIn(httpRequest)) {
            httpResponse.sendRedirect(httpRequest.getContextPath() + "/dashboard.xhtml");
            return;
        }

        if (isPublic(path) || isSignedIn(httpRequest)) {
            chain.doFilter(request, response);
            return;
        }

        // Remember where they were going so the sign-in can return them there.
        // Stored as a view path without the context, because that is what a
        // Faces redirect outcome expects.
        String target = path;
        if (httpRequest.getQueryString() != null) {
            target = target + "?" + httpRequest.getQueryString();
        }
        httpRequest.getSession().setAttribute(AFTER_LOGIN_ATTRIBUTE, target);

        httpResponse.sendRedirect(httpRequest.getContextPath() + "/login.xhtml");
    }

    private boolean isPublic(String path) {
        return PUBLIC_PATHS.contains(path) || path.startsWith(RESOURCE_PREFIX);
    }

    /**
     * Whether this is the landing page, by any of the names it answers to.
     *
     * <p>All three, because they are not interchangeable at the container
     * level: the root arrives as {@code "/"} or as an empty string depending on
     * how the request was written, and the welcome file arrives as
     * {@code /index.xhtml} for anyone who reached it by name or by a bookmark.
     * Testing only one would have made the redirect work at the address people
     * type and fail at the address they save.
     *
     * @param path the request path with the context removed
     * @return true when the request is for the landing page
     */
    private boolean isHome(String path) {
        return path.isEmpty() || "/".equals(path) || "/index.xhtml".equals(path);
    }

    /**
     * Whether the request carries a signed-in session.
     *
     * <p>Checks for an existing session first. Looking the bean up unconditionally
     * would create a session for every anonymous visitor, including one who only
     * ever sees the sign-in page.
     */
    private boolean isSignedIn(HttpServletRequest request) {
        if (request.getSession(false) == null) {
            return false;
        }
        CurrentUser currentUser = CDI.current().select(CurrentUser.class).get();
        return currentUser != null && currentUser.isLoggedIn();
    }

    /** Session attribute name for the page to return to after signing in. */
    public static final String AFTER_LOGIN_ATTRIBUTE = "afterLoginPath";

    /** Reads and clears the remembered destination. */
    public static String consumeAfterLoginPath(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object value = session.getAttribute(AFTER_LOGIN_ATTRIBUTE);
        session.removeAttribute(AFTER_LOGIN_ATTRIBUTE);
        return value == null ? null : value.toString();
    }
}
