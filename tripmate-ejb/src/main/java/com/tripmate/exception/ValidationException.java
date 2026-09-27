package com.tripmate.exception;

import jakarta.ejb.ApplicationException;

/**
 * Input the business rules refuse, as opposed to a fault in the system.
 *
 * <h2>Why this is not just an IllegalArgumentException</h2>
 *
 * A runtime exception thrown out of a session bean is a <em>system</em>
 * exception to the container, and the container marks the caller's transaction
 * rollback-only as a result. That is correct for a fault: the transaction's work
 * is suspect and should not be committed.
 *
 * <p>It is wrong for a rejected input. Nothing was written, so there is nothing
 * to roll back, and the rollback-only marking causes real damage in one
 * specific place: a message-driven bean that catches the exception and returns
 * normally still has its transaction rolled back, so the message is never
 * acknowledged and is redelivered. The consumer rejects the same message
 * forever, as fast as the container can deliver it.
 *
 * <p>Annotating the exception with {@code rollback = false} tells the container
 * this is a business outcome rather than a fault, so the transaction stays
 * usable and the consumer can acknowledge the message it has just rejected.
 *
 * <p>The distinction only becomes visible when something is put on the queue
 * that the consumer cannot process. Through the web tier the rollback is
 * harmless: each request is its own transaction and it was going to end anyway.
 */
@ApplicationException(rollback = false)
public class ValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ValidationException(String message) {
        super(message);
    }

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
