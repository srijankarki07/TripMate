/*
 * The colour theme.
 *
 * Loaded in the page head, without defer, and that is the whole reason it is a
 * separate file from tripmate.js. It has two jobs and they have to run at
 * different times: deciding the theme has to happen before the document body
 * renders, and wiring the toggle has to happen after. Splitting them into two
 * files would cost a second request to keep them apart; keeping them together
 * and running them at two points does not.
 *
 * The <html> element already carries data-theme, written by ThemeBean from the
 * cookie before the page was sent. This file exists for the one case the server
 * cannot cover: a visitor who has never chosen, where the answer has to come
 * from their operating system. Doing that read here, inline and blocking, is
 * what keeps it from being a flash of the wrong theme.
 */
(function () {
    "use strict";

    var COOKIE = "tm-theme";
    var DARK = "dark";
    var LIGHT = "light";
    var ONE_YEAR_SECONDS = 31536000;

    function readChoice() {
        var match = document.cookie.match(/(?:^|;\s*)tm-theme=([^;]*)/);
        return match ? decodeURIComponent(match[1]) : null;
    }

    /*
     * SameSite=Lax rather than Strict. The cookie is a display preference and
     * carries nothing worth protecting, so the stricter setting would only mean
     * arriving from an external link rendered in the wrong theme. It is not
     * HttpOnly, because the client has to be able to write it.
     */
    function remember(value, path) {
        document.cookie = COOKIE + "=" + encodeURIComponent(value)
            + "; path=" + (path || "/")
            + "; max-age=" + ONE_YEAR_SECONDS
            + "; samesite=lax";
    }

    /* The context path, so the cookie is scoped to this application rather than
       to every application on the host. Read from <html> because at head time
       the body, and anything in it, does not exist yet. */
    function contextPath() {
        return document.documentElement.getAttribute("data-tm-context-path") || "/";
    }

    /*
     * The first-visit case. A choice already made is left alone: the server has
     * rendered it, and re-deciding here could only disagree with it.
     */
    var choice = readChoice();
    if (choice !== DARK && choice !== LIGHT) {
        if (window.matchMedia
                && window.matchMedia("(prefers-color-scheme: dark)").matches) {
            document.documentElement.setAttribute("data-theme", DARK);
            /* Recorded as well as applied. Without this the next page would be
               rendered light by the server and flipped again here, and the
               charts, which the server colours, would stay light on a dark
               page. Writing it now means the server agrees from the next
               request onwards, and the first request is always a page with no
               charts on it. */
            remember(DARK, contextPath());
        }
    }

    /* The colours a chart is drawn in, read from the stylesheet. Read before
       the theme changes and again after it, so the two can be paired up by
       name without anything here knowing what any of them are. */
    function palette() {
        var root = window.getComputedStyle(document.documentElement);
        var read = function (name) {
            return (root.getPropertyValue(name) || "").trim();
        };
        return {
            "chart-1": read("--tm-chart-1"),
            "chart-2": read("--tm-chart-2"),
            "chart-3": read("--tm-chart-3"),
            "chart-4": read("--tm-chart-4"),
            "chart-5": read("--tm-chart-5"),
            "chart-6": read("--tm-chart-6"),
            "chart-other": read("--tm-chart-other"),
            "chart-axis": read("--tm-chart-axis"),
            "chart-grid": read("--tm-chart-grid"),
            "surface-strong": read("--tm-color-surface-strong"),
            "ink": read("--tm-color-ink"),
            "canvas": read("--tm-color-canvas")
        };
    }

    function toggle(button) {
        var next = document.documentElement.getAttribute("data-theme") === DARK
            ? LIGHT
            : DARK;

        var before = palette();

        remember(next, button.getAttribute("data-tm-context-path") || contextPath());

        /* The attribute changes the whole page at once: every colour on it is a
           custom property, so nothing needs reloading for the page itself. */
        document.documentElement.setAttribute("data-theme", next);

        var after = palette();

        /* The charts are the one thing that cannot follow, because Chart.js
           draws them onto a canvas from colours handed over in JSON. They are
           recoloured in place instead of reloading the page around them. A
           reload used to take the reader's place with it, which read as a
           glitch rather than as a change of colour. */
        if (typeof window.tmRecolourCharts === "function") {
            window.tmRecolourCharts(before, after);
        } else {
            /* The chart script has not run yet on a page that has no charts,
               which is every page but the reports. Nothing to recolour. */
        }
    }

    function wire() {
        var buttons = document.querySelectorAll("[data-tm-theme-toggle]");
        Array.prototype.forEach.call(buttons, function (button) {
            button.addEventListener("click", function (event) {
                event.preventDefault();
                toggle(button);
            });
        });
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", wire);
    } else {
        wire();
    }
})();
