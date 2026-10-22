package com.tripmate.web.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;

/**
 * Raises the message a person sees after an action.
 *
 * <p>Replaces the {@code String error} and {@code String message} fields every
 * backing bean used to carry. Those had three problems. A page could only show
 * one outcome at a time, because the field held a single string. Neither
 * survived the {@code faces-redirect} that most successful actions perform, so
 * a bean that saved and redirected had nowhere to put "Trip saved" and every
 * page grew its own {@code ?submitted=1} view parameter to work around it. And
 * nothing distinguished a success from a failure, which is why both rendered as
 * the same grey box.
 *
 * <p>A toast has to survive two different endings, and they need two different
 * mechanisms.
 *
 * <p>An action that succeeds usually ends in a redirect, and a redirect throws
 * the request away, so the message is put into flash scope, which JSF carries
 * across one. An action that fails usually redisplays the page instead, and
 * then the render phase runs inside the same request. Flash scope does not help
 * there: a value put during the action phase is only guaranteed to be readable
 * during the <em>next</em> request, so a failed sign-in would have shown
 * nothing at all. That was the first version of this class, and the regression
 * is why the request-scoped bucket below exists.
 *
 * <p>So a toast goes into both, and whichever bucket the reader finds first
 * wins: the request one when the page is being rendered in the same request,
 * the flash one when the page was reached by a redirect. Reading clears both,
 * so a message cannot be shown twice.
 *
 * <p>Static, like {@code PasswordHasher} in the business tier: this is a
 * mechanism, not state, and a bean should be able to raise a toast without
 * having one injected.
 */
public final class Toasts {

    /**
     * The flash key. Namespaced because flash scope is shared with anything
     * else that might use it.
     */
    private static final String KEY = "tm.toasts";

    /**
     * The request-scoped key, held in the Faces context's own attribute map so
     * it is discarded with the request.
     */
    private static final String REQUEST_KEY = "tm.toasts.request";

    private Toasts() {
    }

    /** Reports that something the person asked for happened. */
    public static void success(String text) {
        add(Toast.Kind.SUCCESS, text);
    }

    /** Neutral information that is not a result of an action. */
    public static void info(String text) {
        add(Toast.Kind.INFO, text);
    }

    /** Something needs attention but nothing failed. */
    public static void warning(String text) {
        add(Toast.Kind.WARNING, text);
    }

    /**
     * Something the person asked for did not happen.
     *
     * @param text a sentence worth reading, never a raw exception message; see
     *             {@link Messages#describe(Throwable)} for turning a failure
     *             into one
     */
    public static void error(String text) {
        add(Toast.Kind.ERROR, text);
    }

    private static void add(Toast.Kind kind, String text) {
        if (text == null || text.isBlank()) {
            // A toast with nothing in it is a bug in the caller, not something
            // to render. Failing loudly here would take down an action that
            // otherwise succeeded.
            return;
        }
        List<Toast> pending = requestBucket();
        pending.add(new Toast(kind, text));
        // Mirrored rather than moved, so both endings are covered. A fresh list
        // goes into flash because flash may serialise it into the session, and
        // handing it a list this request still holds would alias the two.
        flash().put(KEY, new ArrayList<>(pending));
    }

    /**
     * The messages waiting to be shown, in the order they were raised.
     *
     * <p>Reads without clearing, which is deliberate. An earlier version
     * consumed the bucket once, on the reasoning that a message should be shown
     * exactly once and a cached list is cheaper than rebuilding one. It was
     * cheaper and it was wrong: the view's bean can be created before the action
     * that raises the message, so the one and only drain ran too early and the
     * message was never seen at all.
     *
     * <p>Not clearing is safe because neither bucket outlives its welcome. The
     * request bucket dies with the request, and flash scope is discarded by JSF
     * once it has been carried across the single redirect it exists for. Reading
     * twice within one render therefore returns the same list, which is what the
     * component wants: it asks once to decide whether to render the stack at
     * all, and once to render the items inside it.
     */
    static List<Toast> pending() {
        List<Toast> fromRequest = copyOf(requestAttributes().get(REQUEST_KEY));
        if (!fromRequest.isEmpty()) {
            return fromRequest;
        }
        // Empty means this page was reached by a redirect, so the message is
        // waiting in the bucket that crosses one.
        return copyOf(flash().get(KEY));
    }

    private static List<Toast> copyOf(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Toast> copy = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Toast toast) {
                copy.add(toast);
            }
        }
        return copy;
    }

    /**
     * The list being collected into during this request.
     *
     * <p>Reused rather than replaced so several raises accumulate into one
     * bucket instead of the last one winning.
     */
    @SuppressWarnings("unchecked")
    private static List<Toast> requestBucket() {
        Object existing = requestAttributes().get(REQUEST_KEY);
        if (existing instanceof List<?> list) {
            return (List<Toast>) list;
        }
        List<Toast> fresh = new ArrayList<>();
        requestAttributes().put(REQUEST_KEY, fresh);
        return fresh;
    }

    /**
     * The Faces context's request attribute map.
     *
     * <p>Keyed by Object rather than String, which is the type the API returns;
     * narrowing it here would be a cast that the compiler is right to refuse.
     */
    @SuppressWarnings("unchecked")
    private static Map<Object, Object> requestAttributes() {
        return FacesContext.getCurrentInstance().getAttributes();
    }

    private static Map<String, Object> flash() {
        ExternalContext external = FacesContext.getCurrentInstance().getExternalContext();
        return external.getFlash();
    }
}
