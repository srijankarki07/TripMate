package com.tripmate.web.backing;

import jakarta.enterprise.context.RequestScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import jakarta.servlet.http.Cookie;

/**
 * Which colour theme the current page is rendered in.
 *
 * <h2>Why the server chooses, and not a media query</h2>
 *
 * The obvious way to add a dark theme to a stylesheet is a
 * {@code prefers-color-scheme} block, and the client never has to tell anyone.
 * That does not work here, because the report charts are not CSS. They are
 * Chart.js configuration assembled in Java and handed to {@code p:chart} as
 * JSON, and a {@code <canvas>} cannot read a custom property. The palette has
 * to be picked before the response is written, so the decision has to reach the
 * server either way.
 *
 * <p>
 * Once it does, reading it from a cookie is strictly better than a media
 * query, for two reasons. The correct colours are in the first byte of HTML, so
 * there is no flash of the wrong theme while a script runs. And the choice
 * outlives the session, which a session-scoped bean would not: somebody who
 * prefers dark does not have to say so again every time they sign out.
 *
 * <h2>Light is the default, deliberately</h2>
 *
 * With no cookie the answer is light, which is what every page rendered before
 * this class existed. A first-time visitor on a dark desktop therefore gets one
 * light page, the sign-in page, which carries no charts, so the server and the
 * stylesheet cannot disagree about anything visible. The inline script in the
 * page head flips it and records the preference, so the second page onwards is
 * dark and the two agree by construction. Defaulting the other way would mean
 * guessing from a request header the browser does not send unless asked.
 */
@Named
@RequestScoped
public class ThemeBean {

    /**
     * The cookie the choice is kept in. Named for the application, not the app
     * server.
     */
    public static final String COOKIE_NAME = "tm-theme";

    /** A year. The preference is not a session and should outlive one. */
    public static final int COOKIE_MAX_AGE_SECONDS = 31_536_000;

    private static final String DARK = "dark";
    private static final String LIGHT = "light";

    /**
     * The theme to render, as the value of the {@code data-theme} attribute.
     *
     * <p>
     * Returns one of exactly two literals, never the raw cookie. The cookie
     * is attacker-controlled in the sense that anyone can edit it in a browser,
     * and a value reflected straight into an attribute is how a markup
     * injection starts. Anything that is not the literal {@code "dark"} is
     * light, so an unexpected value is ignored rather than echoed.
     *
     * @return {@code "dark"} or {@code "light"}, never null
     */
    public String getTheme() {
        Cookie[] cookies = requestCookies();
        if (cookies == null) {
            return LIGHT;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName()) && DARK.equals(cookie.getValue())) {
                return DARK;
            }
        }
        return LIGHT;
    }

    /** @return true when the dark palette should be sent */
    public boolean isDark() {
        return DARK.equals(getTheme());
    }

    /**
     * The other theme, for the toggle button's accessible label.
     *
     * <p>
     * The label names what the button will do rather than what is currently
     * true, which is the convention a screen reader user expects from a
     * control: "Switch to dark theme" is an instruction, "Dark theme" is a
     * state the listener then has to work out the direction of.
     *
     * @return a sentence fragment naming the theme this button selects
     */
    public String getToggleLabel() {
        return isDark() ? "Light Mode" : "Dark Mode";
    }

    /**
     * The icon the toggle shows: the theme it switches to, not the one in use.
     *
     * <p>
     * A moon on a light page reads as "go dark", which is the action, and is
     * what people expect from the control in every other application that has
     * one.
     *
     * @return a PrimeIcons class name
     */
    public String getToggleIcon() {
        return isDark() ? "pi-sun" : "pi-moon";
    }

    private static Cookie[] requestCookies() {
        FacesContext context = FacesContext.getCurrentInstance();
        if (context == null) {
            return null;
        }
        Object request = context.getExternalContext().getRequest();
        if (request instanceof jakarta.servlet.http.HttpServletRequest http) {
            return http.getCookies();
        }
        return null;
    }
}
