/*
 * TripMate's client-side behaviour.
 *
 * Deliberately small and dependency-free. Everything here is progressive: the
 * toasts are rendered by the server and every one of them has a working close
 * button in plain HTML, so with JavaScript disabled the page is still correct,
 * just less tidy.
 *
 * No framework is used because the application has exactly two pieces of
 * interactive behaviour and pulling in a bundler for them would cost more than
 * it saves.
 */
(function () {
    "use strict";

    /* How long a leaving toast takes to animate out. Kept in step with the
       --tm-motion-slow token; see the stylesheet. */
    var LEAVE_MS = 260;

    function prefersReducedMotion() {
        return window.matchMedia
            && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    }

    /*
     * Removes a toast.
     *
     * The element is taken out of the document on a timer rather than on the
     * transitionend event. An animation that is suppressed, interrupted or
     * never starts would leave the event unfired and the toast on screen
     * forever, so the removal is driven by the timer and the animation is
     * decoration on top of it.
     */
    function dismiss(el) {
        if (el.dataset.tmLeaving === "1") {
            return;
        }
        el.dataset.tmLeaving = "1";
        el.classList.add("tm-toast-leaving");
        window.setTimeout(function () {
            if (el.parentNode) {
                el.parentNode.removeChild(el);
            }
            stackEmpty();
        }, prefersReducedMotion() ? 0 : LEAVE_MS);
    }

    /* Hides the container once the last toast has gone, so it stops reserving
       space in the corner of the viewport. */
    function stackEmpty() {
        var stack = document.querySelector(".tm-toast-stack");
        if (stack && !stack.querySelector("[data-tm-toast]")) {
            stack.setAttribute("hidden", "hidden");
        }
    }

    function prepare(el) {
        if (el.dataset.tmReady === "1") {
            return;
        }
        el.dataset.tmReady = "1";

        var close = el.querySelector("[data-tm-toast-close]");
        if (close) {
            close.addEventListener("click", function () {
                dismiss(el);
            });
        }

        var life = parseInt(el.getAttribute("data-tm-life"), 10) || 0;
        if (life <= 0) {
            /* Errors and warnings stay until dismissed. */
            return;
        }

        var timer = window.setTimeout(function () {
            dismiss(el);
        }, life);

        /* A message that disappears while it is being read is worse than one
           that never appeared, so the countdown pauses while the pointer or the
           keyboard is on it. */
        function hold() {
            window.clearTimeout(timer);
        }
        el.addEventListener("mouseenter", hold);
        el.addEventListener("focusin", hold);
    }

    function prepareAll(root) {
        var toasts = (root || document).querySelectorAll("[data-tm-toast]");
        Array.prototype.forEach.call(toasts, prepare);
    }

    function start() {
        prepareAll(document);

        /* PrimeFaces replaces parts of the document on every AJAX update, so a
           toast rendered by an update would otherwise never be wired up. */
        if (!window.MutationObserver) {
            return;
        }
        new MutationObserver(function (records) {
            records.forEach(function (record) {
                Array.prototype.forEach.call(record.addedNodes, function (node) {
                    if (node.nodeType !== 1) {
                        return;
                    }
                    if (node.hasAttribute && node.hasAttribute("data-tm-toast")) {
                        prepare(node);
                    } else if (node.querySelectorAll) {
                        prepareAll(node);
                    }
                });
            });
        }).observe(document.body, { childList: true, subtree: true });
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", start);
    } else {
        start();
    }

    /*
     * Tooltips.
     *
     * Every title attribute in the application is a sentence written for a
     * reader, and the browser's own tooltip is the one surface that cannot be
     * styled, cannot be positioned, and arrives in the operating system's
     * colours a second after the pointer stops. So the title is taken off the
     * element while the pointer is on it and a bubble is drawn instead.
     *
     * The title is moved rather than copied: an element that still has one has
     * its native tooltip drawn over the top of ours, which is the one outcome
     * worse than not doing this at all.
     *
     * Delegated from the document, so it covers markup that arrives later from
     * an AJAX update without anything having to register it.
     */
    (function installTooltips() {
        var tip = null;
        var current = null;
        var SHOW_AFTER = 120;

        function bubble() {
            if (!tip) {
                tip = document.createElement("div");
                tip.className = "tm-tip";
                tip.setAttribute("role", "tooltip");
                document.body.appendChild(tip);
            }
            return tip;
        }

        function place(el) {
            var box = el.getBoundingClientRect();
            var node = bubble();
            var width = node.offsetWidth;
            var height = node.offsetHeight;

            var x = box.left + box.width / 2 - width / 2;
            x = Math.max(8, Math.min(x, window.innerWidth - width - 8));

            /* Above by default, below when there is no room above: a tooltip on
               the first row of a table otherwise opens off the top of the
               viewport and is clipped. */
            var above = box.top - height - 10 > 0;
            var y = above ? box.top - height - 10 : box.bottom + 10;

            node.classList.toggle("tm-tip-below", !above);
            node.style.left = x + "px";
            node.style.top = y + "px";
        }

        function show(el) {
            var text = el.getAttribute("title");
            if (!text) {
                return;
            }
            el.setAttribute("data-tm-tip", text);
            el.removeAttribute("title");
            current = el;

            var node = bubble();
            node.textContent = text;
            place(el);
            /* Measured after the text is in it and before the fade, or a
               two-line bubble is positioned as though it were one line. */
            window.requestAnimationFrame(function () {
                place(el);
                node.classList.add("tm-tip-visible");
            });
        }

        function hide() {
            if (current) {
                var text = current.getAttribute("data-tm-tip");
                if (text) {
                    current.setAttribute("title", text);
                    current.removeAttribute("data-tm-tip");
                }
                current = null;
            }
            if (tip) {
                tip.classList.remove("tm-tip-visible");
            }
        }

        var timer = null;

        function target(node) {
            while (node && node !== document.body) {
                if (node.nodeType === 1
                        && (node.hasAttribute("title") || node.hasAttribute("data-tm-tip"))) {
                    return node;
                }
                node = node.parentNode;
            }
            return null;
        }

        document.addEventListener("mouseover", function (event) {
            var el = target(event.target);
            if (el === current) {
                return;
            }
            window.clearTimeout(timer);
            hide();
            if (!el) {
                return;
            }
            timer = window.setTimeout(function () {
                show(el);
            }, SHOW_AFTER);
        });

        document.addEventListener("mouseout", function () {
            window.clearTimeout(timer);
            hide();
        });

        /* Keyboard users get the same text, on focus rather than on hover. */
        document.addEventListener("focusin", function (event) {
            var el = target(event.target);
            window.clearTimeout(timer);
            hide();
            if (el) {
                show(el);
            }
        });

        document.addEventListener("focusout", function () {
            window.clearTimeout(timer);
            hide();
        });

        document.addEventListener("keydown", function (event) {
            if (event.key === "Escape") {
                window.clearTimeout(timer);
                hide();
            }
        });

        window.addEventListener("scroll", hide, true);
    })();

    /*
     * Colours the charts again after a theme change.
     *
     * The charts are Chart.js configuration built in Java from the palette the
     * server chose, so a canvas cannot follow the theme the way the rest of the
     * page does. The alternative to this was a page reload, which took the
     * reader's place with it and read as a glitch rather than as a change of
     * colour.
     *
     * Nothing here knows what the colours are. The caller reads the palette
     * before the theme changes and after it, and every colour in every chart
     * that matches an entry in the one is replaced by its counterpart in the
     * other. A chart added later is covered without anything being registered.
     */
    window.tmRecolourCharts = function (before, after) {
        if (!window.Chart || !before || !after) {
            return;
        }

        var map = {};
        Object.keys(before).forEach(function (name) {
            var from = (before[name] || "").trim().toLowerCase();
            var to = (after[name] || "").trim();
            if (from && to && from !== to.toLowerCase()) {
                map[from] = to;
            }
        });

        function swap(colour) {
            return typeof colour === "string"
                ? (map[colour.trim().toLowerCase()] || colour)
                : colour;
        }

        function swapAll(value) {
            return Array.isArray(value) ? value.map(swap) : swap(value);
        }

        var instances = window.Chart.instances || {};
        Object.keys(instances).forEach(function (key) {
            var chart = instances[key];
            if (!chart || !chart.config) {
                return;
            }
            ((chart.config.data || {}).datasets || []).forEach(function (dataset) {
                dataset.backgroundColor = swapAll(dataset.backgroundColor);
                dataset.borderColor = swapAll(dataset.borderColor);
                dataset.pointBackgroundColor = swapAll(dataset.pointBackgroundColor);
            });

            var options = chart.config.options || {};
            var plugins = options.plugins || {};
            ["legend", "tooltip"].forEach(function (name) {
                var plugin = plugins[name];
                if (!plugin) {
                    return;
                }
                ["color", "backgroundColor", "titleColor", "bodyColor", "borderColor"].forEach(function (prop) {
                    if (plugin[prop] !== undefined) {
                        plugin[prop] = swap(plugin[prop]);
                    }
                });
                var labels = plugin.labels;
                if (labels && labels.color !== undefined) {
                    labels.color = swap(labels.color);
                }
            });

            var scales = options.scales || {};
            Object.keys(scales).forEach(function (axis) {
                var scale = scales[axis] || {};
                if (scale.grid && scale.grid.color !== undefined) {
                    scale.grid.color = swap(scale.grid.color);
                }
                if (scale.border && scale.border.color !== undefined) {
                    scale.border.color = swap(scale.border.color);
                }
                if (scale.ticks && scale.ticks.color !== undefined) {
                    scale.ticks.color = swap(scale.ticks.color);
                }
            });

            /* 'none' rather than the default, so the series do not animate
               from zero again: this is a change of colour, not of data. */
            chart.update("none");
        });
    };

    /*
     * Brings a form into view and outlines it.
     *
     * Used by the Duplicate action, which fills the add form further up the
     * page. Without this the row it was clicked from is the only thing that
     * changes, and the values land in a form that is off screen: the reader
     * sees a flicker and concludes nothing happened.
     *
     * preventScroll on the focus call, because the smooth scroll is already
     * running and letting the browser jump to the field would cancel it.
     */
    window.tmRevealForm = function (id) {
        var form = document.getElementById(id);
        if (!form) {
            return;
        }
        form.scrollIntoView({ behavior: "smooth", block: "center" });
        form.classList.remove("tm-reveal");
        /* Re-added on the next frame so a second click restarts the animation
           rather than being ignored because the class is already applied. */
        window.requestAnimationFrame(function () {
            form.classList.add("tm-reveal");
        });
        window.setTimeout(function () {
            form.classList.remove("tm-reveal");
        }, 1800);

        var first = form.querySelector("input:not([type=hidden]), textarea, select");
        if (first) {
            first.focus({ preventScroll: true });
        }
    };

    /*
     * Keeping the reader's place across a postback.
     *
     * Most actions here are full-page postbacks, because the pages that carry
     * them navigate server-side and the successful ones end in a redirect. A
     * POST that renders the same view produces a new document, and a browser
     * paints a new document at scroll zero. So a reader who clicks "Make admin"
     * halfway down a long trip page is thrown to the top of it and has to find
     * their place again, and the confirmation appears on a part of the page
     * they are no longer looking at.
     *
     * There is no browser mechanism to preserve this: scroll restoration
     * applies to history navigation, not to a form post. So the position is
     * recorded on submit and put back on load.
     *
     * Only when it is the same page. An action that redirects somewhere else
     * should land at the top, which is what a reader expects of a navigation,
     * and restoring a previous position there would drop them into the middle
     * of a page they have never seen.
     */
    var SCROLL_KEY = "tm.scroll";

    function rememberScroll() {
        try {
            window.sessionStorage.setItem(SCROLL_KEY, JSON.stringify({
                path: window.location.pathname,
                y: window.scrollY,
                at: Date.now()
            }));
        } catch (unavailable) {
            /* Private browsing, or storage disabled. Losing the scroll
               position is a small thing to lose; a thrown error here would
               stop the form from submitting at all. */
        }
    }

    function restoreScroll() {
        var saved;
        try {
            var raw = window.sessionStorage.getItem(SCROLL_KEY);
            window.sessionStorage.removeItem(SCROLL_KEY);
            if (!raw) {
                return;
            }
            saved = JSON.parse(raw);
        } catch (unusable) {
            return;
        }

        /* Same page, and recent. The age check is what stops a position from
           an abandoned form being applied to a page the reader reached some
           other way later on. */
        if (!saved || saved.path !== window.location.pathname) {
            return;
        }
        if (Date.now() - saved.at > 15000) {
            return;
        }
        window.scrollTo(0, saved.y);
    }

    document.addEventListener("submit", rememberScroll, true);

    /*
     * Saying that something is happening.
     *
     * Faces sends an event around every AJAX exchange, so one handler covers
     * every component that uses one. A full postback has no such event, so the
     * submit listener below covers that, and the two together are the whole of
     * what the application does that is not a plain link.
     *
     * A counter rather than a boolean: two requests can overlap, and the first
     * one to finish must not clear the bar while the second is still running.
     */
    var inFlight = 0;

    function busy(delta) {
        inFlight = Math.max(0, inFlight + delta);
        document.documentElement.classList.toggle("tm-loading", inFlight > 0);
    }

    /*
     * PrimeFaces dispatches its own jQuery events around every AJAX exchange,
     * and that is the hook used here.
     *
     * The standard Faces client API is not an option on this build: neither
     * `faces.ajax` nor `jsf.ajax` is on the window, which was checked rather
     * than assumed, and hooking a global that does not exist fails silently in
     * exactly the way that looks like the feature was never written.
     *
     * Bound through jQuery because these are jQuery events, not DOM ones, so
     * addEventListener never sees them.
     */
    if (window.jQuery) {
        window.jQuery(document)
                .on("pfAjaxStart", function () {
                    busy(1);
                })
                .on("pfAjaxComplete", function () {
                    busy(-1);
                });
    }

    /* Anything that reads as "we are leaving this page". `pageshow` puts it
       back if the browser restores the page from its cache instead of loading
       it, which would otherwise leave the bar running forever. */
    document.addEventListener("submit", function () {
        busy(1);
    }, true);
    window.addEventListener("pageshow", function () {
        inFlight = 0;
        document.documentElement.classList.remove("tm-loading");
    });

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", restoreScroll);
    } else {
        restoreScroll();
    }

    /*
     * Chart tooltips.
     *
     * The report charts are Chart.js configuration assembled in Java and handed
     * to p:chart as JSON, and JSON cannot carry a function. That is why this is
     * here and not beside the rest of the chart configuration in Charts.java:
     * the tooltip has to format its numbers, formatting is a callback, and a
     * callback cannot be serialised. Without this the tooltip printed the raw
     * value, so hovering a bar showed "11925" where every other surface in the
     * application shows "NPR 11,925.00".
     *
     * Written as a global default rather than per chart, for two reasons.
     *
     * It has to be a default, because a per-chart callback cannot be installed
     * after the fact. `chart.options` is a resolver rather than the config: a
     * write to `chart.options.plugins.tooltip.callbacks` lands, but every read
     * re-resolves it against Chart.js's own defaults, which define a label
     * callback of their own, and the default wins. The chart then reads back as
     * having the library's callback with no error to explain it.
     *
     * And it can be global, because every chart in this application is money.
     * The category breakdown, what each member paid, the daily spend and the
     * trip totals are all amounts, so there is nothing to choose between and
     * nothing to configure per chart.
     *
     * This runs at parse time, before PrimeFaces builds the charts on DOM ready,
     * which is what makes the default apply to them.
     */
    (function installChartTooltips() {
        if (!window.Chart || !window.Chart.defaults) {
            /* No charting library on this page, which is every page but the
               reports. Nothing to do. */
            return;
        }
        var plugins = window.Chart.defaults.plugins =
            window.Chart.defaults.plugins || {};
        var tooltip = plugins.tooltip = plugins.tooltip || {};
        var callbacks = tooltip.callbacks = tooltip.callbacks || {};

        /*
         * Colours are read from the stylesheet rather than written here, and
         * that is deliberate: the chart palette is defined once, for both
         * themes, as custom properties in tripmate.css, and the server-side
         * chart configuration already refers to those same values by name.
         * Reading them keeps the canvas from disagreeing with the page.
         *
         * It was disagreeing. The tooltip was drawn in the light theme's ink on
         * a page whose floor is a near-black, so it was a dark panel on a dark
         * background with no edge, and the grid lines were drawn in the light
         * theme's grey, which on a dark chart is brighter than the data.
         */
        var root = window.getComputedStyle(document.documentElement);
        var cssVar = function (name) {
            return (root.getPropertyValue(name) || "").trim();
        };
        var isDark = document.documentElement.getAttribute("data-theme") === "dark";

        tooltip.backgroundColor = isDark
            ? (cssVar("--tm-color-surface-strong") || "#2a2a31")
            : "#222222";
        /* On the light theme the panel is ink and the text on it is white; on
           the dark theme the panel is a raised surface and the text is the
           page's own ink. Inverting either one gives white on white. */
        tooltip.titleColor = isDark ? cssVar("--tm-color-ink") : "#ffffff";
        tooltip.bodyColor = isDark ? cssVar("--tm-color-body") : "#ffffff";
        tooltip.borderColor = isDark
            ? cssVar("--tm-color-hairline")
            : "rgba(255, 255, 255, 0.14)";
        tooltip.borderWidth = 1;

        /* Grid and axis labels, the same way. Chart.js keeps its scale defaults
           per axis type, so all four are set rather than whichever one this
           chart happens to use. */
        var gridColour = cssVar("--tm-chart-grid");
        var axisColour = cssVar("--tm-chart-axis");
        if (gridColour && axisColour) {
            ["linear", "category", "logarithmic", "radialLinear"].forEach(function (type) {
                var scale = (window.Chart.defaults.scales || {})[type];
                if (!scale) {
                    return;
                }
                scale.grid = scale.grid || {};
                scale.grid.color = gridColour;
                scale.ticks = scale.ticks || {};
                scale.ticks.color = axisColour;
                scale.border = scale.border || {};
                scale.border.color = gridColour;
            });
        }

        callbacks.label = function (context) {
            var name = context.dataset && context.dataset.label
                ? context.dataset.label + ': '
                : '';

            /* A bar and a line parse to {x, y}; a doughnut parses to the number
               itself. Reading `y` first covers the cartesian case without
               having to know which chart this is. */
            var raw = context.parsed;
            if (raw !== null && typeof raw === 'object') {
                raw = raw.y !== undefined && raw.y !== null ? raw.y : raw.x;
            }
            if (raw === null || raw === undefined || isNaN(raw)) {
                return name;
            }

            /* Thousands separators and two decimals, matching how the table
               beside the chart writes the same figures. */
            var parts = Number(raw).toFixed(2).split('.');
            var grouped = parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',');
            return name + 'NPR ' + grouped + '.' + parts[1];
        };
    })();

})();
