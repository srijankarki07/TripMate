package com.tripmate.mail;

import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.annotation.Resource;
import jakarta.ejb.Asynchronous;
import jakarta.ejb.Stateless;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

/**
 * Sends the application's transactional email.
 *
 * <h2>Sending never fails the thing that caused it</h2>
 *
 * Every method here returns nothing and throws nothing. Registering an account,
 * or being added to a trip, has already succeeded by the time an email is
 * attempted, and a mail server that is down or a password that has expired must
 * not turn a successful registration into an error page. Failures are logged at
 * WARNING with enough detail to diagnose, and the caller carries on.
 *
 * <p>That is a deliberate trade. The alternative, propagating, would mean the
 * user sees a failure for an action that worked, and either retries and creates
 * a duplicate, or gives up on an account that exists.
 *
 * <h2>Asynchronous, and why that matters for the transaction</h2>
 *
 * {@link #send} is {@code @Asynchronous}. The container defers the call until
 * the caller's transaction commits, so an email is never sent for a write that
 * then rolls back, and the caller never waits on an SMTP handshake. The
 * argument is a plain value object for the same reason: nothing in it points at
 * a live entity that will have been detached by the time the other thread runs.
 *
 * <h2>When there is no SMTP server</h2>
 *
 * The session carries a {@code tripmate.deliver} property, set by
 * {@code scripts/glassfish-mail.sh} from whether an SMTP host was configured.
 * When it is false, the message is rendered in full and written to the log
 * instead of being sent. A checkout with no credentials therefore still runs and
 * can still be demonstrated, and nothing silently disappears.
 *
 * <p>This is read from the session rather than from an environment variable
 * because a Java EE application has no reliable access to the environment the
 * server was started in, and because the container's own configuration is the
 * right place for it: the same artifact then runs against a logging transport
 * on a laptop and a real one in a deployment, with no code difference.
 */
@Stateless
public class MailService {

    private static final Logger LOG = Logger.getLogger(MailService.class.getName());

    /** The session the container built from the mail resource. */
    @Resource(lookup = "mail/TripMate")
    private Session session;

    /**
     * Sends a message, or logs it when no transport is configured.
     *
     * <p>Never throws. See the class comment for why.
     *
     * @param email the message, which must already be fully rendered
     */
    @Asynchronous
    public void send(Email email) {
        if (email == null || email.getTo() == null || email.getTo().isBlank()) {
            // Worth a warning rather than silence: it means a caller tried to
            // notify somebody whose address it did not have, which is a bug in
            // the caller rather than a condition to absorb quietly.
            LOG.warning("Refusing to send a mail with no recipient: " + email);
            return;
        }

        if (!isDeliveryConfigured()) {
            logInsteadOfSending(email);
            return;
        }

        try {
            MimeMessage message = build(email);
            // Sent through the transport the session already carries, so the
            // container closes it and the credentials never appear here.
            Transport.send(message);
            LOG.info("Sent '" + email.getSubject() + "' to " + email.getMaskedRecipient());
        } catch (MessagingException | RuntimeException ex) {
            // Logged, not rethrown. See the class comment: the action that
            // caused this has already succeeded and must not be reported as
            // having failed.
            LOG.log(Level.WARNING,
                    "Could not send '" + email.getSubject() + "' to "
                            + email.getMaskedRecipient() + "'",
                    ex);
        }
    }

    /**
     * @return true when an SMTP host was configured for this deployment
     */
    public boolean isDeliveryConfigured() {
        if (session == null) {
            return false;
        }
        return Boolean.parseBoolean(session.getProperty("tripmate.deliver"));
    }

    /**
     * The deployment's public base URL, for building links in a message.
     *
     * <p>Read from the mail session because mail is composed outside a request.
     * A link has to be absolute, the server handling the send has no way to know
     * what hostname a user reached it by, and reading it from an environment
     * variable is not portable across containers. The container's configuration
     * is the one place that knows.
     *
     * @return the base URL with no trailing slash, never null
     */
    public String getBaseUrl() {
        String configured = session == null ? null : session.getProperty("tripmate.baseUrl");
        if (configured == null || configured.isBlank()) {
            // Better a link that is wrong on a deployment than a message with a
            // hole in it. A localhost URL in a test inbox is immediately obvious
            // as a misconfiguration; an empty href is not.
            return "http://localhost:8080/tripmate";
        }
        String trimmed = configured.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /**
     * Renders the message into the log.
     *
     * <p>The body is written in full rather than summarised: with no transport
     * configured this is the only way to read what the application would have
     * sent, which is what makes the templates reviewable without credentials.
     * The recipient is masked even here, because a log is a shared artefact.
     */
    private void logInsteadOfSending(Email email) {
        LOG.info("""

                ===== MAIL (no transport configured; not sent) =====
                To:      %s
                Subject: %s
                ---------------------------------------------------
                %s
                ==================================================="""
                .formatted(email.getMaskedRecipient(), email.getSubject(), email.getTextBody()));
    }

    /**
     * Assembles the MIME message.
     *
     * <p>Multipart with a plain-text part first, which is the order clients
     * expect and the order that decides what a client showing one part shows.
     * A message with only an HTML part scores worse with spam filters and is
     * unreadable in a client that refuses HTML.
     *
     * @param email the rendered message
     * @return the message, ready to hand to a transport
     * @throws MessagingException if the addresses or the parts are rejected
     */
    private MimeMessage build(Email email) throws MessagingException {
        MimeMessage message = new MimeMessage(session);

        Address from = InternetAddress.parse(session.getProperty("mail.from"))[0];
        message.setFrom(from);
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(email.getTo()));
        message.setSubject(email.getSubject(), "UTF-8");

        MimeBodyPart text = new MimeBodyPart();
        text.setText(email.getTextBody(), "UTF-8");

        MimeBodyPart html = new MimeBodyPart();
        html.setContent(email.getHtmlBody(), "text/html; charset=UTF-8");

        MimeMultipart both = new MimeMultipart("alternative");
        both.addBodyPart(text);
        both.addBodyPart(html);
        message.setContent(both);

        return message;
    }
}
