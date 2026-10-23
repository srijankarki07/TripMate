package com.tripmate.entity;

/**
 * How a trip summary send ended up.
 *
 * <p>The log used to record only that a send had been asked for, which reads as
 * "this went out" whether or not it did. A summary that failed because the mail
 * server was down left exactly the same line as one that arrived, so the
 * administrator being asked why nobody received it was told by the application
 * that everybody had.
 *
 * <p>Stored by name rather than by ordinal, so the record keeps its meaning if
 * this enum is ever reordered.
 */
public enum SummaryDeliveryStatus {

    /** Every recipient the send was addressed to accepted the message. */
    SENT("Sent"),

    /** Some accepted it and some did not. */
    PARTIAL("Partly sent"),

    /** The messages were attempted and none got through. */
    FAILED("Not sent"),

    /**
     * Nothing was attempted, because this deployment has no SMTP host
     * configured. The messages were written to the server log instead.
     */
    NOT_CONFIGURED("Not sent (no mail server)");

    private final String label;

    SummaryDeliveryStatus(String label) {
        this.label = label;
    }

    /** @return a short phrase for the log table */
    public String getLabel() {
        return label;
    }

    /** @return true when this status is worth drawing attention to */
    public boolean isProblem() {
        return this != SENT;
    }
}
