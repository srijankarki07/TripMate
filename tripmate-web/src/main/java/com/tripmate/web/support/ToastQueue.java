package com.tripmate.web.support;

import java.io.Serializable;
import java.util.List;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Named;

/**
 * Exposes the pending toasts to the view.
 *
 * <p>The component that renders them needs a list, and {@link Toasts} is a
 * static mechanism with no getter of its own, so this is the adapter between
 * the two.
 *
 * <p>Every call reads through to {@link Toasts#pending()} rather than holding a
 * list captured once at construction. Capturing is the obvious optimisation and
 * it does not work here: this bean can be created before the action that raises
 * the message, so a list captured at that moment is empty and stays empty, and
 * the message raised a moment later is never rendered. Reading through on each
 * call costs an instanceof check over a list that is almost always empty.
 *
 * <p>Request-scoped: the toasts belong to one response, not to a conversation.
 */
@Named
@RequestScoped
public class ToastQueue implements Serializable {

    private static final long serialVersionUID = 1L;

    public List<Toast> getToasts() {
        return Toasts.pending();
    }

    public boolean isHasToasts() {
        return !Toasts.pending().isEmpty();
    }
}
