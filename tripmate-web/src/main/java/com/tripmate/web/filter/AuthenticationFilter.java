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
            "/register.xhtml");

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
