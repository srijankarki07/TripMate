package com.tripmate.messaging;

import java.util.logging.Level;
import java.util.logging.Logger;

import com.tripmate.dto.ExpenseDTO;
import com.tripmate.exception.ValidationException;
import com.tripmate.remote.ExpenseServiceRemote;

import jakarta.ejb.ActivationConfigProperty;
import jakarta.ejb.EJB;
import jakarta.ejb.MessageDriven;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageListener;

/**
 * Writes an expense that was submitted to the queue.
 *
 * <h2>Why the write is asynchronous</h2>
 *
 * Adding an expense writes one row and then a row per participant, and the
 * splits have to be written in the same transaction as the expense or the two
 * could disagree. That is a multi-record write the browser does not need to
 * wait for, and doing it through a queue also gives a natural place to add
 * notifications or analytics later without touching the caller.
 *
 * <p>See ROADMAP section 10.1 for the full rationale.
 *
 * <h2>Which failures are retried and which are not</h2>
 *
 * This is the part that matters operationally. A message that throws is rolled
 * back and redelivered, and a message that will never succeed would then be
 * redelivered forever, filling the log and the dead-letter queue.
 *
 * <p>So the two cases are separated:
 *
 * <ul>
 *   <li>A rejected message, meaning the data itself is wrong, is logged and
 *       consumed. Retrying cannot help, because the same bytes will fail the
 *       same way.</li>
 *   <li>Anything else, such as the database being unreachable, is allowed to
 *       propagate so the container redelivers. That is the case where retrying
 *       is exactly the right behaviour.</li>
 * </ul>
 *
 * <p>The distinction is drawn on {@link IllegalArgumentException}, which is
 * what the service throws for bad input. It is a coarse test, but it is the one
 * the service contract actually makes.
 */
@MessageDriven(activationConfig = {
        @ActivationConfigProperty(
                propertyName = "destinationLookup",
                propertyValue = "jms/ExpenseQueue"),
        @ActivationConfigProperty(
                propertyName = "destinationType",
                propertyValue = "jakarta.jms.Queue")
})
public class ExpenseMessageBean implements MessageListener {

    private static final Logger LOG = Logger.getLogger(ExpenseMessageBean.class.getName());

    /**
     * Injected through the remote interface, the same one the web tier uses.
     * The bean adds nothing to the write path; it exists to move the write off
     * the request thread, not to do the writing differently.
     */
    @EJB
    private ExpenseServiceRemote expenseService;

    @Override
    public void onMessage(Message message) {
        ExpenseMessage payload;
        try {
            payload = message.getBody(ExpenseMessage.class);
        } catch (JMSException malformed) {
            // The message could not be read at all. Retrying will not change
            // that, so it is logged and consumed.
            LOG.log(Level.SEVERE, "Unreadable expense message, discarding: "
                    + malformed.getMessage(), malformed);
            return;
        }

        if (payload == null || payload.getExpense() == null) {
            LOG.warning("Expense message carried no expense, discarding");
            return;
        }

        String correlationId = payload.getCorrelationId();
        ExpenseDTO expense = payload.getExpense();

        try {
            ExpenseDTO stored = expenseService.persistExpense(expense);

            LOG.info("Expense written from the queue [correlationId=" + correlationId
                    + "] expenseId=" + stored.getExpenseId()
                    + " tripId=" + stored.getTripId()
                    + " description='" + stored.getDescription() + "'"
                    + " amount=" + stored.getAmount()
                    + " splits=" + stored.getSplits().size()
                    + " submittedBy=" + payload.getSubmittedBy()
                    + " submittedAt=" + payload.getSubmittedAt());

        } catch (RuntimeException failure) {
            if (isPermanent(failure)) {
                // The data is wrong, so the same message would fail identically
                // on every redelivery. Logged and dropped rather than thrown, so
                // it does not loop.
                LOG.warning("Expense rejected by the business rules, discarding"
                        + " [correlationId=" + correlationId + "]"
                        + " description='" + expense.getDescription() + "'"
                        + " reason=" + rootMessage(failure));
                return;
            }

            // Anything else, such as the database being unreachable. Rethrown so
            // the container rolls back and redelivers, which is what retrying is
            // for.
            LOG.log(Level.SEVERE, "Could not write the expense, will be redelivered"
                    + " [correlationId=" + correlationId + "]", failure);
            throw failure;
        }
    }

    /**
     * Whether a failure would fail the same way on every retry.
     *
     * <p>Walks the cause chain rather than testing the exception that arrives,
     * because a runtime exception thrown by a session bean does not reach the
     * caller as itself. The container wraps it: what lands here is an
     * {@code EJBTransactionRolledbackException} with the
     * {@link IllegalArgumentException} several links down.
     *
     * <p>This was not obvious. Testing the top-level type compiles, reads
     * correctly, and silently classifies every failure as retryable, so a
     * message that can never succeed is redelivered forever. The distinction
     * only shows up when something is deliberately put on the queue that the
     * consumer cannot process.
     *
     * <p>The test matches on the service contract: refused input is a
     * {@link ValidationException}. Anything else is treated as a transient
     * fault, because redelivering something that might succeed is safer than
     * discarding something that would have.
     *
     * <p>Note that discarding only works because {@code ValidationException} is
     * declared {@code @ApplicationException(rollback = false)}. Without that the
     * failed call would leave the transaction rollback-only, returning normally
     * would still roll it back, the message would never be acknowledged, and it
     * would be redelivered as fast as the container could manage. That is not a
     * theoretical concern: it is what happened the first time this was run, and
     * it produced thousands of log lines in seconds.
     */
    private static boolean isPermanent(Throwable thrown) {
        Throwable cause = thrown;
        while (cause != null) {
            if (cause instanceof ValidationException) {
                return true;
            }
            cause = cause.getCause() == cause ? null : cause.getCause();
        }
        return false;
    }

    /** The message from the deepest cause, which is the one worth reading. */
    private static String rootMessage(Throwable thrown) {
        Throwable cause = thrown;
        Throwable next = cause.getCause();
        while (next != null && next != cause) {
            cause = next;
            next = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }
}
