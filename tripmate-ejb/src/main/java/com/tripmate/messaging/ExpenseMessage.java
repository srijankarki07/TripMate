package com.tripmate.messaging;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

import com.tripmate.dto.ExpenseDTO;

/**
 * The payload of an expense submitted for asynchronous writing.
 *
 * <p>Carries the {@link ExpenseDTO} that is to be stored, plus enough context to
 * make the message traceable on its own: who submitted it, when, and a
 * correlation id that identifies this submission in the logs.
 *
 * <p>Deliberately not the DTO alone. A DTO describes what to write; this
 * describes a request to write it, and the two have different lifetimes. The
 * correlation id in particular belongs to the submission rather than to the
 * expense, and would be out of place on a DTO that is also returned by queries.
 *
 * <h2>A note on the wire format</h2>
 *
 * Sent as a JMS object message, which means Java serialisation. That is
 * acceptable here because the only producer is this application and the only
 * consumer is its own message-driven bean, both from the same EAR, so the
 * classes on either side cannot drift. It would not be acceptable for a queue
 * that anything outside the application could write to, because deserialising
 * attacker-controlled bytes is a well-known way to run arbitrary code.
 */
public class ExpenseMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private final ExpenseDTO expense;
    private final Long submittedBy;
    private final LocalDateTime submittedAt;
    private final String correlationId;

    public ExpenseMessage(ExpenseDTO expense, Long submittedBy) {
        this(expense, submittedBy, LocalDateTime.now(), UUID.randomUUID().toString());
    }

    public ExpenseMessage(ExpenseDTO expense, Long submittedBy,
                          LocalDateTime submittedAt, String correlationId) {
        this.expense = expense;
        this.submittedBy = submittedBy;
        this.submittedAt = submittedAt;
        this.correlationId = correlationId;
    }

    public ExpenseDTO getExpense() {
        return expense;
    }

    /** Who asked for the expense to be written. */
    public Long getSubmittedBy() {
        return submittedBy;
    }

    /** When it was submitted, which is not when it was written. */
    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    /** Identifies this submission in the log, and ties the two together. */
    public String getCorrelationId() {
        return correlationId;
    }

    @Override
    public String toString() {
        return "ExpenseMessage{correlationId=" + correlationId
                + ", submittedBy=" + submittedBy
                + ", description='" + (expense == null ? null : expense.getDescription()) + "'"
                + ", amount=" + (expense == null ? null : expense.getAmount()) + "}";
    }
}
