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
})();
