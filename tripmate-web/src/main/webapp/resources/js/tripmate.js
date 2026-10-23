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

    /*
     * Copy-to-clipboard for the calendar subscribe address.
     *
     * The address is a credential, so it is shown in a read-only field rather
     * than as text in a paragraph: a reader can select it by hand, and the
     * field is wide enough to see the whole thing. The button is the
     * convenience.
     *
     * Delegated from the document rather than bound to the button, because the
     * dialog is re-rendered by Ajax every time it opens and every time the
     * address is reset, and a bound listener would be attached to an element
     * that has already been thrown away.
     *
     * This is the one thing on the page that the server cannot do, and it is
     * therefore also the one thing that degrades: with JavaScript off, the
     * field is still there and still selects, and the button does nothing.
     */
    (function installCopyButtons() {
        var RESET_MS = 2000;

        function copy(text) {
            /* The async clipboard API is unavailable on an insecure origin,
               which a laptop running this on http://localhost is. Falling back
               to a selection is not a failure: it leaves the text selected, so
               the reader's next keystroke is Ctrl-C. */
            if (navigator.clipboard && window.isSecureContext) {
                return navigator.clipboard.writeText(text);
            }
            return Promise.reject(new Error('clipboard unavailable'));
        }

        function select(field) {
            field.focus();
            field.select();
        }

        document.addEventListener('click', function (event) {
            var button = event.target.closest('[data-tm-copy]');
            if (!button) {
                return;
            }
            var field = document.getElementById(button.getAttribute('data-tm-copy'));
            if (!field) {
                return;
            }

            var original = button.textContent;
            var say = function (message) {
                button.textContent = message;
                window.setTimeout(function () {
                    button.textContent = original;
                }, RESET_MS);
            };

            copy(field.value).then(
                function () { say('Copied'); },
                function () { select(field); say('Press Ctrl-C'); });
        });
    })();

    /*
     * The travel map.
     *
     * Leaflet, vendored beside this file rather than pulled from a CDN, so the
     * map is still there on a machine with no internet beyond the map tiles
     * themselves.
     *
     * The map object is built once and never rebuilt. The container sits
     * outside every form for that reason: PrimeFaces replaces the whole subtree
     * of whatever a response updates, and replacing the container takes the map
     * with it, leaving an empty box. What the map draws is read from inside the
     * form instead, and redrawn whenever an update lands.
     *
     * Three details are deliberate.
     *
     * The colours are read from the stylesheet rather than written here, the
     * same way the charts do it, so a marker is the colour of the status chip
     * beside it on the trips list and the two cannot drift apart. Reading a
     * custom property needs the computed style of the root element, not the
     * element itself: asking the element for `getPropertyValue` throws, which
     * is how this map first came out blank.
     *
     * A past trip is drawn as a dashed outline rather than a filled dot, not
     * only in a different colour, because colour is the first thing lost to
     * colour blindness and the legend should not be the only thing saying which
     * kind a point is.
     *
     * The map is only fitted to its points once. Fitting on every redraw would
     * move the map out from under a reader who is part way through placing
     * something.
     */
    (function installTravelMap() {
        var container = document.getElementById('tmMap');
        if (!container || typeof window.L === 'undefined') {
            return;
        }

        var root = window.getComputedStyle(document.documentElement);
        var cssVar = function (name, fallback) {
            return (root.getPropertyValue(name) || '').trim() || fallback;
        };

        var colourFor = function (point) {
            if (point.kind === 'PAST_TRIP') {
                return cssVar('--tm-chart-5', '#4a3aa7');
            }
            switch (point.status) {
                case 'PLANNING':  return cssVar('--tm-color-info', '#1f5fa8');
                case 'ONGOING':   return cssVar('--tm-color-success', '#0f7b52');
                case 'CANCELLED': return cssVar('--tm-color-danger', '#c13515');
                default:          return cssVar('--tm-color-muted', '#6a6a6a');
            }
        };

        var escape = function (value) {
            return String(value === null || value === undefined ? '' : value)
                .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
                .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
        };

        var STATUS_LABEL = {
            PLANNING: 'Being planned',
            ONGOING: 'Under way',
            COMPLETED: 'Finished',
            CANCELLED: 'Called off'
        };

        var money = function (amount) {
            var value = Number(amount);
            if (!isFinite(value) || value === 0) {
                return null;
            }
            var parts = value.toFixed(2).split('.');
            return 'NPR ' + parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',') + '.' + parts[1];
        };

        var line = function (text) {
            return text ? '<p class="tm-map-tip-line">' + escape(text) + '</p>' : '';
        };

        var tooltipFor = function (point) {
            var html = '<p class="tm-map-tip-title">' + escape(point.name) + '</p>';

            if (point.kind === 'PAST_TRIP') {
                html += '<p class="tm-map-tip-kind">Before TripMate</p>';
                html += line(point.place);
                html += line(point.visited);
            } else {
                html += '<p class="tm-map-tip-kind">'
                     + escape(STATUS_LABEL[point.status] || point.status || '') + '</p>';
                html += line(point.place);
                html += line(point.start ? point.start + (point.end ? ' to ' + point.end : '') : '');
                var figures = [];
                if (point.items) {
                    figures.push(point.items + (point.items === 1 ? ' item' : ' items'));
                }
                if (point.members) {
                    figures.push(point.members + (point.members === 1 ? ' person' : ' people'));
                }
                var spent = money(point.spent);
                if (spent) {
                    figures.push(spent + ' spent');
                }
                html += line(figures.join(' \u00b7 '));
            }

            if (point.note) {
                html += '<p class="tm-map-tip-note">' + escape(point.note) + '</p>';
            }
            return html;
        };

        /*
         * A pin, as an inline drawing rather than Leaflet's marker image.
         *
         * The image files were not vendored alongside the script, and one
         * drawing used for every pin is one place to change it. It is drawn with
         * `currentColor` so the pin takes the colour of whatever it marks.
         */
        var pinIcon = function (colour) {
            return L.divIcon({
                className: 'tm-map-pin',
                iconSize: [30, 42],
                iconAnchor: [15, 42],
                html: '<svg viewBox="0 0 30 42" width="30" height="42" aria-hidden="true">'
                    + '<path d="M15 41C15 41 28 25.5 28 15A13 13 0 1 0 2 15c0 10.5 13 26 13 26z" '
                    + 'fill="' + colour + '" stroke="' + cssVar('--tm-color-canvas', '#fff') + '" stroke-width="2"/>'
                    + '<circle cx="15" cy="15" r="5" fill="' + cssVar('--tm-color-canvas', '#fff') + '"/>'
                    + '</svg>'
            });
        };

        var map = L.map(container, { scrollWheelZoom: true, worldCopyJump: true });
        L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
            maxZoom: 19,
            /* Required by the tile usage policy, and the page repeats it in
               text for a reader who never hovers the map. */
            attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
        }).addTo(map);

        var drawn = [];
        /* The standing labels, with the region each belongs to, so they can be
           shown and hidden as the zoom changes. */
        var labels = [];
        var fitted = false;
        var overlay = null;
        var pendingPin = null;

        /* Everything the map needs, from the part of the page the server
           rewrites. Absent before the first update, and absent again if the
           form is ever removed. */
        var readState = function () {
            var data = document.getElementById('tmMapData');
            if (!data) {
                return { points: [], placing: false, placingName: '' };
            }
            var points;
            try {
                points = JSON.parse(data.getAttribute('data-points') || '[]');
            } catch (unreadable) {
                points = [];
            }
            return {
                points: points,
                placing: data.getAttribute('data-placing') === 'true',
                placingName: data.getAttribute('data-placing-name') || ''
            };
        };

        var setOverlay = function (text) {
            if (!text) {
                if (overlay) {
                    overlay.parentNode.removeChild(overlay);
                    overlay = null;
                }
                return;
            }
            if (!overlay) {
                overlay = document.createElement('div');
                overlay.className = 'tm-map-overlay';
                container.appendChild(overlay);
                /* Leaflet does not know this is here, and a control would
                   fight it for the corner. It is a sibling of the panes. */
            }
            overlay.textContent = 'Click the map to place ' + text + '.';
        };

        var draw = function () {
            var state = readState();

            drawn.forEach(function (layer) { map.removeLayer(layer); });
            drawn = [];
            labels.forEach(function (entry) { map.removeLayer(entry.label); });
            labels = [];

            state.points.forEach(function (point) {
                var past = point.kind === 'PAST_TRIP';
                var colour = colourFor(point);

                /*
                 * The place itself, filled.
                 *
                 * The real boundary OpenStreetMap holds, not a circle around
                 * the middle: a reader who has been to Pokhara sees the shape
                 * of Pokhara. It is drawn under the marker, and it carries both
                 * the hover tooltip and a standing label, because a filled
                 * region with nothing written on it is a coloured shape and not
                 * a place.
                 *
                 * Non-interactive would be simpler and would let every hover
                 * through to the dot, which is nine pixels across and hard to
                 * hit. The region is the bigger target and the better one.
                 */
                if (point.area) {
                    var shape = L.geoJSON(point.area, {
                        style: {
                            color: colour,
                            weight: 2,
                            opacity: 0.8,
                            fillColor: colour,
                            fillOpacity: 0.3
                        }
                    }).addTo(map);

                    /* Bound to each shape inside the group rather than to the
                       group. A group forwards `bindTooltip` to its children,
                       and a GeoJSON group has none until it is on the map, so
                       binding to the group is a call that quietly does nothing. */
                    shape.eachLayer(function (child) {
                        child.bindTooltip(tooltipFor(point), {
                            direction: 'top',
                            sticky: true,
                            opacity: 1,
                            className: 'tm-map-tip'
                        });
                    });

                    /* The name, standing on the region, as a tooltip of its own
                       rather than the layer's. A layer holds one tooltip, so
                       the second `bindTooltip` replaced the first and the
                       region ended up with neither. This one belongs to the map
                       and is placed at the middle of the boundary, which is
                       rarely the clicked point. */
                    var label = L.tooltip({
                        permanent: true,
                        direction: 'center',
                        className: 'tm-map-label',
                        interactive: false
                    })
                        .setContent(escape(point.name))
                        .setLatLng(shape.getBounds().getCenter());

                    labels.push({ shape: shape, label: label });
                    drawn.push(shape);
                }

                var marker = L.circleMarker([point.lat, point.lon], {
                    radius: past ? 6 : 9,
                    color: colour,
                    weight: past ? 2 : 3,
                    dashArray: past ? '3 3' : null,
                    fillColor: colour,
                    fillOpacity: past ? 0.25 : 0.65,
                    /* Above the tiles but below the tooltip. */
                    pane: 'markerPane'
                }).addTo(map);

                /* With a region drawn, the region carries the tooltip and this
                   is only a precise mark inside it. Two tooltips a few pixels
                   apart make a hover flicker between them. */
                if (!point.area) {
                    marker.bindTooltip(tooltipFor(point), {
                        direction: 'top',
                        offset: [0, -8],
                        opacity: 1,
                        className: 'tm-map-tip'
                    });
                }
                drawn.push(marker);
            });

            if (!fitted) {
                if (drawn.length) {
                    /* Framed on the layers themselves rather than on the
                       points, so a region is fitted by its boundary. Fitting
                       on the clicked point would put a district's centre in
                       the middle of the view and run half of it off the side. */
                    map.fitBounds(L.featureGroup(drawn).getBounds(),
                            { padding: [40, 40], maxZoom: 9 });
                } else {
                    /* Nothing to frame yet, so somewhere the sample data
                       suggests rather than the Gulf of Guinea, which is where
                       a map centred on nothing lands and which reads as
                       broken. */
                    map.setView([28.2, 84.0], 6);
                }
                fitted = true;
            }

            container.classList.toggle('tm-map-placing', state.placing);
            setOverlay(state.placing ? state.placingName : null);
            showLabelsThatFit();
        };

        /*
         * A name is written on a region only when the region is big enough to
         * hold one.
         *
         * Every label at every zoom sounds better than it looks: framed on the
         * whole map, six regions are a few pixels each and their names pile up
         * on one another into an unreadable knot over Nepal. A label that
         * cannot be read is worse than no label, because it hides the map
         * underneath it as well.
         *
         * Re-run on every zoom, so zooming into a district brings its name with
         * it.
         */
        var showLabelsThatFit = function () {
            var minimumWidth = 70;
            var minimumHeight = 26;

            labels.forEach(function (entry) {
                var box = entry.shape.getBounds();
                var corner = map.latLngToContainerPoint(box.getNorthWest());
                var opposite = map.latLngToContainerPoint(box.getSouthEast());
                var wide = Math.abs(opposite.x - corner.x) >= minimumWidth;
                var tall = Math.abs(opposite.y - corner.y) >= minimumHeight;
                var fits = wide && tall;

                if (fits && !map.hasLayer(entry.label)) {
                    entry.label.addTo(map);
                } else if (!fits && map.hasLayer(entry.label)) {
                    map.removeLayer(entry.label);
                }
            });
        };

        /* A click only means something while something is waiting to be placed,
           which is also when the crosshair is showing. */
        map.on('click', function (event) {
            if (!container.classList.contains('tm-map-placing')) {
                return;
            }
            var lat = document.getElementById('mapForm:clickLat');
            var lon = document.getElementById('mapForm:clickLon');
            if (!lat || !lon || typeof window.tmApplyMapClick !== 'function') {
                return;
            }
            lat.value = event.latlng.lat.toFixed(6);
            lon.value = event.latlng.lng.toFixed(6);

            /* Dropped immediately, before the server has answered. The point
               the trip becomes will replace it a moment later, and until then
               this is the only thing telling the reader their click landed. */
            if (pendingPin) {
                map.removeLayer(pendingPin);
            }
            pendingPin = L.marker(event.latlng, {
                icon: pinIcon(cssVar('--tm-color-primary', '#ff385c')),
                interactive: false
            }).addTo(map);

            window.tmApplyMapClick();
        });

        /* A panel opening or the window changing shape does not tell Leaflet,
           and a map sized while it was hidden draws its tiles in the wrong
           places until it is asked to measure itself again. */
        map.on('zoomend', showLabelsThatFit);

        var remeasure = function () { map.invalidateSize(); };
        window.addEventListener('resize', remeasure);
        if (window.ResizeObserver) {
            new ResizeObserver(remeasure).observe(container);
        }

        draw();

        if (window.jQuery) {
            window.jQuery(document).on('pfAjaxComplete', function () {
                /* After the response has been applied, not during it. */
                window.setTimeout(draw, 0);
                /* The dialog's map is a second live object, and a response that
                   re-rendered the dialog has quietly taken its container away. */
                window.setTimeout(window.tmPreparePickMap, 0);
            });
        }
    })();

    /*
     * The tooltip on the drawing of the map on the public home page.
     *
     * That drawing is a static illustration, not a Leaflet map, so it does not
     * get Leaflet's tooltips. It shows the same bubble anyway, built from the
     * same classes, because a reader who has seen the map page should
     * recognise this as the same thing rather than as a picture of it.
     *
     * The content is read from attributes on whichever shape is pointed at, so
     * the page stays a document: with no script there is no bubble, and every
     * figure the bubble would have shown is written out in the band above.
     *
     * Nothing here is generated by the server and nothing is fetched, so the
     * only thing to be careful about is that the attributes are put into
     * markup, which is what the escaping is for.
     */
    (function installLandingMapTooltip() {
        var root = document.querySelector("[data-tm-map-mock]");
        if (!root) {
            return;
        }

        var tip = root.querySelector(".tm-mock-map-tip");
        if (!tip) {
            return;
        }

        /* The shape the bubble currently belongs to, or null. */
        var current = null;

        function escape(value) {
            return String(value === null || value === undefined ? "" : value)
                .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
                .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
        }

        function line(value) {
            return value ? '<p class="tm-map-tip-line">' + escape(value) + "</p>" : "";
        }

        function place(area) {
            /* Measured after the text is in it, or a two-line bubble is placed
               as though it were one line. */
            var shape = area.getBoundingClientRect();
            var host = root.getBoundingClientRect();
            var half = tip.offsetWidth / 2;

            /* Centred on the shape, and held inside the drawing. A district
               near the edge would otherwise put half its bubble over the
               panel's border. */
            var x = shape.left + shape.width / 2 - host.left;
            x = Math.max(half + 2, Math.min(x, host.width - half - 2));

            tip.style.left = x + "px";
            tip.style.top = (shape.top - host.top) + "px";
        }

        function show(area) {
            tip.innerHTML = '<p class="tm-map-tip-title">'
                          + escape(area.getAttribute("data-tip-title")) + "</p>"
                          + '<p class="tm-map-tip-kind">'
                          + escape(area.getAttribute("data-tip-kind")) + "</p>"
                          + line(area.getAttribute("data-tip-date"))
                          + line(area.getAttribute("data-tip-figures"));
            tip.hidden = false;
            current = area;
            place(area);
        }

        function hide() {
            current = null;
            tip.hidden = true;
        }

        /* The district whose bubble is showing when nothing else is.

           A map that says nothing until it is pointed at is a map a reader has
           to guess at, and this drawing is about two hundred pixels tall. So
           one trip introduces itself, and the drawing falls back to it the
           moment the pointer leaves. */
        var fallback = root.querySelector("[data-tip-default]");

        function rest() {
            if (fallback) {
                show(fallback);
            } else {
                hide();
            }
        }

        /* Followed rather than dismissed.

           The bubble is positioned in the page, and the drawing moves under it
           when the page scrolls. Hiding it instead would be simpler and worse:
           a reader who scrolls with the pointer already over a district gets no
           new mouseover when they stop, so the bubble would stay gone until
           they left the shape and came back. */
        function follow() {
            if (current) {
                place(current);
            }
        }

        window.addEventListener("scroll", follow, { passive: true });
        window.addEventListener("resize", follow, { passive: true });

        Array.prototype.forEach.call(
            root.querySelectorAll(".tm-mock-map-area"),
            function (area) {
                area.addEventListener("mouseenter", function () { show(area); });
                /* Focusable in the markup, so this is not decoration: it is the
                   only way to reach the same four lines without a pointer. */
                area.addEventListener("focus", function () { show(area); });
                area.addEventListener("blur", rest);
            }
        );

        /* Leaving the drawing rather than leaving a shape. Moving from one
           district to the next fires both events in the same tick, and
           restoring on the way out would flash the default bubble between
           them. */
        root.addEventListener("mouseleave", rest);

        /* After the first frame, so the drawing has its size: measured before
           that, a bubble is positioned against a box that is still zero. */
        window.requestAnimationFrame(rest);
    })();

    /*
     * The map inside the "somewhere you have been" dialog.
     *
     * Created when the dialog opens, because a Leaflet map built while its
     * container is `display: none` measures zero and then draws its tiles in
     * the wrong places. Rebuilt when the container has been replaced, which is
     * what an update that re-rendered the dialog leaves behind.
     *
     * Exposed on the window because the dialog asks for it by name from
     * `onShow`, which is a client callback and has no other way in.
     */
    (function installPickMap() {
        var map = null;
        var marker = null;
        var held = null;

        var pinIcon = function (colour, edge) {
            return L.divIcon({
                className: 'tm-map-pin',
                iconSize: [30, 42],
                iconAnchor: [15, 42],
                html: '<svg viewBox="0 0 30 42" width="30" height="42" aria-hidden="true">'
                    + '<path d="M15 41C15 41 28 25.5 28 15A13 13 0 1 0 2 15c0 10.5 13 26 13 26z" '
                    + 'fill="' + colour + '" stroke="' + edge + '" stroke-width="2"/>'
                    + '<circle cx="15" cy="15" r="5" fill="' + edge + '"/></svg>'
            });
        };

        var hidden = function (suffix) {
            return document.getElementById('placeForm:' + suffix);
        };

        var send = function (lat, lon) {
            var latField = hidden('pickLat');
            var lonField = hidden('pickLon');
            if (!latField || !lonField) {
                return;
            }
            latField.value = lat.toFixed(6);
            lonField.value = lon.toFixed(6);
            if (typeof window.tmPlacePicked === 'function') {
                window.tmPlacePicked();
            }
        };

        var move = function (latlng, announce) {
            if (!marker) {
                marker = L.marker(latlng, {
                    draggable: true,
                    icon: pinIcon(
                        window.getComputedStyle(document.documentElement)
                            .getPropertyValue('--tm-color-primary').trim() || '#ff385c',
                        window.getComputedStyle(document.documentElement)
                            .getPropertyValue('--tm-color-canvas').trim() || '#ffffff'),
                    autoPan: true
                }).addTo(map);

                /* Dragging is the point: it is how somebody corrects a pin that
                   landed on the next village without losing the name they have
                   already been given. */
                marker.on('dragend', function () {
                    var at = marker.getLatLng();
                    send(at.lat, at.lng);
                });
            } else {
                marker.setLatLng(latlng);
            }
            if (announce) {
                send(latlng.lat, latlng.lng);
            }
        };

        window.tmPreparePickMap = function () {
            var div = document.getElementById('tmPickMap');
            if (!div) {
                return;
            }

            /* Alive and still the same element: nothing to do but let it
               measure itself again, which it may need after the dialog resized. */
            if (map && held === div && div.querySelector('.leaflet-map-pane')) {
                map.invalidateSize();
                return;
            }

            map = L.map(div, { scrollWheelZoom: true, worldCopyJump: true });
            held = div;
            marker = null;

            L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
                maxZoom: 19,
                attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
            }).addTo(map);

            var latField = hidden('pickLat');
            var lonField = hidden('pickLon');
            var existingLat = latField && latField.value ? Number(latField.value) : null;
            var existingLon = lonField && lonField.value ? Number(lonField.value) : null;

            if (existingLat && existingLon) {
                map.setView([existingLat, existingLon], 9);
                move([existingLat, existingLon], false);
            } else {
                /* Somewhere the sample data suggests, for the same reason the
                   main map does: an empty ocean reads as broken. */
                map.setView([28.2, 84.0], 6);
            }

            map.on('click', function (event) {
                move(event.latlng, true);
            });

            /* The dialog animates open, so the container is still settling when
               this runs. */
            window.setTimeout(function () { map.invalidateSize(); }, 250);
        };
    })();

})();
