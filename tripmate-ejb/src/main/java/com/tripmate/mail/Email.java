package com.tripmate.mail;

import java.io.Serializable;

/**
 * One message, ready to send.
 *
 * <p>A value class rather than a pile of arguments, because the parts travel
 * together and an async call site is easier to read with one argument than with
 * five. It is also what makes the logged fallback useful: when no SMTP host is
 * configured, this is what gets written to the log, and it holds everything the
 * message would have carried.
 *
 * <p>Serializable because the send is asynchronous. An {@code @Asynchronous}
 * call hands the argument to another thread, and the container is entitled to
 * serialise it to do so. A plain object graph of strings satisfies that without
 * the container having to complain at runtime.
 */
public class Email implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String to;
    private final String subject;
    private final String htmlBody;
    private final String textBody;

    /**
     * @param to       the recipient's address
     * @param subject  the subject line, which must be a single line of text
     * @param htmlBody the HTML part, which must already be escaped and assembled
     * @param textBody the plain-text alternative, which must already be written
     */
    public Email(String to, String subject, String htmlBody, String textBody) {
        this.to = to;
        this.subject = subject;
        this.htmlBody = htmlBody;
        this.textBody = textBody;
    }

    public String getTo() {
        return to;
    }

    public String getSubject() {
        return subject;
    }

    public String getHtmlBody() {
        return htmlBody;
    }

    public String getTextBody() {
        return textBody;
    }

    /**
     * @return the recipient with the domain kept but the local part shortened,
     *         for a log line. A full address in a log is a record of who was
     *         emailed, which is more than an operator needs to diagnose a send.
     */
    public String getMaskedRecipient() {
        if (to == null || to.isBlank()) {
            return "(none)";
        }
        int at = to.indexOf('@');
        if (at < 1) {
            return "***";
        }
        return to.charAt(0) + "***" + to.substring(at);
    }
}
