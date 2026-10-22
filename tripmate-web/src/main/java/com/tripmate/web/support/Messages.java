package com.tripmate.web.support;

import java.util.logging.Level;
import java.util.logging.Logger;

import com.tripmate.exception.ValidationException;

/**
 * Turns an exception into something worth showing a person.
 *
 * <p>Every backing bean used to carry its own copy of a {@code rootMessage}
 * helper, seven copies in total across three different fallback sentences. Each
 * one walked to the root cause and returned its message verbatim, which meant
 * the page displayed whatever the container or the JDBC driver had written.
 * That is unhelpful when it says the transaction rolled back, and it is worse
 * than unhelpful when it names a table, a column or a constraint.
 *
 * <p>The rule here is the one the business tier already follows. A
 * {@link ValidationException} is the application's own message, written for a
 * person to read, so it passes through untouched. Anything else is a failure
 * the application did not anticipate: the person gets one plain sentence and
 * the detail goes to the server log, where the developer can see it and the
 * page cannot leak it.
 *
 * <p>Walking the whole cause chain rather than only its end matters. A call to a
 * session bean arrives wrapped in an {@code EJBException} whose own message is
 * null, so the sentence worth showing sits somewhere in the middle.
 */
public final class Messages {

    private static final Logger LOG = Logger.getLogger(Messages.class.getName());

    /** What a person sees when something failed that nobody planned for. */
    public static final String UNEXPECTED =
            "Something went wrong at our end. Nothing was saved. Please try again.";

    private Messages() {
    }

    /**
     * @param thrown the failure a backing bean caught
     * @return a sentence safe to render, never null and never empty
     */
    public static String describe(Throwable thrown) {
        ValidationException validation = findValidation(thrown);
        if (validation != null) {
            String message = validation.getMessage();
            if (message != null && !message.isBlank()) {
                return message;
            }
        }

        // Not a rejection the application made deliberately. Keep the detail,
        // but keep it in the log rather than on the page.
        LOG.log(Level.WARNING, "Unhandled failure reaching the user as a generic message", thrown);
        return UNEXPECTED;
    }

    /**
     * The first validation rejection anywhere in the cause chain.
     *
     * @return the rejection, or null when the failure was not one
     */
    private static ValidationException findValidation(Throwable thrown) {
        Throwable cause = thrown;
        // Bounded rather than while-true: a self-referential cause chain would
        // otherwise spin forever, and the loop guard below only catches a
        // cause that points at itself.
        for (int depth = 0; cause != null && depth < 32; depth++) {
            if (cause instanceof ValidationException validation) {
                return validation;
            }
            Throwable next = cause.getCause();
            if (next == cause) {
                break;
            }
            cause = next;
        }
        return null;
    }
}
